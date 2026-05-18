/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Setting;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.RepositoryPlugin;
import org.opensearch.repositories.Repository;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Registers the etcd-backed {@link Repository} under the type id {@code "etcd"}.
 */
public class EtcdRepositoryPlugin extends Plugin implements RepositoryPlugin {

    public static final String TYPE = "etcd";

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(EtcdRepository.ENDPOINTS_SETTING, EtcdRepository.ROOT_PREFIX_SETTING);
    }

    @Override
    public Map<String, Repository.Factory> getRepositories(
        Environment env,
        NamedXContentRegistry namedXContentRegistry,
        ClusterService clusterService,
        RecoverySettings recoverySettings
    ) {
        return Collections.singletonMap(
            TYPE,
            metadata -> new EtcdRepository(metadata, namedXContentRegistry, clusterService, recoverySettings)
        );
    }
}
