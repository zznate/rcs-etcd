# rcs-etcd

An OpenSearch plugin that registers an [etcd][etcd]-backed `Repository` for use as
the storage backend behind OpenSearch's [Remote Cluster State][rcs] (RCS) feature.

## What it is

- A `Repository` implementation (`type: "etcd"`) that maps the `BlobContainer` SPI
  onto an etcd KV store.
- Multi-tenant by design: every key is namespaced under
  `<root_prefix>/<cluster_name>/...` so multiple OpenSearch clusters can share an
  etcd ensemble without colliding.

## What it is not

- **Not a snapshot repository.** The `BlobContainer` surface is implemented in full
  (including the atomic-write probe OpenSearch runs on repository registration), but
  the snapshot-specific paths in `BlobStoreRepository` (e.g. shard-data uploads,
  segment files) have not been validated against etcd and are not part of the
  supported surface. Use a separate repository — typically `repository-s3` — for
  snapshots.

## Compatibility

| Surface                | Version                                       |
| ---------------------- | --------------------------------------------- |
| OpenSearch (Maven)     | `org.opensearch:opensearch:3.0.0`             |
| Deployment image tag   | any image whose base is `opensearchproject/opensearch:3.0.0` (including downstream re-tags such as `3.0.0.2`) |
| JDK                    | 21+                                           |
| Gradle (build-time)    | 8.14                                          |
| etcd                   | 3.5.x (server)                                |
| jetcd                  | 0.8.6                                         |

The plugin is compiled against upstream OpenSearch `3.0.0` from Maven Central and is
binary-compatible with any deployment image whose underlying OpenSearch JARs are
`3.0.0`.

## Configuration

All settings are node-scoped (declared via `Plugin.getSettings()`).

| Setting                              | Default                 | Meaning                                                                                                                                                                  |
| ------------------------------------ | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `cluster.rcs_etcd.endpoints`         | `["http://localhost:2379"]` | etcd endpoints the plugin connects to.                                                                                                                                |
| `cluster.rcs_etcd.root_prefix`       | `/opensearch-rcs`       | Root prefix prepended to every key. The cluster name is appended underneath it (see [docs/key-layout.md](docs/key-layout.md)). Trailing slashes are normalised away.   |
| `cluster.rcs_etcd.max_request_bytes` | `1536kb` (1.5 MiB)      | Hard ceiling on per-blob payload size. Blobs above this size are rejected with a descriptive `IOException` (no silent truncation, no auto-chunking). Should match the etcd cluster's `--max-request-bytes` flag. |

Repository registration is otherwise the standard snapshot-repository API:

```http
PUT _snapshot/etcd-rcs
{
  "type": "etcd"
}
```

## Metrics

