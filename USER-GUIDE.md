# L1ES User Guide

This guide covers the full workflow for using L1ES to search Log10x-compacted data in Elasticsearch 8.17.0.

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Installation](#installation)
3. [Setup](#setup)
4. [Loading Templates](#loading-templates)
5. [Registering Encoded Fields](#registering-encoded-fields)
6. [Loading Encoded Data](#loading-encoded-data)
7. [Searching](#searching)
8. [Query Reference](#query-reference)
9. [Viewing Decoded Results](#viewing-decoded-results)
10. [Configuration Reference](#configuration-reference)
11. [Troubleshooting](#troubleshooting)

---

## Prerequisites

- Elasticsearch 8.17.0
- Log10x encoder output:
  - `templates.json` — one JSON object per line: `{"templateHash":"<hash>","template":"<pattern>"}`
  - `encoded.log` — one encoded event per line: `~<hash>,<value1>,<value2>,...`
  - The original log file (for verification)

## Installation

### Standard Installation

```bash
bin/elasticsearch-plugin install file:///path/to/l1es-plugin-1.0.0.es.8.17.0.zip
```

Restart Elasticsearch after installation.

### Docker Installation

```dockerfile
FROM docker.elastic.co/elasticsearch/elasticsearch:8.17.0
COPY l1es-plugin-1.0.0.es.8.17.0.zip /tmp/l1es-plugin.zip
RUN elasticsearch-plugin install --batch file:///tmp/l1es-plugin.zip
```

### Verify Installation

```bash
curl -s 'http://localhost:9200/_l1es' | python3 -m json.tool
```

Expected response:

```json
{
  "name": "l1es-plugin",
  "description": "L1x support for decoding inside elasticsearch",
  "l1es_version": "1.0.0",
  "elasticsearch_version": "8.17.0"
}
```

## Setup

Initialize the plugin's internal indices:

```bash
curl -X POST 'http://localhost:9200/_l1es/setup'
```

This creates two internal indices:

| Index | Purpose |
|-------|---------|
| `l1es_dml` | Stores template patterns (hash → pattern mapping) |
| `l1es_dml_indices` | Stores field mappings (which index/field uses L1ES decoding) |

Verify the indices were created:

```bash
curl -s 'http://localhost:9200/_cat/indices/l1es_*?v'
```

## Loading Templates

Templates must be loaded into the `l1es_dml` index before encoded data can be searched or decoded. Each template is stored as a document with the template hash as `_id` and the pattern as the `pattern` field.

### Using Python (recommended for large template files)

```python
#!/usr/bin/env python3
"""Load templates from templates.json into l1es_dml."""
import json
import urllib.request

TEMPLATES_FILE = "templates.json"
ES_URL = "http://localhost:9200"
BATCH_SIZE = 500

lines = []
with open(TEMPLATES_FILE, 'r') as f:
    for line in f:
        line = line.strip()
        if line:
            lines.append(line)

print(f"Loading {len(lines)} templates...")

for batch_start in range(0, len(lines), BATCH_SIZE):
    batch = lines[batch_start:batch_start + BATCH_SIZE]
    bulk_body = ""

    for raw_line in batch:
        obj = json.loads(raw_line)
        action = json.dumps({"index": {"_index": "l1es_dml", "_id": obj["templateHash"]}})
        doc = json.dumps({"pattern": obj["template"]})
        bulk_body += action + "\n" + doc + "\n"

    req = urllib.request.Request(
        f"{ES_URL}/_bulk",
        data=bulk_body.encode('utf-8'),
        headers={"Content-Type": "application/x-ndjson"},
        method="POST"
    )
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read())
        errors = sum(1 for item in result['items'] if 'error' in item.get('index', {}))
        print(f"  Batch {batch_start // BATCH_SIZE + 1}: {len(batch) - errors} ok, {errors} errors")

print("Done.")
```

### Verify Templates

```bash
curl -s 'http://localhost:9200/l1es_dml/_count' | python3 -m json.tool
```

## Registering Encoded Fields

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

| Field | Required | Description |
|-------|----------|-------------|
| `index_name` | Yes | The Elasticsearch index containing encoded data |
| `source` | Yes | The field name that holds encoded events |
| `dest` | No | The output field name for decoded results (defaults to `source`) |

### Remove a Field Mapping

```bash
curl -X POST 'http://localhost:9200/_l1es/remove-dml-index' \
  -H 'Content-Type: application/json' \
  -d '{
    "index_name": "my-logs"
  }'
```

## Loading Encoded Data

Index your encoded events as standard Elasticsearch documents. The encoded text goes into the `source` field you registered above.

### Using Python

```python
#!/usr/bin/env python3
"""Load encoded events into Elasticsearch."""
import json
import urllib.request

ENCODED_FILE = "encoded.log"
ES_URL = "http://localhost:9200"
INDEX = "my-logs"
BATCH_SIZE = 1000

lines = []
with open(ENCODED_FILE, 'r') as f:
    for i, line in enumerate(f):
        line = line.strip()
        if line:
            lines.append(line)

print(f"Loading {len(lines)} encoded events...")

for batch_start in range(0, len(lines), BATCH_SIZE):
    batch = lines[batch_start:batch_start + BATCH_SIZE]
    bulk_body = ""

    for i, encoded_line in enumerate(batch):
        doc_id = batch_start + i
        action = json.dumps({"index": {"_index": INDEX, "_id": str(doc_id)}})
        doc = json.dumps({"message": encoded_line})
        bulk_body += action + "\n" + doc + "\n"

    req = urllib.request.Request(
        f"{ES_URL}/_bulk",
        data=bulk_body.encode('utf-8'),
        headers={"Content-Type": "application/x-ndjson"},
        method="POST"
    )
    with urllib.request.urlopen(req) as resp:
        result = json.loads(resp.read())
        errors = sum(1 for item in result['items'] if 'error' in item.get('index', {}))
        print(f"  Batch {batch_start // BATCH_SIZE + 1}: {len(batch) - errors} ok, {errors} errors")

# Refresh to make searchable
urllib.request.urlopen(urllib.request.Request(f"{ES_URL}/{INDEX}/_refresh", method="POST"))
print("Done. Index refreshed.")
```

## Searching

### Basic Phrase Search

```bash
curl -X POST 'http://localhost:9200/my-logs/_search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "l1es_match_phrase": {
        "message": {
          "query": "service started"
        }
      }
    },
    "fields": ["message"],
    "size": 10
  }'
```

**Important:** The `fields` parameter must include the source field to trigger the fetch sub-phase that decodes results. Without it, you get hit counts but no decoded content.

### Token Search with AND Operator

```bash
curl -X POST 'http://localhost:9200/my-logs/_search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "l1es_match": {
        "message": {
          "query": "error timeout database",
          "operator": "AND"
        }
      }
    },
    "fields": ["message"],
    "size": 10
  }'
```

### Multi-Field Search

```bash
curl -X POST 'http://localhost:9200/my-logs/_search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "l1es_multi_match": {
        "query": "connection refused",
        "fields": ["message", "error_field"],
        "operator": "AND"
      }
    },
    "fields": ["message"],
    "size": 10
  }'
```

### Count Only (no decoded results needed)

```bash
curl -X POST 'http://localhost:9200/my-logs/_search' \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "l1es_match_phrase": {
        "message": {
          "query": "error in processing"
        }
      }
    },
    "size": 0
  }'
```

## Query Reference

### l1es_match

Splits the query text into tokens and searches for events whose decoded content contains those tokens.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `query` | (required) | The search text |
| `operator` | `OR` | `AND` requires all tokens; `OR` requires any token |
| `analyzer` | `null` | Override the default analyzer |
| `fuzziness` | `null` | Fuzziness for approximate matching (`AUTO`, `0`, `1`, `2`) |
| `prefix_length` | `0` | Characters that must match exactly at the start of each term |
| `max_expansions` | `50` | Max number of terms fuzzy matching produces |
| `minimum_should_match` | `null` | Min number or percentage of tokens that must match |
| `lenient` | `false` | Ignore format-based errors |
| `zero_terms_query` | `none` | Behavior when analyzer removes all tokens: `none` or `all` |

For multi-token AND queries (2+ tokens), L1ES uses TwoPhaseIterator cross-checking to verify decoded content.

### l1es_match_phrase

Searches for the exact phrase (tokens in order) in decoded content.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `query` | (required) | The exact phrase to search for |
| `analyzer` | `null` | Override the default analyzer |
| `slop` | `0` | Number of positions tokens can be apart and still match |
| `zero_terms_query` | `none` | Behavior when analyzer removes all tokens |

### l1es_multi_match

Searches across multiple fields.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `query` | (required) | The search text |
| `fields` | `[]` | List of fields to search (supports boost: `"field^2.0"`) |
| `type` | `best_fields` | `best_fields`, `most_fields`, `phrase`, `phrase_prefix` |
| `operator` | `OR` | `AND` or `OR` |
| All l1es_match parameters | | Same fuzzy/analyzer options as l1es_match |

## Viewing Decoded Results

When you include `"fields": ["<source_field>"]` in your search request, each hit includes a `fields` object with the decoded content:

```json
{
  "hits": {
    "hits": [
      {
        "_id": "42",
        "_source": {
          "message": "~abc123,val1,val2,val3"
        },
        "fields": {
          "decoded_message": [
            "{\"stream\":\"stdout\",\"log\":\"Service started successfully\",...}"
          ]
        }
      }
    ]
  }
}
```

- `_source.message` — the raw encoded event (as stored)
- `fields.decoded_message` — the decoded original event (expanded by L1ES)

The decoded field name (`decoded_message` in this example) is what you set as `dest` when registering the field mapping.

## Configuration Reference

The plugin configuration file is at `<es-home>/plugins/l1es-plugin/config/l1es.yml`.

### Flags

```yaml
flags:
  enabled: true                      # Master switch — disables all L1ES functionality
  decoding_enabled: true             # Enable the fetch sub-phase that decodes results
  match_query_enabled: true          # Enable l1es_match query type
  match_pharse_query_enabled: true   # Enable l1es_match_phrase query type
  multi_match_query_enabled: true    # Enable l1es_multi_match query type
```

### Encoder Settings

These must match the format produced by your Log10x encoder:

```yaml
encoder:
  hasEncodedLinePrefix: true         # true if encoded lines start with a prefix char
  encodedLinePrefix: "~"             # The prefix character (standard: ~)
  valueSeperator: ","                # Separator between variable values (standard: ,)
```

The defaults match the standard Log10x output format: `~<template-hash>,<val1>,<val2>,...`

### DML Database Settings

```yaml
dmldb:
  indicesIndexNumberOfShards: 1      # Shards for l1es_dml_indices
  indicesIndexNumberOfReplicas: 1    # Replicas for l1es_dml_indices
  dmlIndexNumberOfShards: 1          # Shards for l1es_dml (template storage)
  dmlIndexNumberOfReplicas: 1        # Replicas for l1es_dml
  dmlSizeToSearch: 10000             # Max templates to search per query
```

For large template sets (>10,000), increase `dmlSizeToSearch` to avoid missing matches. This controls the Elasticsearch `size` parameter when querying the template index.

## Troubleshooting

### No decoded_message field in results

1. Make sure `"fields": ["<source_field>"]` is included in your search request.
2. Verify the field mapping is registered: `curl 'http://localhost:9200/l1es_dml_indices/_search?pretty'`
3. Check that `decoding_enabled: true` in `l1es.yml`.
4. Verify templates are loaded: `curl 'http://localhost:9200/l1es_dml/_count'`

### L1ES queries return 0 hits for text that should exist

1. Verify templates are loaded and match your encoded data format.
2. Check `l1es.yml` encoder settings match your data format (prefix char, separator).
3. Ensure the encoded events are indexed in the registered index with the registered field name.
4. Try a broader search: `l1es_match` with `operator: OR` instead of `l1es_match_phrase`.

### Decoded output has wrong dates or timestamps

This is a known issue in the log10x-decoder-core library related to timezone handling in date template patterns. The decoded content is structurally correct but timestamps may show timezone offsets. This does not affect search accuracy.

### Too many results for common terms

Single-token queries (one word) bypass the per-event TwoPhaseIterator verification for performance. They match at the template level, which can return events from all templates containing that token. Use multi-token queries or phrase queries for more precise results.

### Plugin setup fails

Check that Elasticsearch has finished initializing before calling `_l1es/setup`. The plugin needs the cluster to be in a ready state to create indices.
