#!/usr/bin/env bash
#
# Demo verification: exercise the rcs-etcd plugin end-to-end against a running
# stack ('make up' first). Creates an index, indexes a document, and confirms
# the resulting cluster state publish landed a manifest__* key in etcd.

set -euo pipefail
cd "$(dirname "$0")"

OS_URL="${OS_URL:-http://localhost:9200}"
ROOT_PREFIX="${ROOT_PREFIX:-/opensearch-rcs}"
INDEX_NAME="${INDEX_NAME:-demo-index}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-rcs-etcd-demo}"
COMPOSE="docker-compose -p ${COMPOSE_PROJECT}"

# 1. Wait for the cluster to be green (or yellow on the very first publish).
echo "==> Waiting for OpenSearch cluster to be ready..."
until curl -sf "${OS_URL}/_cluster/health?wait_for_status=yellow&timeout=60s" >/dev/null; do
    sleep 2
done

# 2. Snapshot etcd keys before the cluster state change.
echo "==> Capturing etcd key snapshot (before)"
before_keys="$(${COMPOSE} exec -T etcd etcdctl get --prefix --keys-only "${ROOT_PREFIX}/" | sort)"

# 3. Create the index with a small mapping.
echo "==> Creating index ${INDEX_NAME}"
curl -sf -X PUT "${OS_URL}/${INDEX_NAME}" \
    -H 'Content-Type: application/json' \
    -d '{
        "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
        "mappings": {
            "properties": {
                "title":      { "type": "text" },
                "created_at": { "type": "date" }
            }
        }
    }' >/dev/null

# 4. Index a sample document.
echo "==> Indexing a sample document"
curl -sf -X POST "${OS_URL}/${INDEX_NAME}/_doc?refresh=wait_for" \
    -H 'Content-Type: application/json' \
    -d "{\"title\":\"hello from rcs-etcd demo\",\"created_at\":\"$(date -u +%FT%TZ)\"}" >/dev/null

# 5. Give RCS a moment to publish the cluster state change. RCS writes are
# manager-side and async with respect to the index admin REST response.
sleep 3

# 6. Snapshot etcd keys after, diff, look for new manifest__* keys.
echo "==> Capturing etcd key snapshot (after)"
after_keys="$(${COMPOSE} exec -T etcd etcdctl get --prefix --keys-only "${ROOT_PREFIX}/" | sort)"

new_keys="$(comm -13 <(echo "${before_keys}") <(echo "${after_keys}"))"

if [[ -z "${new_keys}" ]]; then
    echo
    echo "FAIL: no new etcd keys appeared after index creation."
    echo "Current etcd contents under ${ROOT_PREFIX}/ :"
    echo "${after_keys}" | sed 's/^/    /'
    exit 1
fi

manifest_keys="$(echo "${new_keys}" | grep -E '/manifest__' || true)"

if [[ -z "${manifest_keys}" ]]; then
    echo
    echo "FAIL: cluster state changed but no new manifest__* key was written."
    echo "New keys observed:"
    echo "${new_keys}" | sed 's/^/    /'
    exit 1
fi

echo
echo "PASS: a new cluster-state manifest landed in etcd."
echo
echo "New manifest key(s) (latest first by RCS's inverted-timestamp encoding):"
echo "${manifest_keys}" | sort | head -3 | sed 's/^/    /'
echo
echo "Total new etcd keys under ${ROOT_PREFIX}/ : $(echo "${new_keys}" | wc -l | tr -d ' ')"