The plugin instruments every `BlobContainer` entry point through OpenSearch's
telemetry SPI (`libs/telemetry`). Operators install the
[`telemetry-otel`](https://docs.opensearch.org/latest/observing-your-data/trace/) plugin
and configure an OTLP endpoint to ship these instruments to a collector / backend
(Prometheus via OTel collector, Tempo, Jaeger, etc.); without that plugin installed,
the instruments flow into OpenSearch's no-op telemetry implementation and are silently
discarded. All names live under the `rcs_etcd.*` namespace.

| Name                                            | Type      | Unit | Fires on                                                          |
| ----------------------------------------------- | --------- | ---- | ----------------------------------------------------------------- |
| `rcs_etcd.blob.write.{total,duration}`          | counter / histogram | 1 / ms | Every `writeBlob` invocation (success or failure).      |
| `rcs_etcd.blob.read.{total,duration}`           | counter / histogram | 1 / ms | Every `readBlob` invocation.                            |
| `rcs_etcd.blob.list.{total,duration}`           | counter / histogram | 1 / ms | Every `listBlobsByPrefix` and `listBlobsByPrefixInSortedOrder`. |
| `rcs_etcd.blob.oversize_rejection.total`        | counter   | 1    | Writes rejected because payload exceeded `max_request_bytes`.     |
| `rcs_etcd.blob.put_if_absent_rejection.total`   | counter   | 1    | `writeBlob(failIfAlreadyExists=true)` etcd-txn rejections.        |
| `rcs_etcd.manifest.publish.total`               | counter   | 1    | Successful writes whose blob name starts with `manifest__`.       |

Latency histograms include both success and failure paths (timing is captured in a
`finally` block), so rates derived from `*.total` and percentiles from `*.duration`
share the same denominator. The `.total` suffix follows Prometheus convention so the
names round-trip cleanly through `otel-collector`.

## Building

Prerequisites:

- JDK 21 or newer (`java -version` must report `21` or higher).
- Docker, or a Docker-API-compatible shim (e.g. `podman`), for the integration tests.

```sh
./gradlew :plugin:bundlePlugin
```

The plugin zip lands in `plugin/build/distributions/rcs-etcd-3.0.0.0.zip`.

To install into a local dev OpenSearch:

```sh
$OPENSEARCH_HOME/bin/opensearch-plugin install \
    file:///$PWD/plugin/build/distributions/rcs-etcd-3.0.0.0.zip
```

## Verification

Two test layers, each its own Gradle task:

```sh
./gradlew :plugin:test              # unit tests, mocked jetcd KV, no Docker
./gradlew :plugin:integrationTest   # single-node OpenSearch + etcd via docker-compose
```

**Unit suite** exercises every `BlobContainer` method RCS uses
(write/read/list/delete, sorted prefix scan, oversize rejection) against a mocked
jetcd `KV` and verifies the multi-tenant key invariant.

**Integration suite** brings up a single etcd 3.5.x container via
`docker/docker-compose-test.yml` and runs:

- `EtcdBlobContainerIT` — exercises `EtcdBlobContainer` directly against the
  real etcd: write/read round-trip, `listBlobsByPrefix`, native ascending-key
  sorted list with limit, `delete` with accurate `DeleteResult` byte counts,
  `deleteBlobsIgnoringIfNotExists` tolerance for missing keys, cross-cluster
  isolation between two `EtcdBlobStore` instances sharing the etcd ensemble,
  oversize-blob rejection, and txn-based `failIfAlreadyExists`.
- `EtcdRepositoryPluginIT` — loads `EtcdRepositoryPlugin` into a single-node
  OpenSearch JVM via `OpenSearchSingleNodeTestCase` and asserts the etcd
  repository registers cleanly through the cluster-admin
  `PUT _snapshot/<name>` / `GET _snapshot/<name>` round-trip (which triggers
  `BlobStoreRepository.startVerification`'s writability probe — the same path
  any production operator hits when registering the repository).

The Gradle build shells out to the standalone `docker-compose` (v1) binary
rather than `docker compose` (v2), so a working `docker-compose` on `PATH`
plus a reachable Docker daemon (or a Docker-API-compatible shim such as
podman) are the only requirements. The `integrationTest` task depends on
`composeUp` and is finalized by `composeDown`, so the etcd container lifecycle
is handled automatically.

Code-quality gates (`dependencyLicenses`, `thirdPartyAudit`, Checkstyle, PMD,
`forbiddenApis`) are disabled in `plugin/build.gradle`.

## Multi-node failover

Multi-node failover ("kill the elected cluster_manager, verify a new one bootstraps
state from etcd") is verified manually against a real deployment — see
[docs/failover.md](docs/failover.md).

## Key layout

Every key follows `<root_prefix>/<cluster_name>/<container-path-joined-with-/>/<blob_name>`.
The full layout and rationale, including the design sketch for transparent blob
chunking (not currently implemented), live in [docs/key-layout.md](docs/key-layout.md).

## License

Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE.txt](NOTICE.txt).

[etcd]: https://etcd.io/
[rcs]:  https://docs.opensearch.org/latest/tuning-your-cluster/availability-and-recovery/remote-store/remote-cluster-state/
