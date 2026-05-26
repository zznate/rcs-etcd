#!/usr/bin/env bash
# Longer-form exercise of the cluster-state path. Distinct from
# smoke.sh, which is the fast benchmark-driven health check.
#
# Phases:
#   1. Diversify — touch every global-metadata blob type the plugin
#      writes (pipeline, component template, index template, cluster
#      setting, index, mapping, alias).
#   2. Volume — 15 replica toggles to trip RETAINED_MANIFESTS cleanup.
#   3. Topology — scale down to 2, back to 3, then kill the elected
#      cluster_manager pod; measures manager re-election latency.
#      This is the only smoke-time exercise of the plugin's READ path
#      (joining/new nodes bootstrap state from etcd).
#   4. Burst — create 50 small indices serially, then delete them;
#      stresses the publish-rate path with many small state changes.
#
# Each phase snapshots the cluster state version
# (`_cluster/state?filter_path=version`) at its boundary; the final
# summary block reports per-phase deltas plus the standard cumulative
# counters and storage figures. The cluster-state version is the
# authoritative monotonic counter of cluster-state changes — it
# survives manager re-election and follower restarts, where the
# per-pod `rcs_etcd_manifest_publish_total` does not.
#
# An EXIT trap removes all `sm-cs-*` and `burst-*` artifacts plus
# resets the transient cluster setting, so successive runs start
# from the same shape.

set -euo pipefail

NAMESPACE=rcs-etcd-poc
OS="http://localhost:9200"
PROM="http://localhost:9090"

config="${1:-}"
if [ -z "$config" ]; then
  config=$(kubectl get configmap opensearch-config -n "$NAMESPACE" \
    -o jsonpath='{.metadata.labels.config}' 2>/dev/null || true)
fi
case "$config" in
  vanilla|rcs-etcd) ;;
  *) echo "no active config detected; pass vanilla|rcs-etcd as arg" >&2; exit 1 ;;
esac

if ! curl -sf "$OS/_cluster/health" >/dev/null; then
  echo "OpenSearch not reachable on localhost:9200 — bring the cluster up first." >&2
  exit 1
fi

echo "==> cluster-state smoke against $config"

prom_value() {
  curl -sf "$PROM/api/v1/query?query=$1" 2>/dev/null \
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

cleanup() {
  echo
  echo "==> cleanup"
  curl -sf -XDELETE "$OS/sm-cs-idx" >/dev/null 2>&1 || true
  for i in $(seq 1 50); do
    curl -sf -XDELETE "$OS/burst-$i" >/dev/null 2>&1 || true
  done
  curl -sf -XDELETE "$OS/_index_template/sm-cs-it" >/dev/null 2>&1 || true
  curl -sf -XDELETE "$OS/_component_template/sm-cs-ct" >/dev/null 2>&1 || true
  curl -sf -XDELETE "$OS/_ingest/pipeline/sm-cs-pl" >/dev/null 2>&1 || true
  curl -sf -XPUT "$OS/_cluster/settings" \
    -H 'Content-Type: application/json' \
    -d '{"transient": {"cluster.routing.allocation.disk.threshold_enabled": null}}' \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

current_manager() {
  curl -sf "$OS/_cat/master?format=json" 2>/dev/null \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)[0]["node"])' 2>/dev/null
}

cluster_state_version() {
  curl -sf "$OS/_cluster/state?filter_path=version" 2>/dev/null \
    | python3 -c 'import sys, json; print(json.load(sys.stdin).get("version", 0))' 2>/dev/null \
    || echo "n/a"
}

etcd_key_count() {
  if [ "$config" = "rcs-etcd" ]; then
    kubectl exec -n "$NAMESPACE" etcd-0 -- \
      etcdctl get /opensearch-rcs/ --prefix --keys-only 2>/dev/null \
      | grep -c . || echo "0"
  else
    echo "n/a"
  fi
}

version_start=$(cluster_state_version)
etcd_keys_start=$(etcd_key_count)

# --- Phase 1: Diversify -----------------------------------------------------
echo
echo "==> Phase 1 — diversify (touch all global-metadata blob types)"

curl -sf -XPUT "$OS/_ingest/pipeline/sm-cs-pl" \
  -H 'Content-Type: application/json' \
  -d '{"description":"cluster-state smoke","processors":[{"set":{"field":"exercised","value":true}}]}' \
  >/dev/null
