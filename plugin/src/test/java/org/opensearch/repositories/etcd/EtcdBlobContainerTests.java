/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.Txn;
import io.etcd.jetcd.kv.DeleteResponse;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.PutResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.opensearch.common.blobstore.BlobContainer.BlobNameSortOrder;
import org.opensearch.common.blobstore.BlobMetadata;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.DeleteResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class EtcdBlobContainerTests {

    private static final String TENANT = "/opensearch-rcs/cluster-a";
    private static final BlobPath CONTAINER_PATH = BlobPath.cleanPath().add("cluster-state");
    private static final String CONTAINER_KEY_PREFIX = TENANT + "/cluster-state/";
    private static final long MAX_REQUEST_BYTES = 1_572_864L; // 1.5 MiB

    private KV kv;
    private EtcdBlobContainer container;

    @Before
    public void setUp() {
        kv = mock(KV.class);
        ByteSequence keyPrefix = ByteSequence.from(CONTAINER_KEY_PREFIX, StandardCharsets.UTF_8);
        container = new EtcdBlobContainer(kv, CONTAINER_PATH, keyPrefix, MAX_REQUEST_BYTES, EtcdRepositoryMetrics.NOOP);
    }

    @Test
    public void keyForBlobIsTenantPrefixPlusContainerPathPlusName() {
        ByteSequence key = container.keyForBlob("manifest__abc");
        assertThat(key.toString(StandardCharsets.UTF_8))
            .isEqualTo("/opensearch-rcs/cluster-a/cluster-state/manifest__abc");
    }

    @Test
    public void writeBlobUsesPlainPutWhenOverwriteAllowed() throws IOException {
        when(kv.put(any(), any())).thenReturn(CompletableFuture.completedFuture(mock(PutResponse.class)));

        container.writeBlob("manifest__abc", inputOf("hello"), 5, false);

        ArgumentCaptor<ByteSequence> keyCaptor = ArgumentCaptor.forClass(ByteSequence.class);
        ArgumentCaptor<ByteSequence> valueCaptor = ArgumentCaptor.forClass(ByteSequence.class);
        verify(kv).put(keyCaptor.capture(), valueCaptor.capture());
        verify(kv, never()).txn();

        assertThat(keyCaptor.getValue().toString(StandardCharsets.UTF_8))
            .isEqualTo(CONTAINER_KEY_PREFIX + "manifest__abc");
        assertThat(valueCaptor.getValue().getBytes()).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void writeBlobUsesTransactionWhenFailIfAlreadyExists() throws IOException {
        TxnResponse txnResponse = mock(TxnResponse.class);
        when(txnResponse.isSucceeded()).thenReturn(true);
        Txn txn = mock(Txn.class);
        when(txn.If(any(Cmp.class))).thenReturn(txn);
        when(txn.Then(any(Op.class))).thenReturn(txn);
        when(txn.commit()).thenReturn(CompletableFuture.completedFuture(txnResponse));
        when(kv.txn()).thenReturn(txn);

        container.writeBlob("manifest__abc", inputOf("hello"), 5, true);

        verify(kv, never()).put(any(), any());
        verify(kv).txn();
        verify(txn).If(any(Cmp.class));
        verify(txn).Then(any(Op.class));
        verify(txn).commit();
    }

    @Test
    public void writeBlobFailIfAlreadyExistsThrowsWhenTxnRejects() {
        TxnResponse txnResponse = mock(TxnResponse.class);
        when(txnResponse.isSucceeded()).thenReturn(false);
        Txn txn = mock(Txn.class);
        when(txn.If(any(Cmp.class))).thenReturn(txn);
        when(txn.Then(any(Op.class))).thenReturn(txn);
        when(txn.commit()).thenReturn(CompletableFuture.completedFuture(txnResponse));
        when(kv.txn()).thenReturn(txn);

        assertThatThrownBy(() -> container.writeBlob("manifest__abc", inputOf("hello"), 5, true))
            .isInstanceOf(FileAlreadyExistsException.class)
            .hasMessageContaining("manifest__abc");
    }

    @Test
    public void writeBlobRejectsOversizeWithDetailedException() {
        byte[] big = new byte[(int) MAX_REQUEST_BYTES + 1];

        assertThatThrownBy(() -> container.writeBlob("huge-mapping", new ByteArrayInputStream(big), big.length, false))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("huge-mapping")
            .hasMessageContaining(String.valueOf(big.length))
            .hasMessageContaining(String.valueOf(MAX_REQUEST_BYTES));
    }

    @Test
    public void readBlobReturnsExpectedBytes() throws IOException {
        GetResponse response = mock(GetResponse.class);
        KeyValue kv0 = mock(KeyValue.class);
        when(kv0.getValue()).thenReturn(ByteSequence.from("payload", StandardCharsets.UTF_8));
        when(response.getKvs()).thenReturn(List.of(kv0));
        when(kv.get(any())).thenReturn(CompletableFuture.completedFuture(response));

        try (InputStream in = container.readBlob("manifest__abc")) {
            assertThat(in.readAllBytes()).isEqualTo("payload".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    public void readBlobThrowsNoSuchFileWhenEmpty() {
        GetResponse response = mock(GetResponse.class);
        when(response.getKvs()).thenReturn(List.of());
        when(kv.get(any())).thenReturn(CompletableFuture.completedFuture(response));

        assertThatThrownBy(() -> container.readBlob("missing"))
            .isInstanceOf(NoSuchFileException.class)
            .hasMessageContaining("missing");
    }

    @Test
    public void blobExistsReturnsTrueOnPositiveCount() throws IOException {
        GetResponse response = mock(GetResponse.class);
        when(response.getCount()).thenReturn(1L);
        when(kv.get(any(), any(GetOption.class))).thenReturn(CompletableFuture.completedFuture(response));

        assertThat(container.blobExists("manifest__abc")).isTrue();
    }

    @Test
    public void blobExistsReturnsFalseOnZeroCount() throws IOException {
        GetResponse response = mock(GetResponse.class);
        when(response.getCount()).thenReturn(0L);
        when(kv.get(any(), any(GetOption.class))).thenReturn(CompletableFuture.completedFuture(response));

        assertThat(container.blobExists("missing")).isFalse();
    }

    @Test
    public void listBlobsByPrefixReturnsDirectChildrenOnly() throws IOException {
        KeyValue direct = mockKv(CONTAINER_KEY_PREFIX + "manifest__abc", 12L);
        KeyValue nested = mockKv(CONTAINER_KEY_PREFIX + "sub/nested-key", 99L);
        GetResponse response = mock(GetResponse.class);
        when(response.getKvs()).thenReturn(List.of(direct, nested));
        when(kv.get(any(), any(GetOption.class))).thenReturn(CompletableFuture.completedFuture(response));

        Map<String, BlobMetadata> result = container.listBlobsByPrefix(null);

        assertThat(result).containsOnlyKeys("manifest__abc");
        assertThat(result.get("manifest__abc").length()).isEqualTo(12L);
    }

    @Test
    public void listBlobsByPrefixInSortedOrderUsesNativeAscendingKeySort() throws IOException {
        KeyValue first = mockKv(CONTAINER_KEY_PREFIX + "manifest__001", 1L);
        KeyValue second = mockKv(CONTAINER_KEY_PREFIX + "manifest__002", 2L);
        GetResponse response = mock(GetResponse.class);
        when(response.getKvs()).thenReturn(List.of(first, second));
        when(kv.get(any(), any(GetOption.class))).thenReturn(CompletableFuture.completedFuture(response));

        List<BlobMetadata> result = container.listBlobsByPrefixInSortedOrder("manifest__", 5, BlobNameSortOrder.LEXICOGRAPHIC);

        ArgumentCaptor<GetOption> optCaptor = ArgumentCaptor.forClass(GetOption.class);
        verify(kv).get(any(), optCaptor.capture());
        GetOption opt = optCaptor.getValue();
        assertThat(opt.isPrefix()).isTrue();
        assertThat(opt.getSortField()).isEqualTo(GetOption.SortTarget.KEY);
        assertThat(opt.getSortOrder()).isEqualTo(GetOption.SortOrder.ASCEND);
        assertThat(opt.getLimit()).isEqualTo(5L);

        assertThat(result).extracting(BlobMetadata::name).containsExactly("manifest__001", "manifest__002");
    }

    @Test
    public void deleteReturnsAccurateBlobAndByteCounts() throws IOException {
        KeyValue first = mockKv(CONTAINER_KEY_PREFIX + "manifest__001", 0L);
        when(first.getValue()).thenReturn(ByteSequence.from(new byte[7]));
        KeyValue second = mockKv(CONTAINER_KEY_PREFIX + "manifest__002", 0L);
        when(second.getValue()).thenReturn(ByteSequence.from(new byte[5]));
        DeleteResponse response = mock(DeleteResponse.class);
        when(response.getDeleted()).thenReturn(2L);
        when(response.getPrevKvs()).thenReturn(List.of(first, second));
        when(kv.delete(any(), any(DeleteOption.class))).thenReturn(CompletableFuture.completedFuture(response));

        DeleteResult result = container.delete();

        ArgumentCaptor<DeleteOption> optCaptor = ArgumentCaptor.forClass(DeleteOption.class);
        verify(kv).delete(any(), optCaptor.capture());
        assertThat(optCaptor.getValue().isPrefix()).isTrue();
        assertThat(optCaptor.getValue().isPrevKV()).isTrue();
        assertThat(result.blobsDeleted()).isEqualTo(2L);
        assertThat(result.bytesDeleted()).isEqualTo(12L);
    }

    @Test
    public void deleteBlobsIgnoringIfNotExistsCallsKvDeletePerName() throws IOException {
        when(kv.delete(any())).thenReturn(CompletableFuture.completedFuture(mock(DeleteResponse.class)));

        container.deleteBlobsIgnoringIfNotExists(List.of("a", "b", "c"));

        verify(kv, times(3)).delete(any());
    }

    @Test
    public void writeBlobAtomicDelegatesToPlainPut() throws IOException {
        when(kv.put(any(), any())).thenReturn(CompletableFuture.completedFuture(mock(PutResponse.class)));

        container.writeBlobAtomic("verify-token", inputOf("ok"), 2, false);

        verify(kv).put(any(), any());
        verify(kv, never()).txn();
    }

    private static InputStream inputOf(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static KeyValue mockKv(String key, long valueSize) {
        KeyValue kv0 = mock(KeyValue.class);
        when(kv0.getKey()).thenReturn(ByteSequence.from(key, StandardCharsets.UTF_8));
        when(kv0.getValue()).thenReturn(ByteSequence.from(new byte[(int) valueSize]));
        return kv0;
    }
}
