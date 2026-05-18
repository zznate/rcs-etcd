/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.KeyValue;
import io.etcd.jetcd.kv.DeleteResponse;
import io.etcd.jetcd.kv.GetResponse;
import io.etcd.jetcd.kv.TxnResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.DeleteOption;
import io.etcd.jetcd.options.GetOption;
import io.etcd.jetcd.options.PutOption;

import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.blobstore.BlobMetadata;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.DeleteResult;
import org.opensearch.common.blobstore.support.PlainBlobMetadata;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Etcd-backed {@link BlobContainer}. Each blob maps 1:1 to an etcd key under
 * {@code <tenantPrefix>/<containerPath>/<blobName>}. RCS-relevant methods are implemented
 * directly; snapshot-only methods (e.g. {@link #writeBlobAtomic}) throw
 * {@link UnsupportedOperationException}.
 */
public class EtcdBlobContainer implements BlobContainer {

    private final KV kv;
    private final BlobPath path;
    private final ByteSequence keyPrefix;
    private final long maxRequestBytes;

    public EtcdBlobContainer(KV kv, BlobPath path, ByteSequence keyPrefix, long maxRequestBytes) {
        this.kv = kv;
        this.path = path;
        this.keyPrefix = keyPrefix;
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    public BlobPath path() {
        return path;
    }

    @Override
    public boolean blobExists(String blobName) throws IOException {
        GetOption opt = GetOption.builder().withCountOnly(true).build();
        GetResponse response = await(kv.get(keyForBlob(blobName), opt));
        return response.getCount() > 0;
    }

    @Override
    public InputStream readBlob(String blobName) throws IOException {
        GetResponse response = await(kv.get(keyForBlob(blobName)));
        if (response.getKvs().isEmpty()) {
            throw new NoSuchFileException("[" + blobName + "] blob not found");
        }
        byte[] bytes = response.getKvs().get(0).getValue().getBytes();
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public InputStream readBlob(String blobName, long position, long length) throws IOException {
        InputStream full = readBlob(blobName);
        long skipped = full.skip(position);
        if (skipped < position) {
            throw new IOException("could not skip to position " + position + " in [" + blobName + "]");
        }
        byte[] window = full.readNBytes((int) Math.min(length, Integer.MAX_VALUE));
        return new ByteArrayInputStream(window);
    }

    @Override
    public void writeBlob(String blobName, InputStream inputStream, long blobSize, boolean failIfAlreadyExists) throws IOException {
        byte[] payload = readBounded(inputStream, blobSize);
        rejectIfOversize(blobName, payload.length);
        ByteSequence key = keyForBlob(blobName);
        ByteSequence value = ByteSequence.from(payload);
        if (failIfAlreadyExists) {
            putIfAbsent(blobName, key, value);
        } else {
            await(kv.put(key, value));
        }
    }

    @Override
    public void writeBlobAtomic(String blobName, InputStream inputStream, long blobSize, boolean failIfAlreadyExists)
        throws IOException {
        throw new UnsupportedOperationException(
            "EtcdRepository is RCS-only; writeBlobAtomic is used for snapshot bookkeeping. "
                + "Use a separate repository (e.g. repository-s3) for snapshots."
        );
    }

    @Override
    public Map<String, BlobMetadata> listBlobs() throws IOException {
        return listBlobsByPrefix(null);
    }

    @Override
    public Map<String, BlobContainer> children() throws IOException {
        // Etcd has no native notion of "child container"; RCS does not exercise this path.
        return new HashMap<>();
    }

    @Override
    public Map<String, BlobMetadata> listBlobsByPrefix(String blobNamePrefix) throws IOException {
        ByteSequence scanFrom = scanPrefix(blobNamePrefix);
        GetOption opt = GetOption.builder().isPrefix(true).build();
        GetResponse response = await(kv.get(scanFrom, opt));
        Map<String, BlobMetadata> result = new HashMap<>();
        for (KeyValue entry : response.getKvs()) {
            String name = stripKeyPrefix(entry.getKey());
            if (name.indexOf('/') >= 0) {
                continue; // not a direct child of this container
            }
            result.put(name, new PlainBlobMetadata(name, entry.getValue().size()));
        }
        return result;
    }

    @Override
    public List<BlobMetadata> listBlobsByPrefixInSortedOrder(String blobNamePrefix, int limit, BlobNameSortOrder order)
        throws IOException {
        ByteSequence scanFrom = scanPrefix(blobNamePrefix);
        GetOption opt = GetOption.builder()
            .isPrefix(true)
            .withSortField(GetOption.SortTarget.KEY)
            .withSortOrder(GetOption.SortOrder.ASCEND)
            .withLimit(limit)
            .build();
        GetResponse response = await(kv.get(scanFrom, opt));
        List<BlobMetadata> result = new ArrayList<>(response.getKvs().size());
        for (KeyValue entry : response.getKvs()) {
            String name = stripKeyPrefix(entry.getKey());
            if (name.indexOf('/') >= 0) {
                continue;
            }
            result.add(new PlainBlobMetadata(name, entry.getValue().size()));
        }
        return result;
    }

    @Override
    public DeleteResult delete() throws IOException {
        DeleteOption opt = DeleteOption.builder().isPrefix(true).withPrevKV(true).build();
        DeleteResponse response = await(kv.delete(keyPrefix, opt));
        long bytes = 0L;
        for (KeyValue entry : response.getPrevKvs()) {
            bytes += entry.getValue().size();
        }
        return new DeleteResult(response.getDeleted(), bytes);
    }

    @Override
    public void deleteBlobsIgnoringIfNotExists(List<String> blobNames) throws IOException {
        if (blobNames.isEmpty()) {
            return;
        }
        List<CompletableFuture<DeleteResponse>> futures = new ArrayList<>(blobNames.size());
        for (String blobName : blobNames) {
            futures.add(kv.delete(keyForBlob(blobName)));
        }
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0])).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while deleting blobs", e);
        } catch (ExecutionException e) {
            throw asIOException("bulk delete failed", e);
        }
    }

    ByteSequence keyForBlob(String blobName) {
        byte[] prefixBytes = keyPrefix.getBytes();
        byte[] nameBytes = blobName.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[prefixBytes.length + nameBytes.length];
        System.arraycopy(prefixBytes, 0, combined, 0, prefixBytes.length);
        System.arraycopy(nameBytes, 0, combined, prefixBytes.length, nameBytes.length);
        return ByteSequence.from(combined);
    }

    private ByteSequence scanPrefix(String blobNamePrefix) {
        if (blobNamePrefix == null || blobNamePrefix.isEmpty()) {
            return keyPrefix;
        }
        byte[] prefixBytes = keyPrefix.getBytes();
        byte[] nameBytes = blobNamePrefix.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[prefixBytes.length + nameBytes.length];
        System.arraycopy(prefixBytes, 0, combined, 0, prefixBytes.length);
        System.arraycopy(nameBytes, 0, combined, prefixBytes.length, nameBytes.length);
        return ByteSequence.from(combined);
    }

    private String stripKeyPrefix(ByteSequence key) {
        String full = key.toString(StandardCharsets.UTF_8);
        String prefix = keyPrefix.toString(StandardCharsets.UTF_8);
        if (!full.startsWith(prefix)) {
            return full;
        }
        return full.substring(prefix.length());
    }

    private void rejectIfOversize(String blobName, int size) throws IOException {
        if (size > maxRequestBytes) {
            throw new IOException(
                "blob [" + blobName + "] of size " + size
                    + " bytes exceeds etcd max_request_bytes (" + maxRequestBytes + " bytes); "
                    + "raise cluster.rcs_etcd.max_request_bytes and the etcd --max-request-bytes flag, "
                    + "or split the payload"
            );
        }
    }

    private void putIfAbsent(String blobName, ByteSequence key, ByteSequence value) throws IOException {
        Cmp cmp = new Cmp(key, Cmp.Op.EQUAL, CmpTarget.createRevision(0L));
        TxnResponse response = await(kv.txn().If(cmp).Then(Op.put(key, value, PutOption.DEFAULT)).commit());
        if (!response.isSucceeded()) {
            throw new FileAlreadyExistsException("blob [" + blobName + "] already exists");
        }
    }

    private byte[] readBounded(InputStream input, long expectedSize) throws IOException {
        if (expectedSize <= 0) {
            return input.readAllBytes();
        }
        if (expectedSize > Integer.MAX_VALUE) {
            throw new IOException("blob size " + expectedSize + " exceeds Integer.MAX_VALUE");
        }
        return input.readNBytes((int) expectedSize);
    }

    private <T> T await(CompletableFuture<T> future) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while awaiting etcd response", e);
        } catch (ExecutionException e) {
            throw asIOException("etcd operation failed", e);
        }
    }

    private IOException asIOException(String message, ExecutionException e) {
        Throwable cause = (e.getCause() != null) ? e.getCause() : e;
        return new IOException(message + ": " + cause.getMessage(), cause);
    }
}
