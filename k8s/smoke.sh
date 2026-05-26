#!/usr/bin/env bash
# Run the comparison smoke against whichever configuration is up.
# Replays time-to-green (and time-to-first-publish, for rcs-etcd)
# annotations captured during bring-up, measures time-to-first-
# index-ack, runs the geonames benchmark in test-mode, and dumps
# the resulting etcd keyspace count.

set -euo pipefail

NAMESPACE=rcs-etcd-poc

# Resolve config: explicit arg wins, otherwise read the label.
config="${1:-}"
if [ -z "$config" ]; then
  config=$(kubectl get configmap opensearch-config -n "$NAMESPACE" \
    -o jsonpath='{.metadata.labels.config}' 2>/dev/null || true)
fi
case "$config" in
  vanilla|rcs-etcd) ;;
  *) echo "no active config detected; pass vanilla|rcs-etcd as arg" >&2; exit 1 ;;
esac

echo "==> smoke against $config"

# Replay bring-up annotations.
ann() {
  kubectl get configmap opensearch-config -n "$NAMESPACE" \
    -o jsonpath="{.metadata.annotations.testbed\.rcs-etcd/$1}" 2>/dev/null || true
}
green=$(ann time-to-green-seconds)
[ -n "$green" ] && echo "METRIC time_to_green_seconds=$green"
publish=$(ann time-to-first-publish-seconds)
[ -n "$publish" ] && echo "METRIC time_to_first_publish_seconds=$publish"

# Probe REST reachability.
if ! curl -sf "http://localhost:9200/_cluster/health" >/dev/null; then
  echo "OpenSearch not reachable on localhost:9200 — is 'make up-$config' green?" >&2
  exit 1
fi

# Clean any leftover index from a previous smoke.
curl -sf -X DELETE "http://localhost:9200/smoke-idx" >/dev/null 2>&1 || true

echo "==> creating smoke index"
t0=$(date +%s%N)
curl -sf -X PUT "http://localhost:9200/smoke-idx" \
     -H 'Content-Type: application/json' \
     -d '{"settings":{"number_of_shards":1,"number_of_replicas":1}}' >/dev/null
t1=$(date +%s%N)
ack_ms=$(( (t1 - t0) / 1000000 ))
echo "METRIC time_to_first_index_ack_ms=$ack_ms"

# Resolve the benchmark CLI. `make benchmark-setup` is the canonical
# installer (user-local via the sibling opensearch-benchmark checkout);
# this lookup also accepts a system pip install on PATH. The workloads
# checkout is expected next to this repo at ../../opensearch-benchmark-
# workloads/ — override either path with OSB_REPO / OSB_WORKLOADS.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SIBLINGS="$(cd "$SCRIPT_DIR/../.." && pwd)"
OSB_WORKLOADS="${OSB_WORKLOADS:-$SIBLINGS/opensearch-benchmark-workloads}"

if command -v opensearch-benchmark >/dev/null 2>&1; then
  OSB_CMD=(opensearch-benchmark)
elif [ -x "$HOME/.local/bin/opensearch-benchmark" ]; then
  OSB_CMD=("$HOME/.local/bin/opensearch-benchmark")
else
  OSB_CMD=()
fi

