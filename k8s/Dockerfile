# Image for the k8s comparison testbed.
#
# Build context is the repository root, not k8s/. The COPY picks up the
# zip produced by `./gradlew :plugin:bundlePlugin`.
# Run via `make image` from k8s/, which also handles `kind load
# docker-image` into the active Kind cluster.

ARG OPENSEARCH_VERSION=3.6.0
FROM opensearchproject/opensearch:${OPENSEARCH_VERSION}

# Strip bundled plugins that auto-create their own system indices at
# boot. They generate cluster-state churn that masks the rcs-etcd
# plugin's own publishes during the comparison smoke. Set converges on
# the cnos 3.6.0 reference image's removal list.
RUN opensearch-plugin remove opensearch-neural-search || true \
 && opensearch-plugin remove opensearch-knn || true \
 && opensearch-plugin remove opensearch-skills || true \
 && opensearch-plugin remove opensearch-security-analytics || true

# telemetry-otel is shipped as an installable plugin (not a bundled
# module) and provides the OTLP exporters that surface the rcs_etcd.*
# instruments through the observability stack.
RUN echo y | opensearch-plugin install telemetry-otel

COPY --chown=1000:0 plugin/build/distributions/rcs-etcd-3.6.0.0.zip /tmp/rcs-etcd-plugin.zip
RUN echo y | opensearch-plugin install file:///tmp/rcs-etcd-plugin.zip \
 && rm /tmp/rcs-etcd-plugin.zip
