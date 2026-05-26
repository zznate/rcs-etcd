#!/usr/bin/env bash
# Longer-form exercise of the cluster-state path. Distinct from
# smoke.sh, which is the fast benchmark-driven health check.
#
# Phases:
#   1. diversify — touch every global-metadata blob type the plugin
#      writes (pipeline, component template, index template, cluster
#      setting, index, mapping, alias). Driven by OSB workload
#      rcs-etcd-state-ops, test-procedure `diversify`.
#   2. volume — 15 alternating replica toggles to trip
#      RETAINED_MANIFESTS cleanup. OSB test-procedure `volume`.
#   3. topology — scale the StatefulSet down to 2, back to 3, then
#      kill the elected cluster_manager pod. This is the only
#      smoke-time exercise of the plugin's READ path (joining and
#      newly-elected managers bootstrap state from etcd). Driven by
#      kubectl in this script; OSB cannot reach kubectl. Measures
#      manager_reelection_seconds.
#   4. burst — 50 small index creates + deletes serially; stresses
#      the publish-rate edge. OSB test-procedure `burst`. Also
#      contains the workload cleanup ops at its tail.
#
# Phase order at runtime is 1 → 2 → 4 → 3 so the topology phase
# stresses an already-busy cluster; the summary block keeps the
# numeric phase IDs to match the docs.
#
# Each phase boundary snapshots:
#   - cluster-state version (monotonic, manager-authoritative,
#     survives pod restarts — unlike per-pod rcs_etcd_* counters)
#   - etcd keyspace count (rcs-etcd config only)
#
# An EXIT trap removes any sm-cs-* / burst-* leftovers + resets the
# transient cluster setting, in case the workload aborts midway.

set -euo pipefail

NAMESPACE=rcs-etcd-poc
OS="http://localhost:9200"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORKLOAD_DIR="$SCRIPT_DIR/workloads/rcs-etcd-state-ops"

if command -v opensearch-benchmark >/dev/null 2>&1; then
  OSB=(opensearch-benchmark)
elif [ -x "$HOME/.local/bin/opensearch-benchmark" ]; then
  OSB=("$HOME/.local/bin/opensearch-benchmark")
else
  echo "ERROR: opensearch-benchmark not found — run 'make benchmark-setup' first." >&2
  exit 1
fi

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

current_manager() {
  curl -sf "$OS/_cat/master?format=json" 2>/dev/null \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)[0]["node"])' 2>/dev/null
}

cleanup() {
  echo
  echo "==> trap cleanup (in case the workload aborted)"
  for prefix in "sm-cs" "burst"; do
    indices=$(curl -sf "$OS/_cat/indices/${prefix}*?h=index" 2>/dev/null || true)
    if [ -n "$indices" ]; then
      for idx in $indices; do
        curl -sf -XDELETE "$OS/$idx" >/dev/null 2>&1 || true
      done
    fi
  done
  curl -sf -XDELETE "$OS/_index_template/sm-cs-it" >/dev/null 2>&1 || true
  curl -sf -XDELETE "$OS/_component_template/sm-cs-ct" >/dev/null 2>&1 || true
  curl -sf -XDELETE "$OS/_ingest/pipeline/sm-cs-pl" >/dev/null 2>&1 || true
  curl -sf -XPUT "$OS/_cluster/settings" \
    -H 'Content-Type: application/json' \
    -d '{"transient":{"cluster.routing.allocation.disk.threshold_enabled":null}}' \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

run_osb_phase() {
  local procedure="$1"
  echo
  echo "==> Phase: $procedure (via OSB workload)"
  "${OSB[@]}" run \
    --workload-path="$WORKLOAD_DIR" \
    --target-hosts="$OS" \
    --pipeline=benchmark-only \
    --client-options='use_ssl:false,verify_certs:false' \
    --test-procedure="$procedure" \
    --kill-running-processes 2>&1 \
    | grep -E "^\[INFO\] (\[Test|.* SUCCESS|.* FAILURE)|^\[ERROR\]" \
    | grep -v "Did you mean" || true
}

version_start=$(cluster_state_version)
etcd_start=$(etcd_key_count)

run_osb_phase diversify
sleep 5
version_after_p1=$(cluster_state_version)
etcd_after_p1=$(etcd_key_count)

run_osb_phase volume
sleep 5
version_after_p2=$(cluster_state_version)
etcd_after_p2=$(etcd_key_count)

run_osb_phase burst
sleep 5
version_after_p4=$(cluster_state_version)
etcd_after_p4=$(etcd_key_count)

# --- Phase 3: Topology (kubectl, not OSB) -----------------------------------
echo
echo "==> Phase 3 — topology (scale down + back up + kill manager)"

kubectl scale statefulset opensearch -n "$NAMESPACE" --replicas=2 >/dev/null
kubectl wait --for=delete pod/opensearch-2 -n "$NAMESPACE" --timeout=120s 2>/dev/null || true
echo "    scaled to 2 replicas"

kubectl scale statefulset opensearch -n "$NAMESPACE" --replicas=3 >/dev/null
kubectl wait --for=condition=ready --timeout=300s pod/opensearch-2 -n "$NAMESPACE"
echo "    scaled back to 3; opensearch-2 ready"

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
etcd_after_p3=$(etcd_key_count)

# --- Summary block ----------------------------------------------------------
delta() {
  local end="$1"
  local start="$2"
  if [ "$end" = "n/a" ] || [ "$start" = "n/a" ]; then
    echo "n/a"
  else
    local d=$(( ${end%.*} - ${start%.*} ))
    if [ "$d" -ge 0 ]; then printf "+%d" "$d"; else printf "%d" "$d"; fi
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
printf "   %-36s %s\n" "phase 4 (burst)"      "$(delta "$version_after_p4" "$version_after_p2")"
printf "   %-36s %s\n" "phase 3 (topology)"   "$(delta "$version_after_p3" "$version_after_p4")"

if [ "$config" = "rcs-etcd" ]; then
  echo
  echo " etcd keys (delta per phase — phase 2 may drop from cleanup)"
  printf "   %-36s %s\n" "phase 1 (diversify)" "$(delta "$etcd_after_p1" "$etcd_start")"
  printf "   %-36s %s\n" "phase 2 (volume)"     "$(delta "$etcd_after_p2" "$etcd_after_p1")"
  printf "   %-36s %s\n" "phase 4 (burst)"      "$(delta "$etcd_after_p4" "$etcd_after_p2")"
  printf "   %-36s %s\n" "phase 3 (topology)"   "$(delta "$etcd_after_p3" "$etcd_after_p4")"
fi
echo
echo " Topology"
printf "   %-36s %s\n" "manager_reelection_seconds" "$manager_reelection_seconds"

if [ "$config" = "rcs-etcd" ]; then
  echo
  echo " Storage (final)"
  printf "   %-36s %s\n" "etcd_keys" "$etcd_after_p3"
fi

cat <<EOF

 Per-op latency for phases 1, 2, 4 is in ~/.osb/benchmarks/test_runs/
 — see the Test Run IDs printed above.
========================================================================
\`\`\`
EOF
