# rcs-etcd demo stack

A self-contained `docker-compose` stack that exercises the `rcs-etcd` plugin
end-to-end: a single etcd container, a single OpenSearch node with the plugin
installed and Remote Cluster State (RCS) enabled, plus a verification script
that creates an index and confirms the resulting cluster-state publish landed
in etcd.

## Scope

**This demo enables cluster-state RCS only.** Shard-segment remote store, segment
replication, and snapshot repositories are not configured. The plugin itself is
RCS-only; verifying anything beyond the cluster-state path is out of scope here
(see the top-level [README.md](../README.md) for the broader stance).

## Prerequisites

- JDK 21 on `PATH` (needed to build the plugin zip).
- `docker-compose` (v1 binary on `PATH`) plus a reachable Docker daemon, or a
  Docker-API-compatible shim such as podman.
- Ports `2379` (etcd) and `9200` (OpenSearch) free on `localhost`.

## Quick start

```sh
make build    # build the plugin zip and the opensearch image
make up       # start the stack in the background
make verify   # exercise it end-to-end (PASS / FAIL)
make down     # tear down containers and volumes
```

`make` (or `make help`) lists every target.

## What `make verify` actually checks

1. Waits for the OpenSearch cluster to reach `yellow` status.
2. Snapshots the etcd keyspace under `/opensearch-rcs/` (the configured
   `root_prefix`).
3. Creates an index `demo-index` with a tiny mapping.
4. Indexes one sample document.
5. Snapshots the etcd keyspace again, diffs the two snapshots, and asserts
   that a new `manifest__*` key has appeared. That key is the cluster-state
   manifest RCS publishes whenever the cluster state changes; its presence
   under `/opensearch-rcs/<cluster_name>/...` is direct evidence that the
   plugin wired cluster state into etcd.

The script prints the new manifest key(s) on success so you can poke at them
with `docker-compose -p rcs-etcd-demo exec etcd etcdctl get <key>` afterwards.

## Inspecting a value

### Byte size of a value

```sh
# exact: decode the base64 value and count bytes
docker-compose -p rcs-etcd-demo exec -T etcd etcdctl get <key> -w json \
  | jq -r '.kvs[0].value' | base64 -d | wc -c

# whole-DB size on disk (see the DB SIZE column)
docker-compose -p rcs-etcd-demo exec -T etcd etcdctl endpoint status -w table
```

`jq` and `base64` run on the host side of the pipe, so they don't need to exist
in the (minimal) etcd image — only `etcdctl` runs in the container.

### Best-effort decode of a value

RCS blobs are a Lucene codec header wrapping a [SMILE](https://github.com/FasterXML/smile-format-specification)
document (LZ4-compressed above a size threshold). A full decode needs OpenSearch's
`ChecksumBlobStoreFormat`, but `xxd` / `strings` surface the structure for free:

```sh
# hex dump: Lucene magic 3fd76c17, then the codec name, then SMILE header (3a 29 0a = ":)\n")
docker-compose -p rcs-etcd-demo exec -T etcd etcdctl get <key> --print-value-only | xxd | head

# readable fragments: codec name, index name, SMILE field names
docker-compose -p rcs-etcd-demo exec -T etcd etcdctl get <key> --print-value-only | strings | head
```

For a small index-metadata blob the `strings` output reads almost like a schema —
`index-metadata`, the index name, then `version`, `mapping_version`,
`settings_version`, `state`, `settings`, `index.auto_expand_replicas`, and so on.
Larger blobs are LZ4-compressed, so only the codec header and the leading SMILE
bytes stay readable.

## Configuration the stack pins, and where it lives

| Concern | Where |
| --- | --- |
| etcd version | `docker-compose.yml` (`quay.io/coreos/etcd:v3.5.21`) |
| OpenSearch base image | `Dockerfile` (`opensearchproject/opensearch:3.0.0`) |
| Plugin install | `Dockerfile` (`opensearch-plugin install file:///tmp/...`) |
| RCS enablement + system-repo bootstrap | `docker-compose.yml` (env vars on the `opensearch` service) |
| Plugin endpoints / root_prefix | `docker-compose.yml` (env vars: `cluster.rcs_etcd.*`) |

Override any of these at runtime by editing the compose file directly — there
is no `.env` indirection. The defaults are chosen to "just work" out of the box.

## How this differs from `./gradlew :plugin:integrationTest`

The Gradle `integrationTest` task spins up OpenSearch in-JVM via
`OpenSearchSingleNodeTestCase` and asserts plugin loading + repository
registration. It does **not** publish real cluster state — RCS enablement under
`OpenSearchSingleNodeTestCase` requires bootstrap-time machinery the published
test framework doesn't expose ergonomically.

This demo runs an actual OpenSearch process with RCS turned on, so it
exercises the path that `OpenSearchSingleNodeTestCase` skips: the publish loop
landing a manifest blob in etcd after a real cluster-state change. The two
gates are complementary, not redundant.
