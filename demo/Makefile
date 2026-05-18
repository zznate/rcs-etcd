SHELL := /usr/bin/env bash
PLUGIN_VERSION ?= 3.0.0.0
PLUGIN_ZIP := ../plugin/build/distributions/rcs-etcd-$(PLUGIN_VERSION).zip
DEMO_IMAGE := rcs-etcd-demo:latest
COMPOSE_PROJECT := rcs-etcd-demo
COMPOSE := docker-compose -p $(COMPOSE_PROJECT)

.PHONY: build up verify down clean help

help:
	@echo "Targets:"
	@echo "  build    Build the plugin zip and rebuild the opensearch image"
	@echo "  up       Start etcd + opensearch (depends on build)"
	@echo "  verify   Run the demo verification script against a running stack"
	@echo "  down     Stop the stack and remove volumes / orphans"
	@echo "  clean    down + delete the built plugin zip"

build:
	@echo "==> Building plugin zip"
	cd .. && JAVA_HOME=$$(/usr/libexec/java_home -v 21 2>/dev/null || echo $$JAVA_HOME) ./gradlew :plugin:bundlePlugin
	@test -f $(PLUGIN_ZIP) || { echo "ERROR: $(PLUGIN_ZIP) not found after build"; exit 1; }
	@echo "==> Building opensearch image with plugin installed"
	# Build with `docker build` directly rather than `docker-compose build`:
	# compose v1 needs the buildx plugin (>= 0.17.0) for build operations and
	# it is not present on every host. `docker build` works against the bare
	# daemon (or podman shim). --load is required when the daemon's default
	# build driver is docker-container, so the image lands in the local image
	# store where compose can find it.
	cd .. && docker build --load -t $(DEMO_IMAGE) -f demo/Dockerfile .

up: build
	$(COMPOSE) up -d
	@echo
	@echo "Demo stack starting. 'make verify' once OpenSearch is ready (~10-20s)."

verify:
	./verify.sh

down:
	$(COMPOSE) down -v --remove-orphans

clean: down
	rm -f $(PLUGIN_ZIP)
