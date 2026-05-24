#!/usr/bin/env bash
# Apply the rcs-etcd dashboards into the existing stepflow-o11y
# Grafana. Idempotent: re-running is safe and only the dashboard
# ConfigMap content changes between runs.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

echo "==> applying dashboard ConfigMap"
kubectl apply -f "$ROOT_DIR/k8s/dashboards/configmap-dashboard-rcs-etcd.yaml"

echo "==> replacing provisioning ConfigMap (adds rcs-etcd provider)"
kubectl apply -f "$SCRIPT_DIR/grafana-provisioning-patch.yaml"

echo "==> patching Grafana deployment (volume + mount)"
kubectl patch deployment grafana -n stepflow-o11y \
  --patch-file="$SCRIPT_DIR/grafana-deployment-patch.yaml"

echo "==> rolling restart Grafana"
kubectl rollout restart deployment/grafana -n stepflow-o11y
kubectl rollout status deployment/grafana -n stepflow-o11y --timeout=120s

echo
echo "Dashboards available at http://localhost:3000/ (admin/admin) under the 'rcs-etcd' folder."
