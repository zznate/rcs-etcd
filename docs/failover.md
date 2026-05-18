# Manual failover verification

This procedure verifies that a freshly-elected OpenSearch cluster_manager pod
can bootstrap its cluster state from the etcd-backed `Repository` after the
previously-elected pod is removed. It is run by hand against a real
deployment — it is not part of the automated test suite.

## Goal

After killing the elected cluster_manager pod, a successor cluster_manager
- joins the cluster within an SLA window (default: under 60 seconds),
- reads the most recent cluster-state manifest from etcd,
- applies it cleanly (no `ERROR` log lines on the read path),
- and reports cluster health back to `green`.

## Preconditions

- An OpenSearch cluster running with three cluster_manager pods, configured to
  use the etcd `Repository` as its RCS backend.
- An etcd ensemble reachable from every cluster_manager pod via the configured
  `cluster.rcs_etcd.endpoints`.
- `etcdctl` available on a workstation that can reach the etcd ensemble.
- `kubectl` access to the cluster.

## Procedure

1. **Capture the baseline.** Note the currently-elected cluster_manager pod:

   ```sh
   kubectl exec -n <namespace> <any-pod> -- curl -s localhost:9200/_cat/master
   ```

   Record the pod name as `$ELECTED`.

2. **Snapshot the etcd key shape.** Confirm a manifest key exists under the
   cluster's prefix:

   ```sh
   etcdctl --endpoints=$ETCD_ENDPOINTS \
       get --prefix --keys-only /opensearch-rcs/<cluster_name>/ \
       | grep -E 'manifest__'
   ```

   Record the highest-sorting (lexicographically smallest, because of RCS's
   inverted-timestamp encoding) manifest key as `$BASELINE_MANIFEST`.

3. **Kill the elected pod.**

   ```sh
   kubectl delete pod -n <namespace> $ELECTED
   ```

   Start a stopwatch.

4. **Watch for a new election.** Poll `_cat/master` every five seconds until
   it returns a pod name different from `$ELECTED`. Record the elapsed time as
   `$T_ELECT`.

   ```sh
   while true; do
       kubectl exec -n <namespace> <any-pod> -- curl -s localhost:9200/_cat/master
       sleep 5
   done
   ```

5. **Verify the new cluster_manager read state from etcd.** Tail the new
   cluster_manager's logs for the RCS read path and confirm no errors:

   ```sh
   kubectl logs -n <namespace> <new-elected-pod> \
       | grep -E 'remote.*cluster.*state|RemoteClusterState' \
       | grep -Ev 'INFO|DEBUG'
   ```

   The expected output is empty (no `WARN` or `ERROR` lines). If anything
   appears, capture it for the failure report.

6. **Confirm cluster health.** Poll until green:

   ```sh
   kubectl exec -n <namespace> <any-pod> -- curl -s localhost:9200/_cluster/health
   ```

   Record the elapsed time from step 3 as `$T_GREEN`.

7. **Cleanup.** No manual cleanup needed; the killed pod is recreated by the
   StatefulSet controller. Verify the cluster is back to three healthy
   cluster_manager pods:

   ```sh
   kubectl get pods -n <namespace> -l role=cluster_manager
   ```

## Pass criteria

- `$T_ELECT < 30s` — a new cluster_manager is elected within thirty seconds of
  the kill.
- `$T_GREEN < 60s` — cluster health returns to `green` within one minute.
- Step 5 produces no `WARN` or `ERROR` lines on the RCS read path.
- Step 2 re-run after the procedure shows a new manifest key
  lexicographically below `$BASELINE_MANIFEST` (RCS writes a fresh manifest
  on election).

## Failure-mode notes

- **Long `$T_ELECT`** without progress in the candidate's logs usually points
  at network reachability to etcd. Confirm with
  `etcdctl --endpoints=$ETCD_ENDPOINTS endpoint health` from inside the new
  pod's network namespace.
- **No new manifest written after election** suggests the new
  cluster_manager could read but not write. Check etcd's auth/RBAC scoping
  against `<root_prefix>/<cluster_name>/...`.
- **Errors on the RCS read path** before any successful read suggest a stale
  or partially-written manifest. Inspect the highest manifest key
  with `etcdctl get --hex` to see whether the value is a valid SMILE-encoded
  manifest or truncated.
