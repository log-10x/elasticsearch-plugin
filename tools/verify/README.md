# L1ES version verifier

Check that a built L1ES plugin zip actually works against a given Elasticsearch
or OpenSearch version. Self-contained: you need **Docker + a plugin zip**, nothing
else (no repo, no gradle, no JDK, nothing hosted).

The two plugin zips are `l1es-plugin-1.0.0.es.8.17.0.zip` and
`l1es-plugin-1.0.0.os.2.19.0.zip`, attached to the
[v1.0.0 release](https://github.com/log-10x/elasticsearch-plugin/releases/tag/v1.0.0).
`./gradlew build` at the repository root writes the same zips to
`build/distributions/` and `opensearch/build/distributions/`. Each zip installs
only on the engine version in its name.

## Run one version

```bash
./verify-l1es.sh es 8.17.0 ./l1es-plugin-1.0.0.es.8.17.0.zip
./verify-l1es.sh os 2.19.0 ./l1es-plugin-1.0.0.os.2.19.0.zip
```

## Sweep several versions

One zip per version, auto-matched from a directory by filename (`*.<es|os>.<ver>.zip`).
With both release zips in `./dist`:

```bash
./verify-l1es.sh es --versions "8.17.0" --zipdir ./dist
./verify-l1es.sh os --versions "2.19.0" --zipdir ./dist
```

A version with no matching zip in the directory fails as `FAIL (no zip)`.

Prints a per-version PASS/FAIL summary at the end and exits non-zero if any failed.

It boots each engine in a throwaway container, installs the plugin, runs the
checks below, prints PASS/FAIL, and tears everything down. Exit code is 0 only
if every version passed.

Flags: `--keep` (single run only; leave the node up to poke at), `--no-decode`
(skip the roundtrip), `--fixtures DIR` (point at a different fixture set).

## What it checks — and what a failure means

| Check | What it proves | A failure means |
|-------|----------------|-----------------|
| install | the plugin's classes load on this engine | an internal API the plugin uses was removed/changed on this version (e.g. the Lucene 10 scorer change), or the zip targets a different version |
| startup | the node stays up with the plugin on | a load-time break (e.g. the Elasticsearch 9 entitlements change) |
| registered | `_cat/plugins` lists it | the plugin didn't register |
| endpoint | `GET /_l1es` answers | the REST/action wiring didn't load |
| search | a plain match query runs | the query path crashes |
| **decode** | **the real compact/expand roundtrip works** | the plugin's actual job is broken on this version |

The **decode** check is the important one. It uses genuine Log10x sample data
(`fixtures/`): it loads real templates, indexes real `~hash,value,...` encoded
events, searches by the *expanded* phrase, and confirms the response contains the
expanded original JSON (including tokens like `stdout` that only exist after
decoding). So a green run means the plugin genuinely expands compacted data on
that engine version, not just that it loads.

## Fixtures

`fixtures/` is carved from the real 200MB OpenTelemetry sample (6 template/event
pairs, ~10KB).

- `dml-bulk.ndjson` — templates, ready to POST to `l1es_dml/_bulk`
- `docs-bulk.ndjson` — encoded events, ready to POST to the target index
- `meta.env` — index name, query phrase, and the decode-proof token

## Using it as an early-warning check

Run it against a new engine release the day it ships (`./verify-l1es.sh es 9.2.0 …`).
If `install` or `decode` fails, that release broke an API the plugin depends on,
and you'll see exactly which. Drop the same call into a scheduled GitHub Action
if you ever want it automated, but nothing here requires that.
