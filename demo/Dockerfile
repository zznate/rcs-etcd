# Build context is the repository root, not demo/. Run `make build` (or
# `docker-compose build` from inside demo/) and the COPY below picks up
# the zip produced by `./gradlew :plugin:bundlePlugin`.

ARG OPENSEARCH_VERSION=3.0.0
FROM opensearchproject/opensearch:${OPENSEARCH_VERSION}

COPY plugin/build/distributions/rcs-etcd-3.0.0.0.zip /tmp/rcs-etcd-plugin.zip

USER root
RUN echo y | /usr/share/opensearch/bin/opensearch-plugin install file:///tmp/rcs-etcd-plugin.zip \
    && rm /tmp/rcs-etcd-plugin.zip
USER opensearch
