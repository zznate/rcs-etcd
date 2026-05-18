/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class EtcdRepositoryTests {

    @Test
    public void normalizePrefixStripsTrailingSlashes() {
        assertThat(EtcdRepository.normalizePrefix("/opensearch-rcs///")).isEqualTo("/opensearch-rcs");
    }

    @Test
    public void normalizePrefixLeavesNonTrailingSlashes() {
        assertThat(EtcdRepository.normalizePrefix("/a/b/c")).isEqualTo("/a/b/c");
    }

    @Test
    public void normalizePrefixReturnsEmptyForEmptyInput() {
        assertThat(EtcdRepository.normalizePrefix("")).isEmpty();
    }

    @Test
    public void normalizePrefixReturnsEmptyForNullInput() {
        assertThat(EtcdRepository.normalizePrefix(null)).isEmpty();
    }

    @Test
    public void tenantPrefixCombinesRootAndCluster() {
        assertThat(EtcdRepository.tenantPrefix("/opensearch-rcs", "cluster-a"))
            .isEqualTo("/opensearch-rcs/cluster-a");
    }

    @Test
    public void tenantPrefixStripsTrailingSlashFromRoot() {
        assertThat(EtcdRepository.tenantPrefix("/opensearch-rcs///", "cluster-a"))
            .isEqualTo("/opensearch-rcs/cluster-a");
    }

    @Test
    public void tenantPrefixToleratesEmptyRoot() {
        assertThat(EtcdRepository.tenantPrefix("", "cluster-a")).isEqualTo("/cluster-a");
    }
}
