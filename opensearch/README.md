# L1ES — OpenSearch Plugin for Log10x

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Search and query [Log10x-encoded](https://doc.log10x.com/run/transform/#encoding) log data directly within OpenSearch with zero data loss. This open-source plugin transparently decodes encoded events at query time, maintaining full search and alerting capabilities while [reducing storage and licensing costs by over 50%](https://doc.log10x.com/apps/edge/optimizer/).

This is the OpenSearch variant of the L1ES plugin. See the [main README](../README.md) for full documentation including Kibana transparent rewriting, the User Guide, and configuration details.

| | |
|---|---|
| **Version** | 0.9.0 |
| **OpenSearch** | 2.19.0 |
| **Java** | 17+ |
| **License** | Apache 2.0 |

## How It Works

Log10x compacts log events into two parts:

1. **Templates** — the recurring structure with `$` placeholders for variable values
2. **Encoded events** — a compact representation: `~<template-hash>,<value1>,<value2>,...`

L1ES stores the templates in an internal index and, at query time:

1. Matches your search terms against the template patterns
2. Finds encoded events that use matching templates
3. Decodes each result back to the original log line
4. Returns the decoded content in a field you specify

Standard OpenSearch queries see only the encoded text and cannot match original content. L1ES custom query types bridge this gap.

## Quick Start

### 1. Build the Plugin

From the repository root:

```bash
./gradlew :opensearch:build
```

This produces the plugin zip at `opensearch/build/distributions/l1es-plugin-0.9.0.os.2.19.0.zip`. See [Building from Source](#building-from-source) for prerequisites.

### 2. Install the Plugin

```bash
bin/opensearch-plugin install file:///path/to/l1es-plugin-0.9.0.os.2.19.0.zip
```

Or with Docker:

```dockerfile
FROM opensearchproject/opensearch:2.19.0
COPY l1es-plugin-0.9.0.os.2.19.0.zip /tmp/l1es-plugin.zip
RUN opensearch-plugin install --batch file:///tmp/l1es-plugin.zip
```

**Important:** After installation, verify that the config file exists at `config/l1es-plugin/l1es.yml` inside your OpenSearch installation directory. If the file is not there, copy it manually:

```bash
mkdir -p config/l1es-plugin
cp plugins/l1es-plugin/config/l1es.yml config/l1es-plugin/l1es.yml
```

### 3. Initialize the Plugin

After OpenSearch starts:

```bash
curl -X POST 'http://localhost:9200/_l1es/setup'
```

This creates the internal indices (`l1es_dml` for template storage, `l1es_dml_indices` for field mappings). DML (Data Matching Library) is the internal template lookup engine that maps encoded events back to their original structure.

### 4. Load Templates

Bulk-load your template file (one JSON object per line) into the `l1es_dml` index:

```bash
# Each line of templates.json: {"templateHash":"<hash>","template":"<pattern>"}
# Convert to bulk format and load:
while IFS= read -r line; do
  hash=$(echo "$line" | python3 -c "import sys,json; print(json.load(sys.stdin)['templateHash'])")
  pattern=$(echo "$line" | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin)['template']))")
  printf '{"index":{"_index":"l1es_dml","_id":"%s"}}\n{"pattern":%s}\n' "$hash" "$pattern"
done < templates.json > /tmp/bulk_templates.ndjson

curl -X POST 'http://localhost:9200/_bulk' \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary @/tmp/bulk_templates.ndjson
```

### 5. Register the Encoded Field

Tell L1ES which index and field contain encoded data:

```bash
curl -X POST 'http://localhost:9200/_l1es/add-dml-index' \
  -H 'Content-Type: application/json' \
  -d '{
    "index_name": "my-logs",
    "source": "message",
    "dest": "decoded_message"
  }'
```

- `index_name` — your data index
- `source` — the field containing encoded events
- `dest` — the field name for decoded output (defaults to `source` if omitted)

### 6. Load Encoded Data

Index your encoded log events into the data index as usual:

```bash
curl -X POST 'http://localhost:9200/my-logs/_bulk' \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary @encoded_events.ndjson
```

### 7. Search

Use L1ES query types to search the decoded content:

```bash
# Phrase search
curl -X POST 'http://localhost:9200/my-logs/_search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "l1es_match_phrase": {
        "message": {
          "query": "Accounting service started"
        }
      }
    },
    "fields": ["message"]
  }'
```

The `fields` parameter is required to trigger the fetch sub-phase that decodes results. The decoded content appears in the `decoded_message` field (or whatever you specified as `dest`).

## Query Types

L1ES provides three query types that mirror standard OpenSearch queries:

### l1es_match

Token-based search. Supports `AND`/`OR` operators.

```json
{
  "l1es_match": {
    "message": {
      "query": "error database",
      "operator": "AND"
    }
  }
}
```

Parameters: `query` (required), `operator`, `analyzer`, `fuzziness`, `prefix_length`, `max_expansions`, `minimum_should_match`, `fuzzy_rewrite`, `fuzzy_transpositions`, `lenient`, `zero_terms_query`, `auto_generate_synonyms_phrase_query`.

### l1es_match_phrase

Exact phrase search. Tokens must appear in order.

```json
{
  "l1es_match_phrase": {
    "message": {
      "query": "service started successfully",
      "slop": 0
    }
  }
}
```

Parameters: `query` (required), `analyzer`, `slop`, `zero_terms_query`.

### l1es_multi_match

Search across multiple fields.

```json
{
  "l1es_multi_match": {
    "query": "connection timeout",
    "fields": ["message", "error_log"],
    "operator": "AND"
  }
}
```

Parameters: `query` (required), `fields`, `type` (best_fields, most_fields, phrase, phrase_prefix), `operator`, `analyzer`, `slop`, `fuzziness`, `prefix_length`, `max_expansions`, `minimum_should_match`, `fuzzy_rewrite`, `tie_breaker`, `lenient`, `zero_terms_query`, `auto_generate_synonyms_phrase_query`, `fuzzy_transpositions`.

## REST API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `_l1es` | GET | Plugin info (version, description) |
| `_l1es/setup` | POST | Create internal indices |
| `_l1es/cleanup` | POST | Remove internal indices |
| `_l1es/add-dml-index` | POST | Register encoded field mapping |
| `_l1es/remove-dml-index` | POST | Unregister encoded field mapping |

## Configuration

The plugin reads its configuration from `config/l1es-plugin/l1es.yml` inside the OpenSearch installation directory. After plugin installation, this file should be at:

```
$OPENSEARCH_HOME/config/l1es-plugin/l1es.yml
```

If it is missing, copy it from the plugin directory:

```bash
cp $OPENSEARCH_HOME/plugins/l1es-plugin/config/l1es.yml \
   $OPENSEARCH_HOME/config/l1es-plugin/l1es.yml
```

Configuration options:

```yaml
flags:
  enabled: true                      # Master switch
  decoding_enabled: true             # Enable fetch sub-phase decoding
  match_query_enabled: true          # Enable l1es_match
  match_pharse_query_enabled: true   # Enable l1es_match_phrase
  multi_match_query_enabled: true    # Enable l1es_multi_match

encoder:
  hasEncodedLinePrefix: true         # Encoded lines start with a prefix character
  encodedLinePrefix: "~"             # The prefix character
  valueSeperator: ","                # Separator between values in encoded lines

dmldb:
  indicesIndexNumberOfShards: 1      # Shards for l1es_dml_indices
  indicesIndexNumberOfReplicas: 1    # Replicas for l1es_dml_indices
  dmlIndexNumberOfShards: 1          # Shards for l1es_dml
  dmlIndexNumberOfReplicas: 1        # Replicas for l1es_dml
  dmlSizeToSearch: 10000             # Max templates to scan per query
```

The `encoder` section must match the format produced by your Log10x encoder. The defaults above match the standard Log10x output format (`~<hash>,<val1>,<val2>,...`).

**Note:** If the config file is missing or unreadable, the plugin falls back to built-in defaults. The built-in defaults for the encoder use `hasEncodedLinePrefix: false` and space-separated values, which do NOT match the standard Log10x output format. Always verify the config file is present.

## Differences from Elasticsearch Variant

The OpenSearch plugin is functionally identical to the Elasticsearch variant. The same query types, REST API, and configuration options apply. Key differences:

| | Elasticsearch | OpenSearch |
|---|---|---|
| Plugin file | `l1es-plugin-0.9.0.es.8.17.0.zip` | `l1es-plugin-0.9.0.os.2.19.0.zip` |
| Install command | `elasticsearch-plugin install` | `opensearch-plugin install` |
| Base image | `elasticsearch:8.17.0` | `opensearchproject/opensearch:2.19.0` |
| Config path | `config/l1es-plugin/l1es.yml` (auto-copied) | `config/l1es-plugin/l1es.yml` (verify after install) |
| Security | Disable `xpack.security` for testing | Disable security plugin for testing |

## Building from Source

### Prerequisites

- Java 17 (JDK)
- Gradle 8.5+

All dependencies (including `log10x-decoder-core`) are resolved automatically from Maven Central.

### Build

From the repository root:

```bash
./gradlew :opensearch:build -x test
```

The plugin zip is produced at `opensearch/build/distributions/l1es-plugin-0.9.0.os.2.19.0.zip`.

To build both Elasticsearch and OpenSearch variants:

```bash
./gradlew build -x test
```

### Docker Image

```bash
docker run -d --name l1es-opensearch \
  -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "DISABLE_SECURITY_PLUGIN=true" \
  opensearchproject/opensearch:2.19.0

# Install plugin
docker cp l1es-plugin-0.9.0.os.2.19.0.zip l1es-opensearch:/tmp/
docker exec l1es-opensearch opensearch-plugin install --batch file:///tmp/l1es-plugin-0.9.0.os.2.19.0.zip

# Ensure config is in place
docker exec l1es-opensearch mkdir -p /usr/share/opensearch/config/l1es-plugin
docker exec l1es-opensearch cp /usr/share/opensearch/plugins/l1es-plugin/config/l1es.yml \
  /usr/share/opensearch/config/l1es-plugin/l1es.yml

docker restart l1es-opensearch
```

## Source Generation

The OpenSearch plugin sources are auto-generated from the Elasticsearch plugin via a Gradle `Copy` task with text replacements (`org.elasticsearch.*` to `org.opensearch.*`, class renames, etc.). Only four files with significant API differences are manually maintained:

- `L1esPlugin.java` — plugin lifecycle API
- `L1esFetchSubPhase.java` — fetch sub-phase API
- `L1esMultiMatchQueryBuilder.java` — transport version API
- `ElasticJob.java` — request builder API

Changes to the Elasticsearch plugin automatically propagate to the OpenSearch build.

## Compatibility

| L1ES Version | OpenSearch | Java | Lucene |
|-------------|-----------|------|--------|
| 0.9.0 | 2.19.0 | 17+ | 9.12.0 |

## Troubleshooting

### Config not loading (queries return 0 results)

Check OpenSearch logs for: `Got null settings, falling back to default`

This means `config/l1es-plugin/l1es.yml` is missing. Copy it from the plugin directory and restart:

```bash
mkdir -p $OPENSEARCH_HOME/config/l1es-plugin
cp $OPENSEARCH_HOME/plugins/l1es-plugin/config/l1es.yml \
   $OPENSEARCH_HOME/config/l1es-plugin/l1es.yml
```

### Jackson jar conflict

If you see `java.lang.LinkageError` or class loading errors mentioning Jackson, verify that the plugin zip does NOT include Jackson JARs. OpenSearch provides Jackson at runtime — bundling a second copy causes jar hell.

### Plugin not loading

Verify the plugin is compatible with your OpenSearch version:

```bash
curl http://localhost:9200/_l1es
```

The response should include `"l1es": "0.9.0"`. If the endpoint is not found, check the OpenSearch startup logs for plugin loading errors.

## License

This repository is licensed under the [Apache License 2.0](../LICENSE).

### Important: Log10x Product License Required

This repository contains an Elasticsearch/OpenSearch plugin for decoding Log10x-encoded events. While the plugin itself is open source, **using the Log10x Edge Optimizer to encode events requires a commercial license**.

| Component | License |
|-----------|---------|
| This repository (Elasticsearch/OpenSearch plugin) | Apache 2.0 (open source) |
| Log10x Edge Optimizer | Commercial license required |

**Get Started:**
- [Log10x Pricing](https://log10x.com/pricing)
- [Documentation](https://doc.log10x.com)
- [Contact Sales](mailto:sales@log10x.com)

## Contributing

Contributions are welcome! Please read our contributing guidelines and submit pull requests to the repository.

## Support

For issues and feature requests:
- Open an issue on [GitHub](https://github.com/log-10x/elasticsearch-plugin/issues)
- Contact the Log10x team at [support@log10x.com](mailto:support@log10x.com)
