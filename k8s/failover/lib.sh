#!/usr/bin/env bash
# Shared helpers for the failover scenarios. Sourced, not executed.
# Keeps the per-scenario scripts focused on the scenario itself.

NAMESPACE=${NAMESPACE:-rcs-etcd-poc}
OS=${OS:-http://localhost:9200}

# Resolve the current cluster_manager pod name via the _cat/cluster_manager API.
current_manager() {
  curl -sf "$OS/_cat/cluster_manager?format=json" 2>/dev/null \
    | python3 -c 'import sys, json
data = json.load(sys.stdin)
print(data[0]["node"] if data else "")' 2>/dev/null
}

# Block until the elected manager differs from $1 (the killed pod's name).
# Returns the new manager's name on stdout. Exits non-zero on timeout.
wait_for_new_manager() {
  local old="$1"
  local timeout="${2:-60}"
  local elapsed=0
  local current
  while [ "$elapsed" -lt "$timeout" ]; do
    current=$(current_manager 2>/dev/null || true)
    if [ -n "$current" ] && [ "$current" != "$old" ]; then
      echo "$current"
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  return 1
}

# Block until the cluster reports green. Returns non-zero on timeout.
wait_for_green() {
  local timeout="${1:-90}"
  local elapsed=0
  while [ "$elapsed" -lt "$timeout" ]; do
    if curl -sf "$OS/_cluster/health?wait_for_status=green&timeout=2s" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  return 1
}

# Snapshot the full cluster state to $1.
snapshot_state() {
  curl -sf "$OS/_cluster/state" > "$1"
}

# Assert numeric metric <= limit. Prints check-mark line and returns 0 if
# within bounds; cross line and 1 if exceeded.
assert_le() {
  local value="$1"
  local limit="$2"
  local metric="$3"
  if [ "$value" -le "$limit" ]; then
    printf "  PASS  %-36s %ss (limit %ss)\n" "$metric" "$value" "$limit"
    return 0
  else
    printf "  FAIL  %-36s %ss > %ss\n" "$metric" "$value" "$limit"
    return 1
  fi
}

# Preflight: verify we're talking to a reachable, green cluster before
# disturbing it. Bail with a clear message if not.
preflight() {
  if ! curl -sf "$OS/_cluster/health" >/dev/null 2>&1; then
    echo "PRECONDITION FAIL: OpenSearch not reachable on $OS — bring the cluster up first." >&2
    return 1
  fi
  local status
  status=$(curl -sf "$OS/_cluster/health" | python3 -c 'import sys, json; print(json.load(sys.stdin)["status"])')
  if [ "$status" != "green" ]; then
    echo "PRECONDITION FAIL: cluster status is $status, refusing to run failover on a non-green cluster." >&2
    return 1
  fi
}
