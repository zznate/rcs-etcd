/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import org.opensearch.telemetry.metrics.Counter;
import org.opensearch.telemetry.metrics.Histogram;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.telemetry.metrics.noop.NoopMetricsRegistry;

/**
 * Telemetry instruments emitted by the plugin. One instance per node, created from the
 * {@link MetricsRegistry} supplied by OpenSearch's telemetry SPI (via
 * {@code TelemetryAwarePlugin.createComponents(...)}). A {@link #NOOP} fallback exists so
 * call sites (and unit tests) never need to null-check.
 */
public final class EtcdRepositoryMetrics {

    private static final String UNIT_COUNT = "1";
    private static final String UNIT_MS = "ms";

    /** Safe fallback used until {@code createComponents} swaps in a real registry. */
    public static final EtcdRepositoryMetrics NOOP = new EtcdRepositoryMetrics(NoopMetricsRegistry.INSTANCE);

    private final Counter writes;
    private final Histogram writeDuration;
    private final Counter reads;
    private final Histogram readDuration;
    private final Counter lists;
    private final Histogram listDuration;
    private final Counter oversizeRejections;
    private final Counter putIfAbsentRejections;
    private final Counter manifestPublishes;

    public EtcdRepositoryMetrics(MetricsRegistry registry) {
        this.writes = registry.createCounter(
            "rcs_etcd.blob.write.total",
            "Total writeBlob invocations against the etcd-backed BlobContainer",
            UNIT_COUNT
        );
        this.writeDuration = registry.createHistogram(
            "rcs_etcd.blob.write.duration",
            "Duration of writeBlob invocations (success or failure)",
            UNIT_MS
        );
        this.reads = registry.createCounter(
            "rcs_etcd.blob.read.total",
            "Total readBlob invocations against the etcd-backed BlobContainer",
            UNIT_COUNT
        );
        this.readDuration = registry.createHistogram(
            "rcs_etcd.blob.read.duration",
            "Duration of readBlob invocations (success or failure)",
            UNIT_MS
        );
        this.lists = registry.createCounter(
            "rcs_etcd.blob.list.total",
            "Total listBlobsByPrefix invocations (sorted and unsorted)",
            UNIT_COUNT
        );
        this.listDuration = registry.createHistogram(
            "rcs_etcd.blob.list.duration",
            "Duration of listBlobsByPrefix invocations (success or failure)",
            UNIT_MS
        );
        this.oversizeRejections = registry.createCounter(
            "rcs_etcd.blob.oversize_rejection.total",
            "Writes rejected because the payload exceeded cluster.rcs_etcd.max_request_bytes",
            UNIT_COUNT
        );
        this.putIfAbsentRejections = registry.createCounter(
            "rcs_etcd.blob.put_if_absent_rejection.total",
            "writeBlob(failIfAlreadyExists=true) rejections from the etcd transaction (key already present)",
            UNIT_COUNT
        );
        this.manifestPublishes = registry.createCounter(
            "rcs_etcd.manifest.publish.total",
            "Writes whose blob name begins with 'manifest__', i.e. RCS manifest blobs",
            UNIT_COUNT
        );
    }

    void recordWrite(long durationNanos) {
        writes.add(1);
        writeDuration.record(toMillis(durationNanos));
    }

    void recordRead(long durationNanos) {
        reads.add(1);
        readDuration.record(toMillis(durationNanos));
    }

    void recordList(long durationNanos) {
        lists.add(1);
        listDuration.record(toMillis(durationNanos));
    }

    void recordOversizeRejection() {
        oversizeRejections.add(1);
    }

    void recordPutIfAbsentRejection() {
        putIfAbsentRejections.add(1);
    }

    void recordManifestPublish() {
        manifestPublishes.add(1);
    }

    private static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
