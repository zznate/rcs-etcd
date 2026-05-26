# Param sources for the rcs-etcd-state-ops workload. Each instance
# generates `burst-N` index names from a counter local to the
# partition. The counter wraps at `count`, so iterating the same
# operation 50 times produces burst-0 .. burst-49.

import threading


def register(registry):
    registry.register_param_source("burst-index-params", BurstIndexCreateParams)
    registry.register_param_source("burst-index-delete", BurstIndexDeleteParams)


class _BurstBase:
    def __init__(self, workload, params, **kwargs):
        self._prefix = params.get("prefix", "burst")
        self._count = int(params.get("count", 50))
        self._counter = 0
        self._lock = threading.Lock()

    def partition(self, partition_index, total_partitions):
        return self

    def _next_index(self):
        with self._lock:
            i = self._counter % self._count
            self._counter += 1
        return f"{self._prefix}-{i}"


class BurstIndexCreateParams(_BurstBase):
    def params(self):
        body = {"settings": {"number_of_shards": 1, "number_of_replicas": 0}}
        return {"indices": [[self._next_index(), body]]}


class BurstIndexDeleteParams(_BurstBase):
    def params(self):
        return {"indices": [self._next_index()]}
