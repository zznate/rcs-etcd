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

if command -v opensearch-benchmark >/dev/null 2>&1; then
  echo "==> running geonames --test-mode"
  WORKLOADS="${OSB_WORKLOADS:-$HOME/Documents/GitHub/opensearch-benchmark-workloads}"
  opensearch-benchmark execute-test \
    --workload=geonames \
    --workload-path="$WORKLOADS/geonames" \
    --test-mode \
    --target-hosts="http://localhost:9200" \
    --pipeline=benchmark-only \
    --client-options='use_ssl:false,verify_certs:false' \
    --results-format=markdown 2>&1 | tail -40
else
  echo "WARN: opensearch-benchmark not installed — skipping benchmark run."
  echo "       pip install opensearch-benchmark to enable."
fi

# Cleanup the smoke index so successive runs start from the same state.
curl -sf -X DELETE "http://localhost:9200/smoke-idx" >/dev/null 2>&1 || true

if [ "$config" = "rcs-etcd" ]; then
  keys=$(kubectl exec -n "$NAMESPACE" etcd-0 -- \
    etcdctl get /opensearch-rcs/ --prefix --keys-only 2>/dev/null \
    | grep -c . || true)
  echo "METRIC etcd_keys=$keys"
fi
