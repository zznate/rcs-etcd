/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.KV;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.common.blobstore.BlobContainer;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.blobstore.BlobStore;

import java.nio.charset.StandardCharsets;

/**
 * Owns the jetcd {@link Client} lifecycle for an {@link EtcdRepository} and produces
 * {@link EtcdBlobContainer} instances bound to a multi-tenant key prefix
 * ({@code <rootPrefix>/<clusterName>}).
 */
public class EtcdBlobStore implements BlobStore {

    private static final Logger LOG = LogManager.getLogger(EtcdBlobStore.class);

    private final Client client;
    private final KV kv;
    private final String tenantPrefix;
    private final long maxRequestBytes;

    public EtcdBlobStore(Client client, String rootPrefix, String clusterName, long maxRequestBytes) {
        this.client = client;
        this.kv = client.getKVClient();
        this.tenantPrefix = buildTenantPrefix(rootPrefix, clusterName);
        this.maxRequestBytes = maxRequestBytes;
    }

    @Override
    public BlobContainer blobContainer(BlobPath path) {
        ByteSequence keyPrefix = ByteSequence.from(containerKeyPrefix(path), StandardCharsets.UTF_8);
        return new EtcdBlobContainer(kv, path, keyPrefix, maxRequestBytes);
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (RuntimeException e) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("etcd client close failed", e);
            }
        }
    }

    String tenantPrefix() {
        return tenantPrefix;
    }

    String containerKeyPrefix(BlobPath path) {
        StringBuilder sb = new StringBuilder(tenantPrefix);
        for (String segment : path) {
            sb.append('/').append(segment);
        }
        sb.append('/');
        return sb.toString();
    }

    static String buildTenantPrefix(String rootPrefix, String clusterName) {
        String trimmed = (rootPrefix == null) ? "" : rootPrefix;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "/" + clusterName;
    }
}
