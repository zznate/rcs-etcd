# otel-stack — self-contained observability for development and staging

A vendored OpenTelemetry-centric observability stack that lives entirely
inside this repository. Deploys into the `otel-stack` namespace via
`make otel-stack` (or `kubectl apply -f` per component). No external
checkouts, no Helm charts, no operator dependencies — six manifests,
flat YAML, pinned image tags.

The stack is **optional infrastructure**: the rcs-etcd testbed runs
without it (sidecars warn about failed exports but the OpenSearch
cluster behaves normally). It exists so a developer can stand up a
complete OTLP pipeline + dashboarding surface from one repo on one
laptop in under three minutes.

## What's in it

| Component | Image | Purpose |
|-----------|-------|---------|
| OTel Collector | `otel/opentelemetry-collector-contrib:0.95.0` | OTLP gRPC/HTTP receivers; fans out to Jaeger, Prometheus, Loki |
| Prometheus | `prom/prometheus:v2.49.1` | Metrics store + scraper |
| Grafana | `grafana/grafana:10.3.3` | Dashboarding (admin/admin) |
| Jaeger | `jaegertracing/all-in-one:1.54` | Trace store + UI |
| Loki | `grafana/loki:2.9.4` | Log store |
| Promtail | `grafana/promtail:2.9.4` | DaemonSet log shipper |

All workloads are single-replica. Loki and Grafana have PVCs; everything
else is ephemeral. Resource requests are sized for a laptop (combined
overhead at idle is ~500 MiB / ~0.5 vCPU).

## Pipeline shape

```
   workloads
     │ OTLP gRPC :4317
     ▼
   ┌──────────────────────┐
   │ otel-collector       │── traces ──▶  jaeger
   │  receivers: otlp,    │── metrics ─▶  prom-exporter :8889 ──▶ prometheus
   │             prom     │── logs ────▶  loki
   │  exporters: jaeger,  │
   │             prom,    │── self ────▶  prom-exporter (own metrics on :8888)
   │             loki     │
   └──────────────────────┘
                                                 ▲
                                                 │ datasources
                                                 │
                                              grafana
                                                 │
                                              promtail (DaemonSet) ──▶ loki
```

Workloads inside the cluster reach the collector at
`otel-collector.otel-stack.svc.cluster.local:4317` (gRPC) or `:4318`
(HTTP). For the rcs-etcd testbed, each OpenSearch pod runs a sidecar
collector that listens on `localhost:4317` and forwards here (see
`k8s/common/otel-sidecar.yaml` for the why).

## What's supported

- **OTLP ingest** for traces, metrics, and logs over gRPC and HTTP.
- **Prometheus scrape** of every component's `/metrics` endpoint plus
  the collector's `:8889` re-exposition of OTLP-sourced metrics.
- **Loki log aggregation** for any pod labelled `logs=collect` (set
  the label on a pod template to opt in — Promtail filters by it).
- **Grafana dashboards** loaded via the provisioning ConfigMap with
  one provider per file-system path; add a dashboard by mounting a new
  ConfigMap and adding a provider entry. The bundled Infrastructure
  dashboard is generic otelcol pipeline health.

## What's deliberately not supported

- **No Alertmanager / alerting rules.** This is a dev/staging stack;
  alerts belong in the production o11y plane.
- **No multi-tenancy on Loki or Prometheus.** Single-tenant defaults.
- **No persistent traces (Jaeger all-in-one is memory-only).**
- **No TLS between components or to clients.** In-cluster only.
- **No exponential-histogram support at the Prometheus exporter
  boundary.** A known v0.95.0 contrib limitation. Counters and gauges
  surface; histograms reach the collector but not Prometheus. Upgrade
  the collector image to surface them.

## Versions and upgrade discipline

Image tags are pinned to specific patch versions in each manifest.
Upgrading is a deliberate act: bump the tag, redeploy, verify, commit.
There is no automatic patching and no transitive tag like `:latest`.

## Endpoints (when using the kind cluster's port mappings)

| Host | What |
|------|------|
| `http://localhost:3000/` | Grafana (admin/admin) |
| `http://localhost:9090/` | Prometheus |
| `http://localhost:16686/` | Jaeger UI |

Collector OTLP receivers are in-cluster only — workloads connect via the
in-cluster Service name, not the host network.

## Bringing it up

```sh
make otel-stack   # apply everything; wait for the four Deployments
```

The target is idempotent: re-running picks up manifest edits and
triggers rollouts as needed. To wipe state, `kubectl delete namespace
otel-stack` and re-apply.
