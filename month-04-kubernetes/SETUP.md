# Month 4 — Kubernetes: Setup Guide

## Dependencies

Uncomment in `month-04-kubernetes/build.gradle` when connecting to a real cluster:

```gradle
dependencies {
    // fabric8 — most popular Java client for Kubernetes API
    implementation 'io.fabric8:kubernetes-client:6.10.0'
    implementation 'io.fabric8:kubernetes-model-core:6.10.0'
}
```

> **Note:** All demos run as-is without a cluster — they use simulated Kubernetes API clients. Uncomment dependencies when you want to target a real cluster.

---

## External Tools

### 1. minikube (local Kubernetes cluster — recommended)
> Used with: all Week 13–16 demos when connecting to a real cluster

**Install:**
```bash
# macOS
brew install minikube

# Start a local cluster (uses Docker as driver)
minikube start --driver=docker --cpus=2 --memory=4g

# Verify
kubectl get nodes
minikube status
```

**Useful minikube commands:**
```bash
# Open Kubernetes dashboard in browser
minikube dashboard

# Get the cluster IP (for accessing NodePort services)
minikube ip

# Stop / delete cluster
minikube stop
minikube delete
```

---

### 2. kind (Kubernetes IN Docker — lightweight alternative)
> Good for CI and multi-node local clusters

**Install:**
```bash
brew install kind

# Create a 3-node cluster
cat <<EOF | kind create cluster --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
  - role: worker
  - role: worker
EOF

# Verify
kubectl get nodes
```

---

### 3. kubectl
> CLI for interacting with any Kubernetes cluster

**Install:**
```bash
brew install kubectl

# Point to minikube
kubectl config use-context minikube

# Common commands used in demos
kubectl get pods -A                          # All pods
kubectl get deployments
kubectl describe pod <pod-name>
kubectl logs <pod-name> -f                   # Stream logs
kubectl exec -it <pod-name> -- /bin/sh       # Shell into pod
kubectl port-forward svc/my-service 8080:80  # Forward local port
```

---

### 4. Helm
> Package manager for Kubernetes — used in Month 4 Supplement Course

**Install:**
```bash
brew install helm

# Add common repos
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

# Example: deploy PostgreSQL with StatefulSet (Week 14)
helm install postgres bitnami/postgresql \
  --set auth.postgresPassword=secret \
  --set primary.persistence.size=1Gi
```

---

### 5. HashiCorp Vault
> Used with: `SecretsManagementDemo` (Week 16) — dynamic secrets, key rotation, audit log

**Docker (dev mode — no persistence, for learning only):**
```bash
docker run -d \
  --name vault \
  -p 8200:8200 \
  --cap-add=IPC_LOCK \
  -e VAULT_DEV_ROOT_TOKEN_ID=root \
  hashicorp/vault:latest

# Set env vars
export VAULT_ADDR='http://localhost:8200'
export VAULT_TOKEN='root'

# Verify
vault status
```

**Basic Vault operations:**
```bash
# Write a secret
vault kv put secret/myapp db-password=supersecret api-key=abc123

# Read a secret
vault kv get secret/myapp

# Enable dynamic database secrets (generates temp DB credentials)
vault secrets enable database

# List all secrets
vault kv list secret/
```

**Vault + Kubernetes integration:**
```bash
# Enable Kubernetes auth method
vault auth enable kubernetes

vault write auth/kubernetes/config \
  kubernetes_host="https://$(minikube ip):8443"

# Now pods can authenticate with their ServiceAccount token
```

---

### 6. Lens (Kubernetes IDE — optional)
> Visual dashboard for managing clusters — covered in Month 4 Supplement Course

```bash
# macOS
brew install --cask lens
# Or download from: https://k8slens.dev/
```

---

## fabric8 Client Setup

When you uncomment the fabric8 dependency, the client auto-discovers your cluster from `~/.kube/config`:

```java
// Connects automatically to current kubectl context
try (KubernetesClient client = new KubernetesClientBuilder().build()) {
    client.pods().inNamespace("default").list()
          .getItems()
          .forEach(p -> System.out.println(p.getMetadata().getName()));
}
```

**Target a specific cluster:**
```java
Config config = new ConfigBuilder()
    .withMasterUrl("https://my-cluster:6443")
    .withOauthToken("my-token")
    .build();
KubernetesClient client = new KubernetesClientBuilder().withConfig(config).build();
```

---

## Quick Start

```bash
# 1. Start minikube
minikube start --driver=docker

# 2. Compile the module
./gradlew :month-04-kubernetes:compileJava

# 3. Run K8s client demo (simulated — no cluster needed)
./gradlew :month-04-kubernetes:run \
  -PmainClass=com.deepdive.month04.week13.KubernetesClientDemo

# 4. Run graceful shutdown demo
./gradlew :month-04-kubernetes:run \
  -PmainClass=com.deepdive.month04.week14.GracefulShutdownDemo

# 5. Run health check demo (exposes /health endpoints on port 8081)
./gradlew :month-04-kubernetes:run \
  -PmainClass=com.deepdive.month04.week14.HealthCheckDemo
# Then: curl http://localhost:8081/health/live
#       curl http://localhost:8081/health/ready
```
