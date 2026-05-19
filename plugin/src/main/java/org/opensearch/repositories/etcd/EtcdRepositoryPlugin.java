/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.repositories.etcd;

import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Setting;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.indices.recovery.RecoverySettings;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.RepositoryPlugin;
import org.opensearch.plugins.TelemetryAwarePlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.repositories.Repository;
import org.opensearch.script.ScriptService;
import org.opensearch.telemetry.metrics.MetricsRegistry;
import org.opensearch.telemetry.tracing.Tracer;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.transport.client.Client;
import org.opensearch.watcher.ResourceWatcherService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registers the etcd-backed {@link Repository} under the type id {@code "etcd"} and
 * wires the OpenSearch telemetry SPI through to the BlobContainer instruments.
 */
public class EtcdRepositoryPlugin extends Plugin implements RepositoryPlugin, TelemetryAwarePlugin {

    public static final String TYPE = "etcd";

    /**
     * Initialised lazily by {@link #createComponents}. Until then the noop instance keeps
     * call sites free of null checks; the {@link Repository.Factory} reads this through a
     * Supplier so the value picked up at repo-construction time is always the post-bootstrap
     * one (see {@code opensearch-telemetry-spi} memory for the lifecycle rationale).
     */
    private volatile EtcdRepositoryMetrics metrics = EtcdRepositoryMetrics.NOOP;

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(EtcdRepository.ENDPOINTS_SETTING, EtcdRepository.ROOT_PREFIX_SETTING);
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier,
        Tracer tracer,
        MetricsRegistry metricsRegistry
    ) {
        this.metrics = new EtcdRepositoryMetrics(metricsRegistry);
        return Collections.emptyList();
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
            metadata -> new EtcdRepository(metadata, namedXContentRegistry, clusterService, recoverySettings, () -> this.metrics)
        );
    }
}
