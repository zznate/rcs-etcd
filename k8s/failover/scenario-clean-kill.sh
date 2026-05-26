#!/usr/bin/env bash
# Scenario 1: clean manager kill + SLA assertion.
#
# Kills the currently-elected cluster_manager pod, measures
# re-election latency, time to first green, and (optionally) time
# until the killed pod rejoins and the cluster is back to N=3
# green. Compares each against an SLA.
#
# Env tuning:
#   SLA_REELECTION                 default 15 (seconds)
#   SLA_GREEN_AFTER_KILL           default 45
#   SLA_FULL_RECOVERY              default 90
#   WAIT_FOR_REJOIN                default 1 (set 0 to skip full-recovery wait)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib.sh"

SLA_REELECTION=${SLA_REELECTION:-15}
SLA_GREEN_AFTER_KILL=${SLA_GREEN_AFTER_KILL:-45}
SLA_FULL_RECOVERY=${SLA_FULL_RECOVERY:-90}
WAIT_FOR_REJOIN=${WAIT_FOR_REJOIN:-1}

echo "==> Scenario: clean manager kill"
preflight

old_manager=$(current_manager)
echo "  current manager: $old_manager"

t_kill=$(date +%s)
kubectl delete pod "$old_manager" -n "$NAMESPACE" >/dev/null
echo "  killed: $old_manager"

new_manager=$(wait_for_new_manager "$old_manager" 60) || {
  echo "FAIL: no new manager elected within 60s" >&2
  exit 1
}
t_reelect=$(date +%s)
reelect_seconds=$((t_reelect - t_kill))
echo "  re-elected: $new_manager (in ${reelect_seconds}s)"

wait_for_green 120 || {
  echo "FAIL: cluster did not reach green within 120s" >&2
  exit 1
}
t_green=$(date +%s)
green_seconds=$((t_green - t_kill))
echo "  cluster green ${green_seconds}s after kill"

full_seconds=""
if [ "$WAIT_FOR_REJOIN" = "1" ]; then
  kubectl wait --for=condition=ready --timeout=180s "pod/$old_manager" -n "$NAMESPACE" >/dev/null
  wait_for_green 60 || {
    echo "FAIL: cluster did not return to green after $old_manager rejoined" >&2
    exit 1
  }
  t_full=$(date +%s)
  full_seconds=$((t_full - t_kill))
  echo "  fully recovered ${full_seconds}s after kill ($old_manager rejoined)"
else
  echo "  WAIT_FOR_REJOIN=0; not waiting for $old_manager to rejoin"
fi

echo
echo "METRIC manager_reelection_seconds=$reelect_seconds"
echo "METRIC cluster_green_after_kill_seconds=$green_seconds"
[ -n "$full_seconds" ] && echo "METRIC fully_recovered_seconds=$full_seconds"

echo
echo "==> SLA checks:"
ok=true
assert_le "$reelect_seconds" "$SLA_REELECTION"        "manager_reelection_seconds"     || ok=false
assert_le "$green_seconds"   "$SLA_GREEN_AFTER_KILL"  "cluster_green_after_kill_secs"  || ok=false
if [ -n "$full_seconds" ]; then
  assert_le "$full_seconds"  "$SLA_FULL_RECOVERY"     "fully_recovered_seconds"        || ok=false
fi

echo
if $ok; then
  echo "PASS  scenario-clean-kill"
  exit 0
else
  echo "FAIL  scenario-clean-kill"
  exit 1
fi