echo "    ingest pipeline: sm-cs-pl"

curl -sf -XPUT "$OS/_component_template/sm-cs-ct" \
  -H 'Content-Type: application/json' \
  -d '{"template":{"settings":{"number_of_shards":1,"number_of_replicas":1}}}' \
  >/dev/null
echo "    component template: sm-cs-ct"

curl -sf -XPUT "$OS/_index_template/sm-cs-it" \
  -H 'Content-Type: application/json' \
  -d '{"index_patterns":["sm-cs-*"],"composed_of":["sm-cs-ct"]}' \
  >/dev/null
echo "    index template: sm-cs-it"

curl -sf -XPUT "$OS/_cluster/settings" \
  -H 'Content-Type: application/json' \
  -d '{"transient":{"cluster.routing.allocation.disk.threshold_enabled":false}}' \
  >/dev/null
echo "    cluster setting: disk threshold disabled"

curl -sf -XPUT "$OS/sm-cs-idx" \
  -H 'Content-Type: application/json' \
  -d '{"settings":{"number_of_shards":1,"number_of_replicas":1}}' \
  >/dev/null
echo "    index: sm-cs-idx"

curl -sf -XPUT "$OS/sm-cs-idx/_mapping" \
  -H 'Content-Type: application/json' \
  -d '{"properties":{"field_a":{"type":"keyword"},"field_b":{"type":"long"},"field_c":{"type":"date"}}}' \
  >/dev/null
echo "    mapping: 3 fields added to sm-cs-idx"

curl -sf -XPOST "$OS/_aliases" \
  -H 'Content-Type: application/json' \
  -d '{"actions":[{"add":{"index":"sm-cs-idx","alias":"sm-cs"}}]}' \
  >/dev/null
echo "    alias: sm-cs -> sm-cs-idx"

sleep 5
version_after_p1=$(cluster_state_version)
etcd_keys_after_p1=$(etcd_key_count)

# --- Phase 2: Volume --------------------------------------------------------
echo
echo "==> Phase 2 — volume (15 replica toggles)"
for i in $(seq 1 15); do
  r=$(( i % 2 ))
  curl -sf -XPUT "$OS/sm-cs-idx/_settings" \
    -H 'Content-Type: application/json' \
    -d "{\"index.number_of_replicas\": $r}" >/dev/null
done
echo "    done (replicas toggled $i times)"

sleep 5
version_after_p2=$(cluster_state_version)
etcd_keys_after_p2=$(etcd_key_count)

# --- Phase 3: Topology ------------------------------------------------------
echo
echo "==> Phase 3 — topology (scale down + back up + kill manager)"

kubectl scale statefulset opensearch -n "$NAMESPACE" --replicas=2 >/dev/null
kubectl wait --for=delete pod/opensearch-2 -n "$NAMESPACE" --timeout=120s 2>/dev/null || true
echo "    scaled to 2 replicas"

kubectl scale statefulset opensearch -n "$NAMESPACE" --replicas=3 >/dev/null
kubectl wait --for=condition=ready --timeout=300s pod/opensearch-2 -n "$NAMESPACE"
echo "    scaled back to 3; opensearch-2 ready"

# Wait for cluster green before the manager kill, so we know which one
# is genuinely the manager.
until curl -sf "$OS/_cluster/health?wait_for_status=green&timeout=30s" >/dev/null; do
  sleep 2
done

manager=$(current_manager)
echo "    current manager: $manager"

t0=$(date +%s)
kubectl delete pod "$manager" -n "$NAMESPACE" >/dev/null

new_manager=""
while [ -z "$new_manager" ] || [ "$new_manager" = "$manager" ]; do
  sleep 2
  new_manager=$(current_manager || true)
done
t1=$(date +%s)
manager_reelection_seconds=$(( t1 - t0 ))
echo "    new manager: $new_manager (re-elected in ${manager_reelection_seconds}s)"

kubectl wait --for=condition=ready --timeout=300s "pod/$manager" -n "$NAMESPACE"
until curl -sf "$OS/_cluster/health?wait_for_status=green&timeout=30s" >/dev/null; do
  sleep 2
done
echo "    cluster back to green; $manager rejoined"

sleep 5
version_after_p3=$(cluster_state_version)
etcd_keys_after_p3=$(etcd_key_count)

