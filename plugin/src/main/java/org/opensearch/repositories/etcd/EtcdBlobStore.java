/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;

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
    private final String tenantPrefix;
    private final long maxRequestBytes;
    private final EtcdRepositoryMetrics metrics;

    public EtcdBlobStore(Client client, String rootPrefix, String clusterName, long maxRequestBytes, EtcdRepositoryMetrics metrics) {
        this.client = client;
        this.tenantPrefix = EtcdRepository.tenantPrefix(rootPrefix, clusterName);
        this.maxRequestBytes = maxRequestBytes;
        this.metrics = metrics;
    }

    @Override
    public BlobContainer blobContainer(BlobPath path) {
        ByteSequence keyPrefix = ByteSequence.from(containerKeyPrefix(path), StandardCharsets.UTF_8);
        return new EtcdBlobContainer(client.getKVClient(), path, keyPrefix, maxRequestBytes, metrics);
    }

    @Override
    public void close() {
        if (client == null) {
            return;
        }
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
            sb.append(EtcdRepository.B_P_SEPARATOR).append(segment);
        }
        sb.append(EtcdRepository.B_P_SEPARATOR);
        return sb.toString();
    }
}
