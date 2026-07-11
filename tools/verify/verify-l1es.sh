#!/usr/bin/env bash
#
# verify-l1es.sh — check that a built L1ES plugin zip works against one or more
#                  Elasticsearch / OpenSearch versions.
#
# No repo, no gradle, no JDK, nothing hosted. You need: Docker + a plugin zip.
# For each version it boots that exact engine in a throwaway container, installs
# the plugin, runs the checks below, prints PASS/FAIL, and tears everything down.
#
# SINGLE VERSION:
#   ./verify-l1es.sh es 8.17.0 ./l1es-plugin-1.0.0.es.8.17.0.zip
#   ./verify-l1es.sh os 2.19.0 ./l1es-plugin-1.0.0.os.2.19.0.zip
#
# SWEEP (one zip per version, auto-matched from a directory by name *.<es|os>.<ver>.zip):
#   ./verify-l1es.sh es --versions "8.17.0 8.19.0 9.0.0" --zipdir ./dist
#
# FLAGS: --keep (single only; leave node up)  --no-decode (skip roundtrip)
#        --fixtures DIR (default: <script>/fixtures)
#
# EXIT: 0 = every version passed, non-zero = at least one check failed.
#
# CHECKS per version (a failure names what broke):
#   install     plugin classes load on this engine (a removed internal API — e.g.
#               the Lucene 10 scorer change — or a descriptor version mismatch)
#   startup     node stays up with the plugin (e.g. the ES 9 entitlements change)
#   registered  _cat/plugins lists l1es-plugin
#   endpoint    GET /_l1es answers (REST + action wiring)
#   search      a plain match query runs (query path intact)
#   decode      REAL compact/expand roundtrip on genuine Log10x sample data:
#               load templates, index ~hash,val events, search by the EXPANDED
#               phrase, confirm the response contains the expanded original.
set -uo pipefail

SELF_DIR="$(cd "$(dirname "$0")" && pwd)"
FIXDIR="${SELF_DIR}/fixtures"
KEEP=""; DECODE=1; ZIPDIR=""; VERSIONS=(); POS=()

PRODUCT="${1:-}"; shift 1 2>/dev/null || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --versions) shift; read -r -a VERSIONS <<< "${1:-}" ;;
    --zipdir)   shift; ZIPDIR="${1:-}" ;;
    --fixtures) shift; FIXDIR="${1:-}" ;;
    --keep)     KEEP=1 ;;
    --no-decode) DECODE=0 ;;
    --*) echo "!! unknown flag: $1"; exit 2 ;;
    *) POS+=("$1") ;;
  esac
  shift
done

# print only the leading header comment block (stop at the first non-comment line)
usage() { awk 'NR>1 && /^[^#]/{exit} NR>1{sub(/^# ?/,""); print}' "$0"; }

case "$PRODUCT" in
  es) IMAGE_BASE="docker.elastic.co/elasticsearch/elasticsearch"; DESC_KEY="elasticsearch.version"; PLUGIN_CLI="elasticsearch-plugin" ;;
  os) IMAGE_BASE="opensearchproject/opensearch";                  DESC_KEY="opensearch.version";    PLUGIN_CLI="opensearch-plugin" ;;
  *)  usage; exit 2 ;;
esac
command -v docker >/dev/null || { echo "!! docker is required"; exit 2; }
command -v curl   >/dev/null || { echo "!! curl is required"; exit 2; }

ES=http://localhost:9200
CT_JSON='-H Content-Type:application/json'
CT_NDJSON='-H Content-Type:application/x-ndjson'

# Backstop: remove any stray verify containers/images on exit.
cleanup_all() { docker ps -aq --filter "name=l1es-verify-" | xargs -r docker rm -f >/dev/null 2>&1 || true; }
trap cleanup_all EXIT

