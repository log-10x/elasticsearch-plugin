# Changelog

## Unreleased

- Docs: replaced the unverified ">50%" savings claim in README.md and opensearch/README.md with one measured on-disk reduction figure per body-size class, sourced from an 18-index measurement matrix on Elasticsearch 8.17.0 with the default LZ4 codec, a single shard, and every index force-merged: around 60 byte bodies 12 percent compact-encode and 47 percent with envelope pruning, 200 to 500 byte bodies 46 percent and 65 percent, around 1.1 KB bodies 60 percent and 73 percent. No percentage ranges are stated.
- Docs: added a new "Where the savings come from" section to both READMEs covering the two-mechanism breakdown (compact encode plus engine-side envelope pruning), the INNER encode-mode requirement with its structural reason, and a sample `drop:` list applied centrally in `config/modules/pipelines/run/modules/initialize/k8s/settings.yaml`.
- Docs: added an "Encode mode (INNER required)" sub-section to both Configuration sections, documenting why OUTER mode breaks Kibana aggregations through the plugin's fetch sub-phase.
- Docs: USER-GUIDE.md troubleshooting now lists OUTER-mode misconfiguration as a cause of zero-hit queries.
- Docs: the pre-encode side of the savings matrix is labeled modeled, not measured; raw bodies were synthesized at 2.5x the inner-body length.

## 1.0.0 (2026-03-20)

- Upgrade `log10x-decoder-core` to 1.0.0

## 0.9.0 (2026-02-25)

Initial public release.

- **Elasticsearch 8.17.0** and **OpenSearch 2.19.0** support
- Custom query types: `l1es_match`, `l1es_match_phrase`, `l1es_multi_match`
- Transparent Kibana support — standard `match`, `match_phrase`, and `multi_match` queries are automatically rewritten to their L1ES equivalents, so Kibana dashboards, saved searches, and KQL queries work against compact data without changes
- Source expansion — compact fields in `_source` are automatically expanded in search responses, so Kibana Discover and document views display the original log text
- Fetch sub-phase expansion with TwoPhaseIterator cross-checking for high-accuracy results
