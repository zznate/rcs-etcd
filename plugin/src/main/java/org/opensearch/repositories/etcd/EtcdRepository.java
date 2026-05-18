/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import io.etcd.jetcd.Client;

import org.opensearch.cluster.metadata.RepositoryMetadata;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.blobstore.BlobPath;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.core.common.unit.ByteSizeUnit;
import org.opensearch.core.common.unit.ByteSizeValue;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.repositories.blobstore.BlobStoreRepository;

import java.util.List;

/**
 * Etcd-backed {@link BlobStoreRepository}. Keys are namespaced under
 * {@code <root_prefix>/<cluster_name>/...} so multiple OpenSearch clusters can share an etcd
 * ensemble without colliding.
 */
public class EtcdRepository extends BlobStoreRepository {

    public static final Setting<List<String>> ENDPOINTS_SETTING = Setting.listSetting(
        "cluster.rcs_etcd.endpoints",
        List.of("http://localhost:2379"),
        s -> s,
        Setting.Property.NodeScope
    );

    public static final Setting<String> ROOT_PREFIX_SETTING = Setting.simpleString(
        "cluster.rcs_etcd.root_prefix",
        "/opensearch-rcs",
        Setting.Property.NodeScope
    );

    /**
     * Hard ceiling on per-blob payload size. Blobs above this threshold are rejected with
     * a clear {@link java.io.IOException} so RCS can surface the failure on its next publish.
     * Tracks etcd's default {@code --max-request-bytes} (1.5 MiB).
     */
    public static final Setting<ByteSizeValue> MAX_REQUEST_BYTES_SETTING = Setting.byteSizeSetting(
        "cluster.rcs_etcd.max_request_bytes",
        new ByteSizeValue(1536, ByteSizeUnit.KB),
        Setting.Property.NodeScope
    );

    private final List<String> endpoints;
    private final String rootPrefix;
    private final String clusterName;
    private final long maxRequestBytes;

    public EtcdRepository(
        RepositoryMetadata metadata,
        NamedXContentRegistry namedXContentRegistry,
        ClusterService clusterService,
        RecoverySettings recoverySettings
    ) {
        super(metadata, namedXContentRegistry, clusterService, recoverySettings);
        Settings nodeSettings = clusterService.getSettings();
        this.endpoints = ENDPOINTS_SETTING.get(nodeSettings);
        this.rootPrefix = normalizePrefix(ROOT_PREFIX_SETTING.get(nodeSettings));
        this.clusterName = clusterService.getClusterName().value();
        this.maxRequestBytes = MAX_REQUEST_BYTES_SETTING.get(nodeSettings).getBytes();
    }

    @Override
    protected EtcdBlobStore createBlobStore() {
        Client client = Client.builder().endpoints(endpoints.toArray(new String[0])).build();
        return new EtcdBlobStore(client, rootPrefix, clusterName, maxRequestBytes);
    }

    @Override
    public BlobPath basePath() {
        return BlobPath.cleanPath();
    }

    static String normalizePrefix(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String trimmed = raw;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