# verify_one <version> <zip> <allow_keep> -> prints checks, returns 0 (pass) / 1 (fail)
verify_one() {
  local VERSION="$1" ZIP="$2" ALLOW_KEEP="$3"
  local IMAGE="${IMAGE_BASE}:${VERSION}"
  local TAG="l1es-verify-${PRODUCT}-${VERSION//./-}:local"
  local NAME="l1es-verify-${PRODUCT}-${VERSION//./-}-$$"
  local CTX; CTX="$(mktemp -d)"
  local P=0 F=0
  # pass/fail are (re)defined each call; dynamic scope lets them update this call's P/F.
  pass() { echo "  PASS  $1"; P=$((P+1)); }
  fail() { echo "  FAIL  $1"; F=$((F+1)); }
  local keep_this=""; [[ -n "$KEEP" && "$ALLOW_KEEP" == "1" ]] && keep_this=1

  local_cleanup() {
    if [[ -n "$keep_this" ]]; then
      echo ">> --keep: node left running as '$NAME' on ${ES}"
    else
      docker rm -f "$NAME" >/dev/null 2>&1 || true
      docker rmi "$TAG"    >/dev/null 2>&1 || true
    fi
    rm -rf "$CTX"
  }

  echo "== L1ES verify: $PRODUCT $VERSION =="
  if [[ ! -f "$ZIP" ]]; then fail "no plugin zip: $ZIP"; local_cleanup; return 1; fi

  # preflight: descriptor version sanity
  if command -v unzip >/dev/null; then
    local dv; dv="$(unzip -p "$ZIP" plugin-descriptor.properties 2>/dev/null | grep "^${DESC_KEY}=" | cut -d= -f2 | tr -d '\r')"
    if [[ -n "$dv" && "$dv" != "$VERSION" ]]; then
      echo "!! WARNING: zip descriptor ${DESC_KEY}=${dv}, testing ${VERSION} — install will fail on mismatch."
    fi
  fi

  # CHECK 1: install (image build time = deterministic)
  cp "$ZIP" "$CTX/plugin.zip"
  printf 'FROM %s\nCOPY plugin.zip /tmp/plugin.zip\nRUN bin/%s install --batch file:///tmp/plugin.zip\n' \
    "$IMAGE" "$PLUGIN_CLI" > "$CTX/Dockerfile"
  echo "-- installing plugin into ${IMAGE} ..."
  if docker build -t "$TAG" "$CTX" > "$CTX/build.log" 2>&1; then
    pass "install (plugin loaded its classes on $PRODUCT $VERSION)"
  else
    fail "install — plugin will not load on $PRODUCT $VERSION"
    echo "   ---- install error (last 20 lines) ----"; tail -20 "$CTX/build.log" | sed 's/^/   /'
    local_cleanup; echo "-- $PRODUCT $VERSION: $P passed, $F failed"; return 1
  fi

  # CHECK 2: startup
  local ENVS
  if [[ "$PRODUCT" == "es" ]]; then
    ENVS=(-e discovery.type=single-node -e xpack.security.enabled=false \
          -e xpack.security.http.ssl.enabled=false -e xpack.security.transport.ssl.enabled=false \
          -e "ES_JAVA_OPTS=-Xms512m -Xmx512m")
  else
    ENVS=(-e discovery.type=single-node -e DISABLE_SECURITY_PLUGIN=true \
          -e "OPENSEARCH_INITIAL_ADMIN_PASSWORD=Str0ng-Passw0rd!" \
          -e "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m")
  fi
  docker run -d --name "$NAME" -p 9200:9200 "${ENVS[@]}" "$TAG" >/dev/null
  echo "-- waiting for node to become healthy ..."
  local up=""
  for _ in $(seq 1 60); do
    curl -fsS "${ES}/_cluster/health" >/dev/null 2>&1 && { up=1; break; }
    docker ps --format '{{.Names}}' | grep -q "$NAME" || break
    sleep 3
  done
  if [[ -n "$up" ]]; then
    pass "startup (node healthy with plugin installed)"
  else
    fail "startup — node did not come up (load-time failure)"
    echo "   ---- node log (last 25 lines) ----"; docker logs "$NAME" 2>&1 | tail -25 | sed 's/^/   /'
    local_cleanup; echo "-- $PRODUCT $VERSION: $P passed, $F failed"; return 1
  fi

  # CHECK 3: registered
  curl -fsS "${ES}/_cat/plugins" | grep -q "l1es-plugin" \
    && pass "registered (_cat/plugins lists l1es-plugin)" || fail "plugin not listed"

  # CHECK 4: endpoint
  curl -fsS -o /dev/null -w '%{http_code}' "${ES}/_l1es" | grep -qE '^20[01]$' \
    && pass "endpoint (GET /_l1es responds)" || fail "GET /_l1es no 2xx"

  # CHECK 5: plain search
  curl -fsS -XPUT  "${ES}/l1es_plain" $CT_JSON -d '{"mappings":{"properties":{"message":{"type":"text"}}}}' >/dev/null 2>&1
  curl -fsS -XPOST "${ES}/l1es_plain/_doc?refresh=true" $CT_JSON -d '{"message":"hello l1es verify"}' >/dev/null 2>&1
  curl -fsS "${ES}/l1es_plain/_search" $CT_JSON -d '{"query":{"match":{"message":"verify"}}}' | grep -qE '"value":1[,}]' \
    && pass "search (plain match query returned the doc)" || fail "plain search unexpected"

  # CHECK 6: real compact/expand roundtrip
  if [[ "$DECODE" == "0" ]]; then
    echo "  SKIP  decode roundtrip (--no-decode)"
  elif [[ ! -f "$FIXDIR/dml-bulk.ndjson" || ! -f "$FIXDIR/docs-bulk.ndjson" || ! -f "$FIXDIR/meta.env" ]]; then
    echo "  SKIP  decode roundtrip (no fixtures at $FIXDIR)"
  else
    # shellcheck disable=SC1090,SC1091
    source "$FIXDIR/meta.env"   # FIX_INDEX, FIX_QUERY, FIX_PROOF
    local ok=1 resp
    curl -fsS -XPOST "${ES}/_l1es/setup" >/dev/null 2>&1 || ok=0
    curl -fsS -XPUT  "${ES}/${FIX_INDEX}" $CT_JSON -d '{"mappings":{"properties":{"message":{"type":"text"}}}}' >/dev/null 2>&1
    curl -fsS -XPOST "${ES}/_l1es/add-dml-index" $CT_JSON -d "{\"index_name\":\"${FIX_INDEX}\",\"source\":\"message\"}" >/dev/null 2>&1 || ok=0
    curl -fsS -XPOST "${ES}/l1es_dml/_bulk?refresh=true"      $CT_NDJSON --data-binary "@${FIXDIR}/dml-bulk.ndjson"  | grep -q '"errors":false' || ok=0
    curl -fsS -XPOST "${ES}/${FIX_INDEX}/_bulk?refresh=true"  $CT_NDJSON --data-binary "@${FIXDIR}/docs-bulk.ndjson" | grep -q '"errors":false' || ok=0
    resp="$(curl -fsS "${ES}/${FIX_INDEX}/_search" $CT_JSON \
      -d "{\"query\":{\"l1es_match_phrase\":{\"message\":{\"query\":\"${FIX_QUERY}\"}}},\"fields\":[\"message\"],\"size\":5}" 2>/dev/null)"
    if [[ "$ok" == "1" ]] && grep -q "$FIX_QUERY" <<<"$resp" && grep -q "$FIX_PROOF" <<<"$resp"; then
      pass "decode (searched by expanded phrase; response contains expanded original)"
    else
      fail "decode roundtrip did not expand encoded events (compact/expand broken)"
      echo "   ---- search response (first 400 chars) ----"; echo "${resp:0:400}" | sed 's/^/   /'
    fi
  fi

  local_cleanup
  echo "-- $PRODUCT $VERSION: $P passed, $F failed"
  [[ $F -eq 0 ]] && return 0 || return 1
}

