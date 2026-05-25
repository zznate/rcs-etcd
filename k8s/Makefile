# Makefile for the k8s comparison testbed.
#
# Run from the k8s/ directory. Targets resolve the active kubectl
# context to find the matching Kind cluster name (strips the `kind-`
# prefix).

SHELL     := /usr/bin/env bash
REPO_ROOT := $(shell git -C $(CURDIR) rev-parse --show-toplevel)
IMAGE     := rcs-etcd-k8s:latest
CONTEXT   := $(shell kubectl config current-context 2>/dev/null)
CLUSTER   := $(patsubst kind-%,%,$(CONTEXT))
NAMESPACE := rcs-etcd-poc

# Kind on this project runs on podman; the env var must be set for
# every kind invocation that touches the cluster's containers.
export KIND_EXPERIMENTAL_PROVIDER := podman

.PHONY: help cluster otel-stack image up-vanilla up-rcs-etcd switch-to-vanilla switch-to-rcs-etcd smoke down clean

help:
	@echo "Targets:"
	@echo "  cluster              Create the opensearch-testing Kind cluster + namespaces (refuses to recreate)"
	@echo "  otel-stack           Deploy the self-contained otel-stack (OTel + Prometheus + Grafana + Jaeger + Loki + Promtail)"
	@echo "  image                Build the rcs-etcd plugin + testbed image, load into Kind"
	@echo "  up-vanilla           Apply common + vanilla; wait for green; record timings"
	@echo "  up-rcs-etcd          Apply common + rcs-etcd; wait for green and first manifest publish"
	@echo "  switch-to-vanilla    down + up-vanilla"
	@echo "  switch-to-rcs-etcd   down + up-rcs-etcd"
	@echo "  smoke                Run the benchmark + emit METRIC lines for the active config"
	@echo "  down                 Delete the rcs-etcd-poc namespace (otel-stack is untouched)"
	@echo "  clean                down + remove the Kind-loaded image"

cluster:
	@if kind get clusters 2>/dev/null | grep -qx 'opensearch-testing'; then \
	  echo "ERROR: cluster 'opensearch-testing' already exists."; \
	  echo "       Run 'kind delete cluster --name opensearch-testing' first to recreate."; \
	  exit 1; \
	fi
	kind create cluster --config $(CURDIR)/kind/kind-config.yaml
	kubectl apply -f $(CURDIR)/kind/namespaces.yaml

otel-stack:
	@for c in otel-collector jaeger prometheus loki promtail grafana; do \
	  echo "==> applying $$c"; \
	  kubectl apply -f $(CURDIR)/otel-stack/$$c/ ; \
	done
	@echo "==> waiting for deployments"
	kubectl wait --for=condition=available --timeout=180s \
	  deployment/otel-collector deployment/jaeger deployment/prometheus deployment/grafana \
	  -n otel-stack

image:
	@test -n "$(CLUSTER)" || { echo "no active kubectl context — run 'make cluster' first"; exit 1; }
	@echo "==> building rcs-etcd plugin zip"
	cd $(REPO_ROOT) && ./gradlew :plugin:bundlePlugin
	@echo "==> building image $(IMAGE)"
	cd $(REPO_ROOT) && docker build --load -t $(IMAGE) -f k8s/Dockerfile .
	@echo "==> loading image into kind cluster $(CLUSTER)"
	kind load docker-image $(IMAGE) --name $(CLUSTER)

up-vanilla:
	bash $(CURDIR)/bring-up.sh vanilla

up-rcs-etcd:
	bash $(CURDIR)/bring-up.sh rcs-etcd

switch-to-vanilla: down up-vanilla

switch-to-rcs-etcd: down up-rcs-etcd

smoke:
	bash $(CURDIR)/smoke.sh

down:
	kubectl delete namespace $(NAMESPACE) --ignore-not-found
	kubectl apply -f $(CURDIR)/kind/namespaces.yaml

clean: down
	-docker rmi $(IMAGE)
