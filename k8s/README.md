# rcs-etcd vs vanilla OpenSearch comparison testbed

Local-laptop Kubernetes deployment that brings up either of two
interchangeable 4-node OpenSearch clusters — one vanilla, one running
the `rcs-etcd` plugin with Remote Cluster State backed by etcd — wired
into a shared OpenTelemetry observability stack and exercised by an
`opensearch-benchmark` smoke run.

The two configurations are not run simultaneously. Switching between
them tears down one and brings up the other in the same namespace.

## Prerequisites

- Podman with Kind v0.23+. Kind invocations export
  `KIND_EXPERIMENTAL_PROVIDER=podman` from the Makefile.
- `kubectl` configured to talk to the local kubelet.
- JDK 21 (for the Gradle plugin build).
- Clones of `opensearch-project/opensearch-benchmark` and
  `opensearch-project/opensearch-benchmark-workloads` at `../../`
  (siblings to this repo). `make benchmark-setup` installs the runner
  user-locally to `~/.local/bin/opensearch-benchmark` from the sibling
  checkout. Override the paths with `OSB_REPO` / `OSB_WORKLOADS` if you
  keep them elsewhere. `~/.local/bin` must be on `PATH` for `make smoke`
  to find the binary; the smoke script also probes that directory as a
  fallback.

## Layout

```
k8s/
  kind/              Kind cluster config + namespace declarations
  otel-stack/        Self-contained observability stack (see otel-stack/README.md)
  common/            etcd StatefulSet, headless Service, OTel sidecar ConfigMap
  vanilla/           opensearch.yml + StatefulSet + client Service (no RCS)
  rcs-etcd/          opensearch.yml + StatefulSet + client Service (RCS enabled)
  Dockerfile         Testbed image (OpenSearch 3.0.0 + rcs-etcd + telemetry-otel)
  Makefile           Entry points
  bring-up.sh        Used by up-vanilla / up-rcs-etcd
  smoke.sh           Used by smoke
```

## End-to-end flow

```sh
cd k8s
make cluster          # creates `opensearch-testing` Kind cluster + namespaces
make otel-stack       # deploys the bundled observability stack (includes rcs-etcd dashboards)
make image            # builds the plugin zip, the image, and kind-loads it
make benchmark-setup  # one-time: user-local install of opensearch-benchmark
make up-rcs-etcd      # brings up the rcs-etcd configuration; records timings
make smoke            # replays timings + runs the benchmark + dumps etcd keys
make switch-to-vanilla
make smoke            # same metrics under vanilla for comparison
make down             # tears down rcs-etcd-poc, leaves otel-stack running
```

`make cluster` refuses to run if a Kind cluster named
`opensearch-testing` already exists. Delete it explicitly
(`kind delete cluster --name opensearch-testing`) if you intend
to recreate.

## Host endpoints

The Kind cluster's `extraPortMappings` expose:

| Host port | Service |
|-----------|---------|
| 9200 | OpenSearch REST (current config) |
| 3000 | Grafana (admin/admin) |
| 9090 | Prometheus |
| 16686 | Jaeger UI |
| 2379 | etcd client API (debug-only NodePort) |

So `curl http://localhost:9200/_cluster/health` and `etcdctl
--endpoints=http://localhost:2379 get /opensearch-rcs/ --prefix
--keys-only` work directly from the host with no port-forwards.

## Dashboards

Two dashboards land in Grafana under the **rcs-etcd** folder:

- **rcs-etcd Plugin** — manifest publish rate, blob op rates
  (write/read/list), cumulative counters, and per-pod OTLP exporter
  throughput. The rate panels are the headline view of the
  RCS-via-etcd path during a smoke.
- **OpenSearch Cluster** — async-fetch success/failure rate plus a
  notes panel describing instruments that are deferred to broader
  OpenSearch SPI coverage and to exponential-histogram support
  landing upstream in the contrib Prometheus exporter.

## Telemetry routing

OpenSearch 3.0.0's `telemetry-otel` module constructs the OTLP
exporter via `OtlpGrpcMetricExporter.getDefault()`, which hardcodes
the endpoint to `localhost:4317` and ignores
`OTEL_EXPORTER_OTLP_ENDPOINT`. A lightweight `otelcol-contrib`
sidecar in each OpenSearch pod listens on that port and forwards
traces and metrics to the otel-stack collector. The sidecar
config is `common/otel-sidecar.yaml`.

## Telemetry surfaces

There are two complementary surfaces for observing what each
configuration does. They answer different questions and live in
different places.

### Lifecycle metrics (one-shot, from `make smoke` stdout)

Captured by `bring-up.sh` (during cluster formation) and `smoke.sh`
(during the smoke run). One value each per run. Useful as comparison
numbers in a report.

| Metric | When | Source |
|--------|------|--------|
| `time_to_green_seconds` | both configs | Wall-clock from `kubectl apply` to all three pods Ready |
| `time_to_first_publish_seconds` | rcs-etcd only | Wall-clock from `kubectl apply` to first `manifest__` key in etcd |
| `time_to_first_index_ack_ms` | both configs | Latency of a `PUT smoke-idx` against `localhost:9200` |
| `etcd_keys` | rcs-etcd only | Final keyspace count under `/opensearch-rcs/` |

