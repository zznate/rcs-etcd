/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import org.assertj.core.api.Assertions;
import org.opensearch.action.admin.cluster.repositories.get.GetRepositoriesResponse;
import org.opensearch.action.admin.cluster.repositories.put.PutRepositoryRequest;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchSingleNodeTestCase;

import java.util.Collection;
import java.util.List;

/**
 * Loads the plugin into a single-node OpenSearch test JVM and registers the etcd
 * repository through the public cluster-admin API. Validates that the plugin
 * wiring (Plugin → RepositoryPlugin → Repository.Factory under type id "etcd")
 * is correct end-to-end.
 *
 * <p>Does not exercise an actual RCS publish; that gate is tracked separately.
 */
public class EtcdRepositoryPluginIT extends OpenSearchSingleNodeTestCase {

    private static final String ENDPOINT = System.getProperty("etcd.endpoint", "http://localhost:2379");

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return List.of(EtcdRepositoryPlugin.class);
    }

    @Override
    protected Settings nodeSettings() {
        return Settings.builder()
            .put(super.nodeSettings())
            .putList(EtcdRepository.ENDPOINTS_SETTING.getKey(), ENDPOINT)
            .put(EtcdRepository.ROOT_PREFIX_SETTING.getKey(), "/rcs-etcd-it-plugin")
            .build();
    }

    public void testRepositoryRegistersUnderEtcdType() throws Exception {
        PutRepositoryRequest put = new PutRepositoryRequest("etcd-rcs")
            .type(EtcdRepositoryPlugin.TYPE)
            .settings(Settings.EMPTY);
        Assertions.assertThat(client().admin().cluster().putRepository(put).actionGet().isAcknowledged()).isTrue();

        GetRepositoriesResponse repos = client().admin().cluster().prepareGetRepositories("etcd-rcs").get();
        Assertions.assertThat(repos.repositories()).hasSize(1);
        Assertions.assertThat(repos.repositories().get(0).name()).isEqualTo("etcd-rcs");
        Assertions.assertThat(repos.repositories().get(0).type()).isEqualTo(EtcdRepositoryPlugin.TYPE);
    }
}
