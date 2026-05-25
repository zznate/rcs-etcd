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
- A local checkout of `stepflow` at `~/Documents/GitHub/stepflow`
  (override via `STEPFLOW_REPO`). The o11y manifests live under
  `examples/production/k8s/stepflow-o11y/` and are applied wholesale.
- `opensearch-benchmark` (`pip install opensearch-benchmark`) and a
  checkout of `opensearch-benchmark-workloads` for the `geonames`
  workload. The smoke script defaults to
  `~/Documents/GitHub/opensearch-benchmark-workloads`; override with
  `OSB_WORKLOADS`. The smoke prints a warning and skips the benchmark
  if the CLI is missing.

## Layout

```
k8s/
  kind/              Kind cluster config + namespace declarations
  common/            etcd StatefulSet, headless Service, OTel sidecar ConfigMap
  vanilla/           opensearch.yml + StatefulSet + client Service (no RCS)
  rcs-etcd/          opensearch.yml + StatefulSet + client Service (RCS enabled)
  dashboards/        Grafana dashboard JSONs + their wrapping ConfigMap
  o11y-patches/      Patches that mount the dashboards into stepflow-o11y Grafana
  Dockerfile         Testbed image (OpenSearch 3.0.0 + rcs-etcd + telemetry-otel)
  Makefile           Entry points
  bring-up.sh        Used by up-vanilla / up-rcs-etcd
  smoke.sh           Used by smoke
```

## End-to-end flow

```sh
cd k8s
make cluster        # creates `opensearch-testing` Kind cluster + namespaces
make o11y           # deploys the stepflow-o11y stack into it
make image          # builds the plugin zip, the image, and kind-loads it
make dashboards     # one-time: mounts the rcs-etcd dashboards into Grafana
make up-rcs-etcd    # brings up the rcs-etcd configuration; records timings
make smoke          # replays timings + runs the benchmark + dumps etcd keys
make switch-to-vanilla
make smoke          # same metrics under vanilla for comparison
make down           # tears down rcs-etcd-poc, leaves stepflow-o11y running
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
traces and metrics to the stepflow-o11y collector. The sidecar
config is `common/otel-sidecar.yaml`.

## Smoke metrics

Each `smoke` run emits parseable `METRIC <name>=<value>` lines on
stdout:

| Metric | When emitted | Source |
|--------|--------------|--------|
| `time_to_green_seconds` | both configs | Captured by `bring-up.sh` |
| `time_to_first_publish_seconds` | rcs-etcd only | Captured by `bring-up.sh` (waits for first `manifest__` key) |
| `time_to_first_index_ack_ms` | both configs | Measured by `smoke.sh` via a `PUT smoke-idx` |
| `etcd_keys` | rcs-etcd only | Final etcd keyspace count |

The bring-up timings persist as annotations on the
`opensearch-config` ConfigMap so successive `smoke` runs replay
them without re-bootstrapping.

## Out of scope

Failover automation — killing an elected cluster_manager and
verifying recovery via etcd-backed cluster state — is tracked
separately and intentionally not part of this testbed.
