# Changelog

## 0.9.0 (2026-02-25)

Initial public release.

- **Elasticsearch 8.17.0** and **OpenSearch 2.19.0** support
- Custom query types: `l1es_match`, `l1es_match_phrase`, `l1es_multi_match`
- Transparent Kibana support — standard `match`, `match_phrase`, and `multi_match` queries are automatically rewritten to their L1ES equivalents, so Kibana dashboards, saved searches, and KQL queries work against encoded data without changes
- Source decoding — encoded fields in `_source` are automatically decoded in search responses, so Kibana Discover and document views display the original log text
- Fetch sub-phase decoding with TwoPhaseIterator cross-checking for high-accuracy results
