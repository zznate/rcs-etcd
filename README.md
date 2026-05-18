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

- **Not a snapshot repository.** The `BlobContainer` methods snapshots rely on
  (notably `writeBlobAtomic`) throw `UnsupportedOperationException`. Use a separate
  repository — typically `repository-s3` — for snapshots.

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

```sh
./gradlew :plugin:test    # unit tests, mocked jetcd KV, no Docker
```

The unit suite covers the BlobContainer methods RCS exercises (write/read/list/delete,
sorted prefix scan, oversize rejection) and the multi-tenant key invariant.

A single-node OpenSearch + dockerCompose etcd integration suite is planned under
`./gradlew :plugin:integrationTest`; it is not yet wired up.

Code-quality gates (`dependencyLicenses`, `thirdPartyAudit`, Checkstyle, PMD) are
disabled in `plugin/build.gradle`.

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
