#!/usr/bin/env bash
# Bring up either configuration into the rcs-etcd-poc namespace,
# wait for cluster green, and capture time-to-green plus (for
# rcs-etcd) time-to-first-publish onto the opensearch-config
# ConfigMap as annotations. The smoke script reads them back.

set -euo pipefail

config="${1:?usage: bring-up.sh <vanilla|rcs-etcd>}"
case "$config" in
  vanilla|rcs-etcd) ;;
  *) echo "config must be vanilla or rcs-etcd" >&2; exit 1 ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NAMESPACE=rcs-etcd-poc

echo "==> applying common + $config manifests"
t0=$(date +%s)
kubectl apply -f "$SCRIPT_DIR/common/" -f "$SCRIPT_DIR/$config/"

echo "==> waiting for opensearch-0/1/2 ready"
for p in opensearch-0 opensearch-1 opensearch-2; do
  kubectl wait --for=condition=ready --timeout=420s "pod/$p" -n "$NAMESPACE"
done
t1=$(date +%s)
time_to_green=$((t1 - t0))
echo "METRIC time_to_green_seconds=$time_to_green"

kubectl annotate configmap opensearch-config -n "$NAMESPACE" \
  --overwrite "testbed.rcs-etcd/time-to-green-seconds=$time_to_green" >/dev/null

if [ "$config" = "rcs-etcd" ]; then
  echo "==> waiting for first manifest__ key in etcd"
  until kubectl exec -n "$NAMESPACE" etcd-0 -- \
        etcdctl get /opensearch-rcs/ --prefix --keys-only 2>/dev/null \
        | grep -q manifest__; do
    sleep 1
  done
  t2=$(date +%s)
  time_to_first_publish=$((t2 - t0))
  echo "METRIC time_to_first_publish_seconds=$time_to_first_publish"
  kubectl annotate configmap opensearch-config -n "$NAMESPACE" \
    --overwrite "testbed.rcs-etcd/time-to-first-publish-seconds=$time_to_first_publish" >/dev/null
fi

cat <<EOF

Cluster ready. Host endpoints:
  OpenSearch REST:  http://localhost:9200/
  Grafana:          http://localhost:3000/  (admin/admin)
  Prometheus:       http://localhost:9090/
  Jaeger UI:        http://localhost:16686/
  etcd client API:  http://localhost:2379/
EOF
