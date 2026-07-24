# L1ES — Elasticsearch Plugin for Log10x

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

Search and query [compact](https://doc.log10x.com/run/transform/#compact) Log10x events directly within Elasticsearch and OpenSearch with zero data loss. This open-source [Log10x](https://www.log10x.com/?utm_source=github&utm_medium=readme&utm_campaign=elasticsearch-plugin&utm_content=hero) plugin transparently expands compact events at query time, maintaining full search, Kibana, and alerting capabilities while [reducing storage and licensing costs by over 50%](https://doc.log10x.com/apps/receiver/).

> **Blog:** [Cutting Elasticsearch log storage roughly in half without changing queries](https://www.log10x.com/blog/cutting-elasticsearch-log-storage/?utm_source=github&utm_medium=readme&utm_campaign=elasticsearch-plugin&utm_content=blog). How Elasticsearch stores fewer bytes and still returns the original log lines.

| | |
|---|---|
| **Version** | 1.0.0 |
| **Elasticsearch** | 8.17.0 |
| **OpenSearch** | 2.19.0 |
| **Java** | 17+ |
| **License** | Apache 2.0 |

## How It Works

Log10x compacts log events into two parts:

1. **Templates** — the recurring structure with `$` placeholders for variable values
2. **Encoded events** — a compact representation: `~<template-hash>,<value1>,<value2>,...`

L1ES stores the templates in an internal index and, at query time:

1. Matches your search terms against the template patterns
2. Finds compact events that use matching templates
3. Decodes each result back to the original log line
4. Returns the expanded content in a field you specify

Standard Elasticsearch queries see only the compact text and cannot match original content. L1ES bridges this gap in two ways:

1. **Custom query types** (`l1es_match`, `l1es_match_phrase`, `l1es_multi_match`) — explicit queries for compact fields
2. **Transparent query rewriting** — automatically converts standard `match`, `match_phrase`, and `multi_match` queries to their L1ES equivalents, so Kibana dashboards, saved searches, and KQL queries work without changes

## Quick Start

### 1. Build the Plugin

```bash
./gradlew build
```

This produces the plugin zip at `build/distributions/l1es-plugin-1.0.0.es.8.17.0.zip`. See [Building from Source](#building-from-source) for prerequisites and alternative build methods.

### 2. Install the Plugin

```bash
bin/elasticsearch-plugin install file:///path/to/l1es-plugin-1.0.0.es.8.17.0.zip
```

Or with Docker:

```dockerfile
FROM docker.elastic.co/elasticsearch/elasticsearch:8.17.0
COPY l1es-plugin-1.0.0.es.8.17.0.zip /tmp/l1es-plugin.zip
RUN elasticsearch-plugin install --batch file:///tmp/l1es-plugin.zip
```

### 3. Initialize the Plugin

After Elasticsearch starts:

```bash
curl -X POST 'http://localhost:9200/_l1es/setup'
```

This creates the internal indices (`l1es_dml` for template storage, `l1es_dml_indices` for field mappings). DML (Data Matching Library) is the internal template lookup engine that maps compact events back to their original structure.

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

Tell L1ES which index and field contain compact data:

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
- `source` — the field containing compact events
- `dest` — the field name for expanded output (defaults to `source` if omitted)

### 6. Load Encoded Data

Index your compact log events into the data index as usual:

```bash
curl -X POST 'http://localhost:9200/my-logs/_bulk' \
  -H 'Content-Type: application/x-ndjson' \
  --data-binary @encoded_events.ndjson
```

### 7. Search

Use L1ES query types to search the expanded content:

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

The `fields` parameter is required to trigger the fetch sub-phase that decodes results. The expanded content appears in the `decoded_message` field (or whatever you specified as `dest`).

## Transparent Kibana Support

L1ES can transparently rewrite standard Elasticsearch queries so that Kibana dashboards, saved searches, and KQL queries work against compact data without any changes.

When `query_rewrite_enabled` is `true` (the default), L1ES intercepts incoming search requests and converts standard `match`, `match_phrase`, and `multi_match` queries to their L1ES equivalents. If the target field is not compact, the query falls back to standard Elasticsearch behavior automatically.

When `source_decoding_enabled` is `true` (the default), L1ES decodes compact fields in `_source` for all search responses on registered indices. Kibana document views, Discover, and dashboards display the original log text instead of the encoded `~hash,val1,val2,...` format.

Together, these two features mean:

- **Kibana Discover** — searches match original log content; documents display expanded text
- **Kibana dashboards** — existing visualizations, filters, and saved searches work unchanged
- **KQL queries** — `message: "error" AND message: "database"` matches against expanded content
- **Alerts** — Kibana alerting rules continue to fire on the original log data

No changes are needed to index mappings, index patterns, Kibana saved objects, or any client application.

### Disabling Transparent Rewriting

To use only the explicit `l1es_*` query types (e.g., for programmatic access where you control the query DSL), set in `config/l1es.yml`:

```yaml
flags:
  query_rewrite_enabled: false
  source_decoding_enabled: false
```

### OpenSearch

On OpenSearch, transparent query rewriting uses a search pipeline (`l1es_query_rewrite`) instead of an action filter. The pipeline is automatically created and set as the default search pipeline for the index when you call `_l1es/add-dml-index`. The behavior is otherwise identical.

## Query Types

L1ES provides three query types that mirror standard Elasticsearch queries:

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
| `_l1es/add-dml-index` | POST | Register compact field mapping |
| `_l1es/remove-dml-index` | POST | Unregister compact field mapping |

## Configuration

The plugin configuration is in `config/l1es.yml` inside the plugin directory:

```yaml
flags:
  enabled: true                      # Master switch
  decoding_enabled: true             # Enable fetch sub-phase decoding
  match_query_enabled: true          # Enable l1es_match
  match_pharse_query_enabled: true   # Enable l1es_match_phrase
  multi_match_query_enabled: true    # Enable l1es_multi_match
  query_rewrite_enabled: true        # Transparent rewriting of standard queries for Kibana
  source_decoding_enabled: true      # Decode compact fields in _source responses

encoder:
  hasEncodedLinePrefix: true         # Encoded lines start with a prefix character
  encodedLinePrefix: "~"             # The prefix character
  valueSeperator: ","                # Separator between values in compact lines

dmldb:
  indicesIndexNumberOfShards: 1      # Shards for l1es_dml_indices
  indicesIndexNumberOfReplicas: 1    # Replicas for l1es_dml_indices
  dmlIndexNumberOfShards: 1          # Shards for l1es_dml
  dmlIndexNumberOfReplicas: 1        # Replicas for l1es_dml
  dmlSizeToSearch: 10000             # Max templates to scan per query
```

The `encoder` section must match the format produced by your Log10x encoder. The defaults above match the standard Log10x output format (`~<hash>,<val1>,<val2>,...`).

## Building from Source

### Prerequisites

- Java 17 (JDK)
- Gradle 8.5+

All dependencies (including `log10x-decoder-core`) are resolved automatically from Maven Central.

### Build

```bash
./gradlew build
```

The plugin zip is produced at `build/distributions/l1es-plugin-1.0.0.es.8.17.0.zip`.

### Build with Docker (if your local Java version is incompatible)

```bash
docker run --rm \
  -v "$(pwd)":/project \
  -v "$HOME/.m2":/root/.m2 \
  -w /project \
  eclipse-temurin:17-jdk \
  ./gradlew build
```

### Docker Image

```bash
docker build -t l1es:8.17.0 .
docker run -d --name l1es -p 9200:9200 \
  -e "discovery.type=single-node" \
  -e "xpack.security.enabled=false" \
  l1es:8.17.0
```

## Project Structure

```
├── build.gradle                     # Build configuration
├── gradle.properties                # Version numbers
├── Dockerfile                       # Docker image for ES + plugin
├── src/main/
│   ├── config/l1es.yml              # Plugin configuration
│   ├── resources/
│   │   └── plugin-descriptor.properties
│   └── java/
│       ├── com/log10x/l1es/
│       │   ├── analysis/            # Tokenization utilities
│       │   ├── dml/                 # Template storage and lookup
│       │   ├── fetch/               # Fetch sub-phase (expand results + _source expansion)
│       │   ├── filter/              # ActionFilter for transparent query rewriting
│       │   ├── job/                 # Internal ES operations
│       │   ├── query/               # Custom query types
│       │   │   ├── match/           # Query builders and query implementations
│       │   │   ├── predicate/       # Cross-check predicates (AND, phrase)
│       │   │   └── wrap/            # TwoPhaseIterator wrappers
│       │   └── util/                # ES and JSON utilities
│       └── org/elasticsearch/plugin/log10x/
│           ├── L1esPlugin.java      # Plugin entry point
│           ├── config/              # Configuration classes
│           └── handler/             # REST endpoint handlers
```

## OpenSearch Support

L1ES also supports OpenSearch 2.19.0. The OpenSearch variant is built as a separate Gradle subproject and is functionally identical. See [opensearch/README.md](opensearch/README.md) for OpenSearch-specific instructions.

Build the OpenSearch variant:

```bash
./gradlew :opensearch:build -x test
```

The plugin zip is produced at `opensearch/build/distributions/l1es-plugin-1.0.0.os.2.19.0.zip`.

## Compatibility

| L1ES Version | Elasticsearch | OpenSearch | Java | Lucene |
|-------------|---------------|------------|------|--------|
| 1.0.0 | 8.17.0 | 2.19.0 | 17+ | 9.12.0 |

## Documentation

- [Log10x Documentation](https://doc.log10x.com)
- [Receiver Documentation](https://doc.log10x.com/apps/receiver/)
- [Compact & Expand](https://doc.log10x.com/run/transform/#compact)

## License

This repository is licensed under the [Apache License 2.0](LICENSE).

### Important: Log10x Product License Required

This repository contains an Elasticsearch/OpenSearch plugin for expanding Log10x compact events. While the plugin itself is open source, **using the Log10x Receiver to compact events requires a commercial license**.

| Component | License |
|-----------|---------|
| This repository (Elasticsearch/OpenSearch plugin) | Apache 2.0 (open source) |
| Log10x Receiver | Commercial license required |

**What this means:**
- You can freely use, modify, and distribute this plugin
- The Log10x Receiver that generates compact events requires a paid subscription
- A valid Log10x license is required to run the Receiver

**Get Started:**
- [Log10x Pricing](https://www.log10x.com/pricing?utm_source=github&utm_medium=readme&utm_campaign=elasticsearch-plugin&utm_content=footer)
- [Documentation](https://doc.log10x.com)
- [Contact Sales](mailto:sales@log10x.com)

## Contributing

Contributions are welcome! Please read our contributing guidelines and submit pull requests to the repository.

## Support

For issues and feature requests:
- Open an issue on [GitHub](https://github.com/log-10x/elasticsearch-plugin/issues)
- Contact the Log10x team at [support@log10x.com](mailto:support@log10x.com)
