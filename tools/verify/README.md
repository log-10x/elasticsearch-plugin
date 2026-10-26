# L1ES version verifier

Check that a built L1ES plugin zip actually works against a given Elasticsearch
or OpenSearch version. Self-contained: you need **Docker, curl and a plugin zip**,
nothing else (no repo, no Gradle, no JDK).

The two plugin zips are `l1es-plugin-1.0.1.es.8.17.0.zip` and
`l1es-plugin-1.0.1.os.2.19.0.zip`, attached to the
[v1.0.1 release](https://github.com/log-10x/elasticsearch-plugin/releases/tag/v1.0.1).
`./gradlew build` at the repository root writes the same zips to
`build/distributions/` and `opensearch/build/distributions/`. Each zip installs
only on the engine version in its name.

## Run one version

```bash
./verify-l1es.sh es 8.17.0 ./l1es-plugin-1.0.1.es.8.17.0.zip
./verify-l1es.sh os 2.19.0 ./l1es-plugin-1.0.1.os.2.19.0.zip
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
(skip the roundtrip), `--fixtures DIR` (point at a different fixture set),
`--port N` (host port for the node, default 9299; the run stops if something
already answers on that port).

## What it checks and what a failure means

| Check | What it proves | A failure means |
|-------|----------------|-----------------|
| install | the plugin's classes load on this engine | an internal API the plugin uses was removed or changed on this version, or the zip targets a different version |
| startup | the node stays up with the plugin on | a load-time break |
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

`fixtures/` holds 6 template and event pairs (about 10 KB) taken from the 200 MB
OpenTelemetry sample.

- `dml-bulk.ndjson`: templates, ready to POST to `l1es_dml/_bulk`
- `docs-bulk.ndjson`: encoded events, ready to POST to the target index
- `meta.env`: index name, query phrase, and the decode-proof token
