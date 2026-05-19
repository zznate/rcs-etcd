/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Txn;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.PutResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.GetOption;

import org.junit.Before;
import org.junit.Test;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.telemetry.metrics.Counter;
import org.opensearch.telemetry.metrics.Histogram;
import org.opensearch.telemetry.metrics.MetricsRegistry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asserts the BlobContainer instruments declared in {@link EtcdRepositoryMetrics} actually
 * fire on the expected code paths. Uses a {@link MetricsRegistry} mock that hands out a
 * distinct {@link Counter} / {@link Histogram} mock per metric name so per-instrument
 * assertions can target each one.
 */
public class EtcdBlobContainerMetricsTests {

    private static final BlobPath CONTAINER_PATH = BlobPath.cleanPath().add("cluster-state");
    private static final String CONTAINER_KEY_PREFIX = "/opensearch-rcs/cluster-a/cluster-state/";
    private static final long MAX_REQUEST_BYTES = 1024L;

    private KV kv;
    private EtcdBlobContainer container;

    // Per-name instrument mocks captured via the MetricsRegistry mock so each test
    // can verify behaviour against the exact instrument it expects to fire.
    private final Map<String, Counter> counters = new HashMap<>();
    private final Map<String, Histogram> histograms = new HashMap<>();

    @Before
    public void setUp() {
        kv = mock(KV.class);
        MetricsRegistry registry = mock(MetricsRegistry.class);
        when(registry.createCounter(any(String.class), any(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            Counter c = mock(Counter.class);
            counters.put(name, c);
            return c;
        });
        when(registry.createHistogram(any(String.class), any(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            Histogram h = mock(Histogram.class);
            histograms.put(name, h);
            return h;
        });

        EtcdRepositoryMetrics metrics = new EtcdRepositoryMetrics(registry);
        ByteSequence keyPrefix = ByteSequence.from(CONTAINER_KEY_PREFIX, StandardCharsets.UTF_8);
        container = new EtcdBlobContainer(kv, CONTAINER_PATH, keyPrefix, MAX_REQUEST_BYTES, metrics);
    }

    @Test
    public void allInstrumentsAreRegistered() {
        assertThat(counters).containsKeys(
            "rcs_etcd.blob.write.total",
            "rcs_etcd.blob.read.total",
            "rcs_etcd.blob.list.total",
            "rcs_etcd.blob.oversize_rejection.total",
            "rcs_etcd.blob.put_if_absent_rejection.total",
            "rcs_etcd.manifest.publish.total"
        );
        assertThat(histograms).containsKeys(
            "rcs_etcd.blob.write.duration",
            "rcs_etcd.blob.read.duration",
            "rcs_etcd.blob.list.duration"
        );
    }

    @Test
    public void writeBlobRecordsCountAndDuration() throws IOException {
        when(kv.put(any(), any())).thenReturn(CompletableFuture.completedFuture(mock(PutResponse.class)));

        container.writeBlob("non-manifest-blob", inputOf("hello"), 5, false);

        verify(counters.get("rcs_etcd.blob.write.total")).add(1.0);
        verify(histograms.get("rcs_etcd.blob.write.duration")).record(anyDouble());
        verify(counters.get("rcs_etcd.manifest.publish.total"), never()).add(anyDouble());
    }

    @Test
    public void manifestWriteIncrementsManifestPublishCounter() throws IOException {
        when(kv.put(any(), any())).thenReturn(CompletableFuture.completedFuture(mock(PutResponse.class)));

        container.writeBlob("manifest__some-id", inputOf("payload"), 7, false);

        verify(counters.get("rcs_etcd.manifest.publish.total")).add(1.0);
        verify(counters.get("rcs_etcd.blob.write.total")).add(1.0);
    }

    @Test
    public void oversizeRejectionIncrementsCounterAndStillRecordsWriteDuration() {
        byte[] big = new byte[(int) MAX_REQUEST_BYTES + 1];

        assertThatThrownBy(() -> container.writeBlob("too-big", new ByteArrayInputStream(big), big.length, false))
            .isInstanceOf(IOException.class);

        verify(counters.get("rcs_etcd.blob.oversize_rejection.total")).add(1.0);
        verify(counters.get("rcs_etcd.blob.write.total")).add(1.0);
        verify(histograms.get("rcs_etcd.blob.write.duration")).record(anyDouble());
        verify(counters.get("rcs_etcd.manifest.publish.total"), never()).add(anyDouble());
    }

    @Test
    public void txnRejectionIncrementsPutIfAbsentRejectionCounter() {
        TxnResponse txnResponse = mock(TxnResponse.class);
        when(txnResponse.isSucceeded()).thenReturn(false);
        Txn txn = mock(Txn.class);
        when(txn.If(any(Cmp.class))).thenReturn(txn);
        when(txn.Then(any(Op.class))).thenReturn(txn);
        when(txn.commit()).thenReturn(CompletableFuture.completedFuture(txnResponse));
        when(kv.txn()).thenReturn(txn);

        assertThatThrownBy(() -> container.writeBlob("once", inputOf("payload"), 7, true))
            .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);

        verify(counters.get("rcs_etcd.blob.put_if_absent_rejection.total")).add(1.0);
        verify(counters.get("rcs_etcd.blob.write.total")).add(1.0);
        verify(counters.get("rcs_etcd.manifest.publish.total"), never()).add(anyDouble());
    }

    @Test
    public void readBlobRecordsCountAndDuration() throws IOException {
        GetResponse response = mock(GetResponse.class);
        io.etcd.jetcd.KeyValue kv0 = mock(io.etcd.jetcd.KeyValue.class);
        when(kv0.getValue()).thenReturn(ByteSequence.from("payload", StandardCharsets.UTF_8));
        when(response.getKvs()).thenReturn(List.of(kv0));
        when(kv.get(any())).thenReturn(CompletableFuture.completedFuture(response));

        try (InputStream in = container.readBlob("any-blob")) {
            in.readAllBytes();
        }

        verify(counters.get("rcs_etcd.blob.read.total")).add(1.0);
        verify(histograms.get("rcs_etcd.blob.read.duration")).record(anyDouble());
    }

    @Test
    public void listBlobsByPrefixRecordsCountAndDuration() throws IOException {
        GetResponse response = mock(GetResponse.class);
        when(response.getKvs()).thenReturn(List.of());
        when(kv.get(any(), any(GetOption.class))).thenReturn(CompletableFuture.completedFuture(response));

        container.listBlobsByPrefix("manifest__");

        verify(counters.get("rcs_etcd.blob.list.total")).add(1.0);
        verify(histograms.get("rcs_etcd.blob.list.duration")).record(anyDouble());
    }

    @Test
    public void listBlobsByPrefixInSortedOrderAlsoRecordsListMetrics() throws IOException {
        GetResponse response = mock(GetResponse.class);
        when(response.getKvs()).thenReturn(List.of());
        when(kv.get(any(), any(GetOption.class))).thenReturn(CompletableFuture.completedFuture(response));

        container.listBlobsByPrefixInSortedOrder("manifest__", 5, EtcdBlobContainer.BlobNameSortOrder.LEXICOGRAPHIC);

        verify(counters.get("rcs_etcd.blob.list.total")).add(1.0);
        verify(histograms.get("rcs_etcd.blob.list.duration")).record(anyDouble());
    }

    private static InputStream inputOf(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