if [ ${#OSB_CMD[@]} -gt 0 ] && [ -d "$OSB_WORKLOADS/geonames" ]; then
  echo "==> running geonames --test-mode"
  "${OSB_CMD[@]}" run \
    --workload-path="$OSB_WORKLOADS/geonames" \
    --test-mode \
    --target-hosts="http://localhost:9200" \
    --pipeline=benchmark-only \
    --client-options='use_ssl:false,verify_certs:false' \
    --results-format=markdown 2>&1 | tail -40
else
  if [ ${#OSB_CMD[@]} -eq 0 ]; then
    echo "WARN: opensearch-benchmark not found — run 'make benchmark-setup' first."
  else
    echo "WARN: geonames workload missing at $OSB_WORKLOADS/geonames — skipping benchmark."
  fi
fi

# Cleanup the smoke index so successive runs start from the same state.
curl -sf -X DELETE "http://localhost:9200/smoke-idx" >/dev/null 2>&1 || true

if [ "$config" = "rcs-etcd" ]; then
  keys=$(kubectl exec -n "$NAMESPACE" etcd-0 -- \
    etcdctl get /opensearch-rcs/ --prefix --keys-only 2>/dev/null \
    | grep -c . || true)
  echo "METRIC etcd_keys=$keys"
fi

# --- Prometheus snapshot ----------------------------------------------------
# Sleep briefly to catch the OTel publish that lands after the benchmark
# completes, then pull the cumulative counters the dashboards display. If
# Prometheus is unreachable (otel-stack not running) the values fall back
# to "n/a" and the summary still renders.
echo
echo "==> snapshotting Prometheus counters"
sleep 12

prom_value() {
  curl -sf "http://localhost:9090/api/v1/query?query=$1" 2>/dev/null \
    | python3 -c '
import sys, json
try:
  r = json.load(sys.stdin)
  result = r["data"]["result"]
  print(result[0]["value"][1] if result else "0")
except Exception:
  print("n/a")
' 2>/dev/null || echo "n/a"
}

otlp_exported=$(prom_value otlp_exporter_exported_total)
async_success=$(prom_value async_fetch_success_count_total)
async_failure=$(prom_value async_fetch_failure_count_total)
if [ "$config" = "rcs-etcd" ]; then
  plugin_publishes=$(prom_value rcs_etcd_manifest_publish_total)
  plugin_writes=$(prom_value rcs_etcd_blob_write_total)
  plugin_reads=$(prom_value rcs_etcd_blob_read_total)
  plugin_lists=$(prom_value rcs_etcd_blob_list_total)

  echo "METRIC rcs_etcd_manifest_publish_total=$plugin_publishes"
  echo "METRIC rcs_etcd_blob_write_total=$plugin_writes"
  echo "METRIC rcs_etcd_blob_read_total=$plugin_reads"
  echo "METRIC rcs_etcd_blob_list_total=$plugin_lists"
fi
echo "METRIC async_fetch_success_count_total=$async_success"
echo "METRIC async_fetch_failure_count_total=$async_failure"
echo "METRIC otlp_exporter_exported_total=$otlp_exported"

# --- Summary block ----------------------------------------------------------
# Aligned, fenced output designed for copy-paste into markdown / docs.
cat <<EOF

\`\`\`
========================================================================
 Smoke summary — config: $config
========================================================================

 Lifecycle (one-shot, captured during bring-up + smoke)
EOF
printf "   %-36s %s\n" "time_to_green_seconds" "${green:-n/a}"
if [ "$config" = "rcs-etcd" ]; then
  printf "   %-36s %s\n" "time_to_first_publish_seconds" "${publish:-n/a}"
fi
printf "   %-36s %s\n" "time_to_first_index_ack_ms" "${ack_ms:-n/a}"
echo
if [ "$config" = "rcs-etcd" ]; then
  echo " Plugin counters (cumulative since cluster start)"
  printf "   %-36s %s\n" "rcs_etcd_manifest_publish_total" "${plugin_publishes:-n/a}"
  printf "   %-36s %s\n" "rcs_etcd_blob_write_total" "${plugin_writes:-n/a}"
  printf "   %-36s %s\n" "rcs_etcd_blob_read_total" "${plugin_reads:-n/a}"
  printf "   %-36s %s\n" "rcs_etcd_blob_list_total" "${plugin_lists:-n/a}"
  echo
fi
echo " OpenSearch + telemetry counters"
printf "   %-36s %s\n" "async_fetch_success_count_total" "${async_success:-n/a}"
printf "   %-36s %s\n" "async_fetch_failure_count_total" "${async_failure:-n/a}"
printf "   %-36s %s\n" "otlp_exporter_exported_total" "${otlp_exported:-n/a}"
if [ "$config" = "rcs-etcd" ]; then
  echo
  echo " Storage"
  printf "   %-36s %s\n" "etcd_keys" "${keys:-n/a}"
fi
cat <<EOF

========================================================================
\`\`\`
EOF
