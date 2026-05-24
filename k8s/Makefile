# Makefile for the k8s comparison testbed.
#
# Run from the k8s/ directory. Targets resolve the active kubectl context
# to find the matching Kind cluster name (strips the `kind-` prefix).

SHELL     := /usr/bin/env bash
REPO_ROOT := $(shell git -C $(CURDIR) rev-parse --show-toplevel)
IMAGE     := rcs-etcd-k8s:latest
CONTEXT   := $(shell kubectl config current-context 2>/dev/null)
CLUSTER   := $(patsubst kind-%,%,$(CONTEXT))

# Kind on this project runs on podman; the env var must be set for every
# kind invocation that touches the cluster's containers.
export KIND_EXPERIMENTAL_PROVIDER := podman

.PHONY: image
image:
	@test -n "$(CLUSTER)" || { echo "no active kubectl context — run 'make cluster' first"; exit 1; }
	@echo "==> building rcs-etcd plugin zip"
	cd $(REPO_ROOT) && ./gradlew :plugin:bundlePlugin
	@echo "==> building image $(IMAGE)"
	cd $(REPO_ROOT) && docker build --load -t $(IMAGE) -f k8s/Dockerfile .
	@echo "==> loading image into kind cluster $(CLUSTER)"
	kind load docker-image $(IMAGE) --name $(CLUSTER)
