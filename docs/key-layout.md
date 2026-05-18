# Etcd key layout

Every key the plugin writes follows the same shape:

```
<root_prefix>/<cluster_name>/<container-path-joined-with-/>/<blob_name>
```

- `<root_prefix>` is the value of the `cluster.rcs_etcd.root_prefix` setting
  (default `/opensearch-rcs`). Trailing slashes are normalised away.
- `<cluster_name>` is read from `ClusterService.getClusterName().value()` at
  repository construction and cached for the lifetime of the node process.
- `<container-path-joined-with-/>` is the `BlobPath` the caller asked the
  `BlobStore` for, joined with `/`. RCS uses this segment to model directories
  (e.g. `cluster-state/manifests`).
- `<blob_name>` is the leaf object name (e.g. `manifest__<term>__<state-version>__C|P__<inverted-timestamp>__<codec>`).

## Worked example

For a cluster named `cluster-a`, the default root prefix, and a manifest written
by RCS to the `cluster-state/manifests` container, the etcd key is:

```
/opensearch-rcs/cluster-a/cluster-state/manifests/manifest__<...>
```

RCS may interpose additional path segments between the cluster prefix and the
manifest filename (driven by `cluster.remote_store.state.path.prefix` and the
`HASHED_PREFIX` / `HASHED_INFIX` path types in OpenSearch core). The plugin
preserves whatever `BlobPath` it receives — it does not strip or rewrite
intermediate segments.

## The multi-tenant invariant

This prefix shape is non-negotiable. The plugin treats etcd as a shared resource
even when a single cluster is using it:

- **No global keys.** Every operation is scoped under the cluster's prefix.
- **List operations are prefix-scoped.** `listBlobsByPrefix` only ever scans
  within the current cluster's namespace; it cannot see other clusters' blobs.
- **Adding clusters is a deployment concern, not a code concern.** Pointing a
  second OpenSearch cluster at the same etcd ensemble adds keys under its own
  `<cluster_name>` segment without any plugin change.

Operators should configure etcd RBAC to grant each cluster access only to its
own `<root_prefix>/<cluster_name>/...` subtree. The plugin does not manage etcd
users itself; that is a deployment-side hardening step.

## Oversize blobs

etcd enforces a per-request size limit via the `--max-request-bytes` flag
(default 1.5 MiB; commonly raised to a few MiB). The plugin mirrors this limit
in the `cluster.rcs_etcd.max_request_bytes` setting and rejects any blob that
exceeds it. The thrown `IOException` carries the blob name, the attempted size
in bytes, and the configured limit, so the failure is immediately diagnosable
without trawling the etcd server logs.

Blob chunking is not implemented. If oversize blobs become a recurring concern,
the intended approach is sketched below.

## Chunking design (not implemented)

When a single blob exceeds the configured `max_request_bytes`, the plan is to
split it transparently inside `EtcdBlobContainer` rather than failing the write:

- **N chunk keys** carry the raw bytes at `<blob_key>/chunk/<n>` where `n` is a
  zero-padded ordinal.
- **One manifest key** at the original blob key carries a small JSON value:
  `{ "chunkCount": N, "totalSize": <bytes>, "checksum": <hex> }`.
- **Writer order matters:** chunks are written first, the manifest last. A
  half-written blob is invisible to readers because there is no manifest yet.
- **Reader logic:** fetch the blob key; if its value parses as a chunk manifest,
  fetch the N chunks and concatenate; otherwise treat the value as a single-key
  blob payload (the common case).
- **Single-key writes do not change.** Blobs that fit inside
  `max_request_bytes` continue to be written as one key, so the existing key
  layout is preserved end-to-end.

This is a forward-compatible extension: adding it does not require changing
how RCS uses the BlobContainer API.
