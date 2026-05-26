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

SIBLINGS := $(realpath $(REPO_ROOT)/..)
OSB_REPO ?= $(SIBLINGS)/opensearch-benchmark
OSB_WORKLOADS ?= $(SIBLINGS)/opensearch-benchmark-workloads

.PHONY: help cluster otel-stack image benchmark-setup up-vanilla up-rcs-etcd switch-to-vanilla switch-to-rcs-etcd smoke smoke-cluster-state failover-suite failover-clean-kill failover-state-diff down clean

help:
	@echo "Targets:"
	@echo "  cluster              Create the opensearch-testing Kind cluster + namespaces (refuses to recreate)"
	@echo "  otel-stack           Deploy the self-contained otel-stack (OTel + Prometheus + Grafana + Jaeger + Loki + Promtail)"
	@echo "  image                Build the rcs-etcd plugin + testbed image, load into Kind"
	@echo "  benchmark-setup      Install opensearch-benchmark (user-local) from the sibling checkout"
	@echo "  up-vanilla           Apply common + vanilla; wait for green; record timings"
	@echo "  up-rcs-etcd          Apply common + rcs-etcd; wait for green and first manifest publish"
	@echo "  switch-to-vanilla    down + up-vanilla"
	@echo "  switch-to-rcs-etcd   down + up-rcs-etcd"
	@echo "  smoke                Run the benchmark + emit METRIC lines for the active config"
	@echo "  smoke-cluster-state  Longer (~3-4 min) exercise of cluster-state ops + topology + read-path"
	@echo "  failover-suite       Run all failover scenarios with PASS/FAIL assertions"
	@echo "  failover-clean-kill  Scenario 1 only — kill manager + SLA assertion"
	@echo "  failover-state-diff  Scenario 2 only — declarative state diff across kill"
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

benchmark-setup:
	@test -d $(OSB_REPO) || { \
	  echo "ERROR: $(OSB_REPO) not found."; \
	  echo "       Clone the runner next to this repo:"; \
	  echo "         git clone https://github.com/opensearch-project/opensearch-benchmark.git $(OSB_REPO)"; \
	  exit 1; \
	}
	@test -d $(OSB_WORKLOADS) || { \
	  echo "ERROR: $(OSB_WORKLOADS) not found."; \
	  echo "       Clone the workloads next to this repo:"; \
	  echo "         git clone https://github.com/opensearch-project/opensearch-benchmark-workloads.git $(OSB_WORKLOADS)"; \
	  exit 1; \
	}
	@echo "==> installing opensearch-benchmark (user-local) from $(OSB_REPO)"
	cd $(OSB_REPO) && python3 -m pip install --user --upgrade --editable '.[develop]'
	@echo "==> opensearch-benchmark installed at $$HOME/.local/bin/opensearch-benchmark"

smoke:
	bash $(CURDIR)/smoke.sh

smoke-cluster-state:
	bash $(CURDIR)/smoke-cluster-state.sh

failover-clean-kill:
	bash $(CURDIR)/failover/scenario-clean-kill.sh

failover-state-diff:
	bash $(CURDIR)/failover/scenario-state-diff.sh

failover-suite: failover-clean-kill failover-state-diff
	@echo
	@echo "==> Failover suite complete (all scenarios passed)"

down:
	kubectl delete namespace $(NAMESPACE) --ignore-not-found
	kubectl apply -f $(CURDIR)/kind/namespaces.yaml

clean: down
	-docker rmi $(IMAGE)
