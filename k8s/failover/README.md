# Failover scenarios

Automated assertions that the rcs-etcd cluster recovers correctly
when its elected `cluster_manager` is killed. Distinct from the
smoke tests — these scenarios emit explicit PASS / FAIL with a
non-zero exit code on failure, so they integrate cleanly with CI
once we're ready.

The scenarios assume the rcs-etcd cluster is already up and green
(`make up-rcs-etcd`). They do **not** bring the cluster up
themselves.

## Scenarios

### `scenario-clean-kill.sh`

Kills the elected `cluster_manager` pod, measures recovery, and
asserts against tunable SLAs.

| Metric | Default SLA | Meaning |
|---|---|---|
| `manager_reelection_seconds` | ≤ 15s | pod kill → new manager elected |
| `cluster_green_after_kill_seconds` | ≤ 45s | pod kill → `_cluster/health` returns green |
| `fully_recovered_seconds` | ≤ 90s | pod kill → killed pod rejoined and cluster green |

Override defaults via `SLA_REELECTION`, `SLA_GREEN_AFTER_KILL`,
`SLA_FULL_RECOVERY` (all in seconds). Set `WAIT_FOR_REJOIN=0` to
skip the rejoin step (faster, but loses the rejoin verification).

### `scenario-state-diff.sh`

Creates a known set of declarative cluster-state artifacts (index,
mapping, alias, ingest pipeline, index template, transient cluster
setting), snapshots state, kills the manager, waits for full
recovery, snapshots again, and runs `state_diff.py` to compare.

PASS = the declarative state survives bit-identical across the
failover. FAIL = any artifact lost, mutated, or invented.

`state_diff.py` ignores fields that legitimately change across a
re-election (`master_node`, `nodes.*`, `routing_table.*`,
`metadata.cluster_coordination`, `version`, per-index version
bumps, etc.) — see the `IGNORED` list at the top of the script.

## Running

```sh
make failover-suite          # both scenarios sequentially
make failover-clean-kill     # scenario 1 only
make failover-state-diff     # scenario 2 only
```

A failed scenario exits non-zero and the suite stops at that
scenario. State-diff cleans up its own artifacts via an EXIT trap
regardless of outcome.

## Out of scope

- **Etcd HA / partition** — the testbed runs a single-replica etcd
  ensemble; etcd failures are expected to be total outages. Testing
  the etcd ensemble itself is deferred to a real production
  deployment story.
- **Mid-mutation failure** (the third scenario originally
  considered) — would coordinate an OSB workload + kill timing.
  Tractable but more wiring; deferred until #1 and #2 stabilise.
- **Network partition between OS and etcd** — needs NetworkPolicy or
  pod sidecar tooling; not worth building until the chaos-test plane
  is broader than just failover.