# --- Phase 4: Burst ---------------------------------------------------------
echo
echo "==> Phase 4 — burst (50 small indices create + delete)"
for i in $(seq 1 50); do
  curl -sf -XPUT "$OS/burst-$i" \
    -H 'Content-Type: application/json' \
    -d '{"settings":{"number_of_shards":1,"number_of_replicas":0}}' \
    >/dev/null
done
echo "    created 50 indices"

for i in $(seq 1 50); do
  curl -sf -XDELETE "$OS/burst-$i" >/dev/null
done
echo "    deleted 50 indices"

sleep 5
version_after_p4=$(cluster_state_version)
etcd_keys_after_p4=$(etcd_key_count)

# --- Final counters + summary ----------------------------------------------
plugin_publishes=$(prom_value rcs_etcd_manifest_publish_total)
plugin_writes=$(prom_value rcs_etcd_blob_write_total)
plugin_reads=$(prom_value rcs_etcd_blob_read_total)
plugin_lists=$(prom_value rcs_etcd_blob_list_total)
async_success=$(prom_value async_fetch_success_count_total)
async_failure=$(prom_value async_fetch_failure_count_total)
otlp_exported=$(prom_value otlp_exporter_exported_total)

delta() {
  local end="$1"
  local start="$2"
  if [ "$end" = "n/a" ] || [ "$start" = "n/a" ]; then
    echo "n/a"
  else
    local d=$(( ${end%.*} - ${start%.*} ))
    if [ "$d" -ge 0 ]; then
      printf "+%d" "$d"
    else
      printf "%d" "$d"
    fi
  fi
}

cat <<EOF

\`\`\`
========================================================================
 Cluster-state smoke summary — config: $config
========================================================================

 Cluster-state version (delta per phase — monotonic, survives restarts)
EOF
printf "   %-36s %s\n" "phase 1 (diversify)" "$(delta "$version_after_p1" "$version_start")"
printf "   %-36s %s\n" "phase 2 (volume)"     "$(delta "$version_after_p2" "$version_after_p1")"
printf "   %-36s %s\n" "phase 3 (topology)"   "$(delta "$version_after_p3" "$version_after_p2")"
printf "   %-36s %s\n" "phase 4 (burst)"      "$(delta "$version_after_p4" "$version_after_p3")"
if [ "$config" = "rcs-etcd" ]; then
  echo
  echo " etcd keys (delta per phase — phase 2 expected to drop from cleanup)"
  printf "   %-36s %s\n" "phase 1 (diversify)" "$(delta "$etcd_keys_after_p1" "$etcd_keys_start")"
  printf "   %-36s %s\n" "phase 2 (volume)"     "$(delta "$etcd_keys_after_p2" "$etcd_keys_after_p1")"
  printf "   %-36s %s\n" "phase 3 (topology)"   "$(delta "$etcd_keys_after_p3" "$etcd_keys_after_p2")"
  printf "   %-36s %s\n" "phase 4 (burst)"      "$(delta "$etcd_keys_after_p4" "$etcd_keys_after_p3")"
fi
echo
echo " Topology"
printf "   %-36s %s\n" "manager_reelection_seconds" "$manager_reelection_seconds"
echo
echo " Cumulative counters (final — per-pod, may reset on topology phase)"
if [ "$config" = "rcs-etcd" ]; then
  printf "   %-36s %s\n" "rcs_etcd_manifest_publish_total" "$plugin_publishes"
  printf "   %-36s %s\n" "rcs_etcd_blob_write_total" "$plugin_writes"
  printf "   %-36s %s\n" "rcs_etcd_blob_read_total" "$plugin_reads"
  printf "   %-36s %s\n" "rcs_etcd_blob_list_total" "$plugin_lists"
fi
printf "   %-36s %s\n" "async_fetch_success_count_total" "$async_success"
printf "   %-36s %s\n" "async_fetch_failure_count_total" "$async_failure"
printf "   %-36s %s\n" "otlp_exporter_exported_total" "$otlp_exported"
if [ "$config" = "rcs-etcd" ]; then
  echo
  echo " Storage (final)"
  printf "   %-36s %s\n" "etcd_keys" "$etcd_keys_after_p4"
fi
cat <<EOF

========================================================================
\`\`\`
EOF
