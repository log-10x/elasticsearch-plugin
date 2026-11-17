# Changelog

## 1.0.2 (2026-11-17)

- Upgrade `log10x-decoder-core` to 1.0.1: a value slot right before a CR decodes, so Windows events that keep the CR of a CR LF expand to their original text
- Upgrade `log10x-decoder-core` to 1.0.1: an epoch timestamp with a fraction expands as written, with its dot and zeros: milliseconds (`1705313472123.456`) and seconds (`1723042099.699`, as in Linux audit records)
- Upgrade `log10x-decoder-core` to 1.0.1: a CR alone inside a multi-line event, and a timestamp right before a CR, expand as written
- Upgrade `log10x-decoder-core` to 1.0.1: a `$` that follows a `/` in the original text (`"http://$($json.host)"`) expands as written
- `match_phrase` finds a phrase that lies partly or wholly inside a value, such as a Windows path or an IPv4 address the engine writes as one value
- A word cut between template text and a value (`index.aspx` in `index.$` with the value `aspx?id`) is found by phrase search
- An event whose template hash ends in a digit and whose first value starts with one is found by its hash
- User guide: map the compact field with `,` as a break between terms; the loader strips only the line break

## 1.0.1 (2026-10-25)

- `GET _l1es` on OpenSearch names OpenSearch and its version

## 1.0.0 (2026-03-20)

- Upgrade `log10x-decoder-core` to 1.0.0

## 0.9.0 (2026-02-25)

Initial public release.

- **Elasticsearch 8.17.0** and **OpenSearch 2.19.0** support
- Custom query types: `l1es_match`, `l1es_match_phrase`, `l1es_multi_match`
- Transparent Kibana support — standard `match`, `match_phrase`, and `multi_match` queries are automatically rewritten to their L1ES equivalents, so Kibana dashboards, saved searches, and KQL queries work against compact data without changes
- Source expansion — compact fields in `_source` are automatically expanded in search responses, so Kibana Discover and document views display the original log text
- Fetch sub-phase expansion with TwoPhaseIterator cross-checking for high-accuracy results
