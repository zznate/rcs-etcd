#!/usr/bin/env bash
# Scenario 2: state-correctness verification via pre/post snapshot diff.
#
# Creates a known set of declarative cluster-state artifacts (index,
# mapping, alias, ingest pipeline, index template, transient cluster
# setting), snapshots cluster state, kills the manager, waits for
# full recovery, snapshots again, and diffs the two snapshots through
# state_diff.py (which ignores fields that legitimately change across
# an election).
#
# PASS = no significant state differences across the failover.
# FAIL = any user-declared artifact lost, mutated, or invented.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

WAIT_FOR_REJOIN=${WAIT_FOR_REJOIN:-1}

WORKDIR=$(mktemp -d -t failover-state-diff.XXXXXX)
echo "==> Scenario: state diff across manager kill"
echo "  workdir: $WORKDIR"

cleanup_artifacts() {
  curl -sf -XDELETE "$OS/failover-idx"                    >/dev/null 2>&1 || true
  curl -sf -XDELETE "$OS/_index_template/failover-it"     >/dev/null 2>&1 || true
  curl -sf -XDELETE "$OS/_ingest/pipeline/failover-pl"    >/dev/null 2>&1 || true
  curl -sf -XPUT  "$OS/_cluster/settings" \
       -H 'Content-Type: application/json' \
       -d '{"transient":{"cluster.routing.allocation.disk.threshold_enabled":null}}' \
       >/dev/null 2>&1 || true
}
trap 'rm -rf "$WORKDIR"; cleanup_artifacts' EXIT

preflight
# Pre-clean in case a prior run aborted between create and trap.
cleanup_artifacts

echo "  creating test artifacts"
curl -sf -XPUT "$OS/_ingest/pipeline/failover-pl" \
     -H 'Content-Type: application/json' \
     -d '{"description":"failover correctness probe","processors":[{"set":{"field":"tested","value":true}}]}' \
     >/dev/null
curl -sf -XPUT "$OS/_index_template/failover-it" \
     -H 'Content-Type: application/json' \
     -d '{"index_patterns":["failover-*"],"template":{"settings":{"number_of_shards":1,"number_of_replicas":1}}}' \
     >/dev/null
curl -sf -XPUT "$OS/failover-idx" \
     -H 'Content-Type: application/json' \
     -d '{"settings":{"number_of_shards":1,"number_of_replicas":1},"mappings":{"properties":{"field_a":{"type":"keyword"},"field_b":{"type":"long"}}}}' \
     >/dev/null
curl -sf -XPOST "$OS/_aliases" \
     -H 'Content-Type: application/json' \
     -d '{"actions":[{"add":{"index":"failover-idx","alias":"failover"}}]}' \
     >/dev/null
curl -sf -XPUT "$OS/_cluster/settings" \
     -H 'Content-Type: application/json' \
     -d '{"transient":{"cluster.routing.allocation.disk.threshold_enabled":false}}' \
     >/dev/null

wait_for_green 30 || {
  echo "FAIL: cluster did not return to green after artifact creation" >&2
  exit 1
}

echo "  snapshotting expected state"
snapshot_state "$WORKDIR/expected.json"

old_manager=$(current_manager)
echo "  killing manager: $old_manager"
t_kill=$(date +%s)
kubectl delete pod "$old_manager" -n "$NAMESPACE" >/dev/null

new_manager=$(wait_for_new_manager "$old_manager" 60) || {
  echo "FAIL: no new manager elected within 60s" >&2
  exit 1
}
echo "  re-elected: $new_manager"

wait_for_green 120 || {
  echo "FAIL: cluster did not reach green within 120s" >&2
  exit 1
}
echo "  cluster green again"

if [ "$WAIT_FOR_REJOIN" = "1" ]; then
  kubectl wait --for=condition=ready --timeout=180s "pod/$old_manager" -n "$NAMESPACE" >/dev/null
  wait_for_green 60 || {
    echo "FAIL: cluster did not return to green after $old_manager rejoined" >&2
    exit 1
  }
  t_full=$(date +%s)
  echo "  fully recovered $((t_full - t_kill))s after kill ($old_manager rejoined)"
fi

echo "  snapshotting actual state"
snapshot_state "$WORKDIR/actual.json"

echo
echo "==> Comparing declarative state"
if python3 "$SCRIPT_DIR/state_diff.py" "$WORKDIR/expected.json" "$WORKDIR/actual.json"; then
  echo
  echo "PASS  scenario-state-diff"
  exit 0
else
  echo
  echo "FAIL  scenario-state-diff (state diverged across failover)"
  exit 1
fi