Bring-up timings persist as annotations on the `opensearch-config`
ConfigMap so successive `smoke` runs replay them without
re-bootstrapping.

### Continuous metrics (Prometheus / Grafana dashboards)

Pushed by OpenSearch via OTLP through the per-pod sidecar to the
otel-stack collector, scraped by Prometheus, rendered on the
**rcs-etcd Plugin** and **OpenSearch Cluster** dashboards under the
`rcs-etcd` folder in Grafana. Rate and cumulative shapes over time.
Useful for watching what the cluster is doing while a smoke runs.

Headline series:

- `rcs_etcd_manifest_publish_total` — counter; rate is the headline
  view of the RCS-via-etcd publish path.
- `rcs_etcd_blob_{write,read,list}_total` — counters per blob op.
- `async_fetch_{success,failure}_count_total` — OpenSearch core
  shard-fetch activity (one of the few core operations instrumented
  via the SPI at 3.0.0).
- `otlp_exporter_exported_total` — pipeline health: data points
  leaving the sidecar collector.

### Bridge: the smoke summary block

After the benchmark completes, `smoke.sh` snapshots the Prometheus
counters above and prints a single fenced block combining the
lifecycle measurements with the cumulative counter values. The block
is markdown-paste-ready — drop it into a comparison doc verbatim:

```
========================================================================
 Smoke summary — config: rcs-etcd
========================================================================

 Lifecycle (one-shot, captured during bring-up + smoke)
   time_to_green_seconds                130
   time_to_first_publish_seconds        130
   time_to_first_index_ack_ms           212

 Plugin counters (cumulative since cluster start)
   rcs_etcd_manifest_publish_total      118
   rcs_etcd_blob_write_total            253
   rcs_etcd_blob_read_total             137
   rcs_etcd_blob_list_total             4

 OpenSearch + telemetry counters
   async_fetch_success_count_total      3
   async_fetch_failure_count_total      0
   otlp_exporter_exported_total         588

 Storage
   etcd_keys                            66

========================================================================
```

Counter values are "n/a" if Prometheus isn't reachable (otel-stack
not deployed). The individual `METRIC <name>=<value>` lines remain on
stdout above the block for downstream parsing.

## Extended smoke (`make smoke-cluster-state`)

A longer (~3–4 min) exercise of the cluster-state path, separate
from `make smoke`. Four phases:

- **diversify** — touch every global-metadata blob type the plugin
  writes (ingest pipeline, component + index templates, cluster
  setting, index, mapping, alias).
- **volume** — 15 alternating replica toggles to trip the hardcoded
  `RETAINED_MANIFESTS = 10` cleanup path.
- **burst** — 50 small `create-index` + `delete-index` pairs.
- **topology** — `kubectl scale` the StatefulSet down to 2, back to
  3, then `kubectl delete pod <current-cluster-manager>` to force a
  re-election. Records `manager_reelection_seconds`.

The first three phases run as a custom `opensearch-benchmark`
workload at `k8s/workloads/rcs-etcd-state-ops/`, with one
`test-procedure` per phase (`diversify`, `volume`, `burst`). Most
ops use OSB's native operation types (`put-pipeline`,
`create-component-template`, `create-index`, etc.); the few whose
native runners reference opensearch-py methods missing in the
current 2.x client fall back to `raw-request`. A small
`workload.py` registers a param source that cycles `burst-0` …
`burst-49` for the burst phase. Per-op latency histograms land in
`~/.osb/benchmarks/test_runs/<id>/`.

The topology phase stays in bash because OSB can't drive kubectl.
The summary block follows the same fenced shape as `make smoke`,
with per-phase deltas for cluster-state version (manager-
authoritative, monotonic across restarts) and etcd keyspace.

## Failover suite (`make failover-suite`)

Automated assertions that the rcs-etcd cluster recovers correctly
when its elected `cluster_manager` pod is killed. Distinct from the
smoke tests — these scenarios emit explicit PASS / FAIL with a
non-zero exit code so they integrate cleanly with CI.

Two scenarios:

- **scenario-clean-kill** (`make failover-clean-kill`) — kills the
  elected manager, measures `manager_reelection_seconds`,
  `cluster_green_after_kill_seconds`, and (when `WAIT_FOR_REJOIN=1`,
  the default) `fully_recovered_seconds`. Asserts each against an
  SLA. Tune via `SLA_REELECTION` / `SLA_GREEN_AFTER_KILL` /
  `SLA_FULL_RECOVERY` env vars.
- **scenario-state-diff** (`make failover-state-diff`) — creates a
  known set of declarative artifacts (index, mapping, alias, ingest
  pipeline, index template, transient cluster setting), kills the
  manager, waits for full recovery, then diffs cluster state. The
  Python `state_diff.py` strips fields that legitimately change
  across an election (`master_node` / `cluster_manager_node`,
  `state_uuid`, `nodes.*`, `routing_table.*`, `primary_terms`,
  `in_sync_allocations`, version counters, etc.) and exits non-zero
  if any user-declared artifact diverges.

Full suite runs both sequentially; ~75s total on a healthy cluster.
See `k8s/failover/README.md` for the design rationale and the
deferred follow-on scenarios.

## Out of scope

Failover automation — killing an elected cluster_manager and
verifying recovery via etcd-backed cluster state — is tracked
separately and intentionally not part of this testbed.