# ---- dispatch: single vs sweep ----
declare -a RESULTS
overall=0

if [[ ${#VERSIONS[@]} -eq 0 ]]; then
  # single
  VER="${POS[0]:-}"; ZIP="${POS[1]:-}"
  [[ -n "$VER" && -n "$ZIP" ]] || { usage; exit 2; }
  verify_one "$VER" "$ZIP" 1 || overall=1
  [[ $overall -eq 0 ]] && echo "RESULT: PASS — L1ES works on $PRODUCT $VER" \
                       || echo "RESULT: FAIL — see above"
else
  # sweep
  [[ -n "$ZIPDIR" ]] || { echo "!! --versions requires --zipdir DIR (one zip per version)"; exit 2; }
  for VER in "${VERSIONS[@]}"; do
    echo
    ZIP="$(ls "$ZIPDIR"/*."$PRODUCT"."$VER".zip 2>/dev/null | head -1)"
    if [[ -z "$ZIP" ]]; then
      echo "== L1ES verify: $PRODUCT $VER =="
      echo "  FAIL  no zip matching *.${PRODUCT}.${VER}.zip in ${ZIPDIR}"
      RESULTS+=("$PRODUCT $VER  FAIL (no zip)"); overall=1; continue
    fi
    if verify_one "$VER" "$ZIP" 0; then RESULTS+=("$PRODUCT $VER  PASS")
    else RESULTS+=("$PRODUCT $VER  FAIL"); overall=1; fi
  done
  echo; echo "== sweep summary =="
  for r in "${RESULTS[@]}"; do echo "  $r"; done
fi

exit $overall
