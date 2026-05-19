/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import io.etcd.jetcd.Client;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.blobstore.BlobContainer.BlobNameSortOrder;
import org.opensearch.common.blobstore.BlobMetadata;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.DeleteResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests that exercise {@link EtcdBlobContainer} against a real etcd container
 * started by the Gradle dockerCompose plugin. Each test runs against a unique
 * {@code <root_prefix>/<cluster_name>/} prefix so parallel runs do not collide.
 */
public class EtcdBlobContainerIT {

    private static final String ROOT_PREFIX = "/rcs-etcd-it";
    private static final long MAX_REQUEST_BYTES = 64 * 1024L; // 64 KiB — small enough to test oversize cheaply
    private static final String ENDPOINT = System.getProperty("etcd.endpoint", "http://localhost:2379");

    private static Client client;
    private String clusterName;
    private EtcdBlobStore store;

    @BeforeClass
    public static void createClient() {
        client = Client.builder().endpoints(ENDPOINT).build();
    }

    @AfterClass
    public static void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    @Before
    public void setUp() {
        clusterName = "cluster-" + UUID.randomUUID();
        store = new EtcdBlobStore(client, ROOT_PREFIX, clusterName, MAX_REQUEST_BYTES, EtcdRepositoryMetrics.NOOP);
    }

    @After
    public void tearDown() throws IOException {
        if (store != null) {
            // Wipe the test cluster's prefix; do not close the shared Client.
            store.blobContainer(BlobPath.cleanPath()).delete();
        }
    }

    @Test
    public void writeReadRoundTrip() throws IOException {
        BlobContainer container = store.blobContainer(BlobPath.cleanPath().add("cluster-state"));
        container.writeBlob("manifest__001", inputOf("hello world"), 11, false);

        try (InputStream in = container.readBlob("manifest__001")) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello world");
        }
        assertThat(container.blobExists("manifest__001")).isTrue();
        assertThat(container.blobExists("missing")).isFalse();
    }

    @Test
    public void listBlobsByPrefixReturnsWrittenSet() throws IOException {
        BlobContainer container = store.blobContainer(BlobPath.cleanPath().add("listing"));
        container.writeBlob("foo-1", inputOf("a"), 1, false);
        container.writeBlob("foo-2", inputOf("bb"), 2, false);
        container.writeBlob("bar-1", inputOf("ccc"), 3, false);

        Map<String, BlobMetadata> all = container.listBlobsByPrefix(null);
        assertThat(all).containsOnlyKeys("foo-1", "foo-2", "bar-1");
        assertThat(all.get("foo-1").length()).isEqualTo(1L);
        assertThat(all.get("bar-1").length()).isEqualTo(3L);

        Map<String, BlobMetadata> filtered = container.listBlobsByPrefix("foo-");
        assertThat(filtered).containsOnlyKeys("foo-1", "foo-2");
    }

    @Test
    public void sortedListIsAscendingByKeyWithLimit() throws IOException {
        BlobContainer container = store.blobContainer(BlobPath.cleanPath().add("sorted"));
        container.writeBlob("manifest__003", inputOf("c"), 1, false);
        container.writeBlob("manifest__001", inputOf("a"), 1, false);
        container.writeBlob("manifest__002", inputOf("b"), 1, false);

        List<BlobMetadata> result = container.listBlobsByPrefixInSortedOrder(
            "manifest__",
            2,
            BlobNameSortOrder.LEXICOGRAPHIC
        );

        assertThat(result).extracting(BlobMetadata::name).containsExactly("manifest__001", "manifest__002");
    }

    @Test
    public void deleteRemovesAllKeysUnderContainer() throws IOException {
        BlobContainer container = store.blobContainer(BlobPath.cleanPath().add("to-delete"));
        container.writeBlob("a", inputOf("xx"), 2, false);
        container.writeBlob("b", inputOf("yyy"), 3, false);

        DeleteResult result = container.delete();

        assertThat(result.blobsDeleted()).isEqualTo(2L);
        assertThat(result.bytesDeleted()).isEqualTo(5L);
        assertThat(container.listBlobsByPrefix(null)).isEmpty();
    }

    @Test
    public void deleteBlobsIgnoringIfNotExistsToleratesMissing() throws IOException {
        BlobContainer container = store.blobContainer(BlobPath.cleanPath().add("partial"));
        container.writeBlob("present", inputOf("z"), 1, false);

        container.deleteBlobsIgnoringIfNotExists(List.of("present", "missing-1", "missing-2"));

        assertThat(container.blobExists("present")).isFalse();
    }

    @Test
    public void crossClusterIsolation() throws IOException {
        String otherCluster = "cluster-" + UUID.randomUUID();
        EtcdBlobStore otherStore = new EtcdBlobStore(client, ROOT_PREFIX, otherCluster, MAX_REQUEST_BYTES, EtcdRepositoryMetrics.NOOP);
        try {
            BlobContainer mine = store.blobContainer(BlobPath.cleanPath().add("shared-name"));
            BlobContainer theirs = otherStore.blobContainer(BlobPath.cleanPath().add("shared-name"));

            mine.writeBlob("manifest", inputOf("MINE"), 4, false);
            theirs.writeBlob("manifest", inputOf("THEIRS"), 6, false);

            try (InputStream in = mine.readBlob("manifest")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("MINE");
            }
            try (InputStream in = theirs.readBlob("manifest")) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("THEIRS");
            }
            assertThat(mine.listBlobsByPrefix(null)).containsOnlyKeys("manifest");
            assertThat(theirs.listBlobsByPrefix(null)).containsOnlyKeys("manifest");
        } finally {
            otherStore.blobContainer(BlobPath.cleanPath()).delete();
        }
    }

    @Test
    public void oversizeBlobIsRejectedWithDescriptiveException() {
        BlobContainer container = store.blobContainer(BlobPath.cleanPath().add("oversize"));
        byte[] big = new byte[(int) MAX_REQUEST_BYTES + 1];

        assertThatThrownBy(() -> container.writeBlob("too-big", new ByteArrayInputStream(big), big.length, false))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("too-big")
            .hasMessageContaining(String.valueOf(big.length))
            .hasMessageContaining(String.valueOf(MAX_REQUEST_BYTES));
    }

    @Test
    public void writeBlobFailIfAlreadyExistsRejectsDuplicate() throws IOException {
        BlobContainer container = store.blobContainer(BlobPath.cleanPath().add("dup-test"));
        container.writeBlob("once", inputOf("first"), 5, true);

        assertThatThrownBy(() -> container.writeBlob("once", inputOf("second"), 6, true))
            .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
    }

    private static InputStream inputOf(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
