/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import org.junit.Test;
import org.opensearch.common.blobstore.BlobPath;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the multi-tenant key-prefix invariant: every key written by the plugin is
 * namespaced under {@code <rootPrefix>/<clusterName>/}. See {@code project_multi_tenant_invariant}.
 */
public class EtcdBlobStoreTests {

    @Test
    public void buildTenantPrefixCombinesRootAndCluster() {
        assertThat(EtcdBlobStore.buildTenantPrefix("/opensearch-rcs", "cluster-a"))
            .isEqualTo("/opensearch-rcs/cluster-a");
    }

    @Test
    public void buildTenantPrefixStripsTrailingSlashFromRoot() {
        assertThat(EtcdBlobStore.buildTenantPrefix("/opensearch-rcs///", "cluster-a"))
            .isEqualTo("/opensearch-rcs/cluster-a");
    }

    @Test
    public void buildTenantPrefixToleratesEmptyRoot() {
        assertThat(EtcdBlobStore.buildTenantPrefix("", "cluster-a"))
            .isEqualTo("/cluster-a");
    }

    @Test
    public void containerKeyPrefixIncludesPathSegmentsAndTrailingSlash() {
        // We don't need a real jetcd Client to exercise containerKeyPrefix; pass null,
        // but the test must not invoke any client method.
        EtcdBlobStore store = stubStore("/opensearch-rcs", "cluster-a");
        BlobPath path = BlobPath.cleanPath().add("cluster-state").add("manifests");

        assertThat(store.containerKeyPrefix(path))
            .isEqualTo("/opensearch-rcs/cluster-a/cluster-state/manifests/");
    }

    @Test
    public void containerKeyPrefixOnRootContainerEndsImmediatelyAfterCluster() {
        EtcdBlobStore store = stubStore("/opensearch-rcs", "cluster-a");

        assertThat(store.containerKeyPrefix(BlobPath.cleanPath()))
            .isEqualTo("/opensearch-rcs/cluster-a/");
    }

    private static EtcdBlobStore stubStore(String rootPrefix, String clusterName) {
        // The Client is only touched on blobContainer(...) / close(), neither of which
        // these key-derivation tests invoke. close() tolerates a null client.
        return new EtcdBlobStore(null, rootPrefix, clusterName, 1_572_864L);
    }
}
