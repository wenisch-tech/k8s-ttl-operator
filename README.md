# ChronoReaper

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17-orange.svg)](https://adoptium.net)
[![Quarkus](https://img.shields.io/badge/Quarkus-3.8-blueviolet.svg)](https://quarkus.io)

A **Kubernetes Operator** that automatically deletes any resource once its
`wenisch.tech/ttl` annotation timestamp has passed.

Built with [Quarkus](https://quarkus.io) and the
[Java Operator SDK (JOSDK)](https://javaoperatorsdk.io). Deployable via
[Helm](https://helm.sh) or the
[Operator Lifecycle Manager (OLM)](https://olm.operatorframework.io) /
[OperatorHub.io](https://operatorhub.io).

---

## How it works

Add the `wenisch.tech/ttl` annotation to **any** Kubernetes resource. When the
specified timestamp is reached, the operator automatically deletes the resource.

The operator polls all resource types every 60 seconds (configurable).

---

## Annotation format

Two timestamp formats are supported:

### ISO-8601 UTC

```yaml
metadata:
  annotations:
    wenisch.tech/ttl: "2025-12-31T23:59:59Z"
```

### Unix epoch seconds

```yaml
metadata:
  annotations:
    wenisch.tech/ttl: "1775666164"
```

> If the value cannot be parsed in either format, an **error** is logged and
> the resource is left untouched.

---

## Supported resource types

| Category         | Resources |
|------------------|-----------|
| **Workloads**    | `Pod`, `Deployment`, `ReplicaSet`, `StatefulSet`, `DaemonSet`, `Job`, `CronJob` |
| **Networking**   | `Service`, `Ingress` |
| **Configuration**| `ConfigMap`, `Secret`, `ServiceAccount` |
| **Cluster-scoped** | `Namespace`, `CustomResourceDefinition`, `ClusterRole`, `ClusterRoleBinding` |
| **Custom resources** | All installed CRDs are discovered and checked dynamically |

---

## Quick start

### Option 1 — Helm

```bash
helm install chrono-reaper helm/chrono-reaper \
  --namespace chrono-reaper \
  --create-namespace
```

Common overrides:

```bash
helm install chrono-reaper helm/chrono-reaper \
  --namespace chrono-reaper \
  --create-namespace \
  --set operator.checkInterval=30s \   # poll every 30 s
  --set operator.dryRun=true           # log only, no actual deletion
```

### Option 2 — OLM / OperatorHub

1. Install OLM (if not already present):

   ```bash
   operator-sdk olm install
   ```

2. Apply the bundle manifests:

   ```bash
   kubectl apply -f bundle/manifests/
   ```

3. Or search for **ChronoReaper** on [OperatorHub.io](https://operatorhub.io)
   and follow the one-click install.

---

## Example resources

### Pod — expires at a fixed date

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: temp-pod
  namespace: default
  annotations:
    wenisch.tech/ttl: "2025-06-30T00:00:00Z"
spec:
  containers:
    - name: app
      image: alpine:latest
      command: ["sleep", "infinity"]
```

### Deployment — expires via Unix epoch

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: temp-deploy
  namespace: default
  annotations:
    wenisch.tech/ttl: "1775666164"   # 2026-03-06T06:36:04Z
spec:
  replicas: 1
  selector:
    matchLabels:
      app: temp
  template:
    metadata:
      labels:
        app: temp
    spec:
      containers:
        - name: app
          image: nginx:latest
```

### Namespace — self-destructing sandbox

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: sandbox-abc123
  annotations:
    wenisch.tech/ttl: "1800000000"
```

---

## Configuration

| Helm value | Env variable | Default | Description |
|---|---|---|---|
| `operator.checkInterval` | `TTL_CHECK_INTERVAL` | `60s` | How often to scan all resources (ISO-8601 duration) |
| `operator.dryRun` | `TTL_DRY_RUN` | `false` | Log deletions without executing them |

### `application.properties` keys

| Key | Default | Description |
|-----|---------|-------------|
| `ttl.check.interval` | `60s` | Polling interval |
| `ttl.dry-run` | `false` | Dry-run mode |

---

## Observability

### Health probes

| Probe | Path | Port |
|-------|------|------|
| Liveness | `/q/health/live` | `8081` |
| Readiness | `/q/health/ready` | `8081` |

### Prometheus metrics (port `8081`, path `/q/metrics`)

| Metric | Description |
|--------|-------------|
| `ttl_operator_resources_deleted_total` | Total resources deleted since startup |
| `ttl_operator_errors_total` | Total errors encountered since startup |

---

## Development

### Prerequisites

| Tool | Minimum version |
|------|-----------------|
| Java | 17 |
| Apache Maven | 3.9 |
| Docker / Podman | any recent version |

### Build

```bash
mvn package -DskipTests
```

### Run tests

```bash
mvn test
```

### Run locally (dev mode with live-reload)

```bash
mvn quarkus:dev
```

> Requires a reachable kubeconfig (`~/.kube/config`) or an in-cluster
> `KUBERNETES_SERVICE_HOST`. Outside a cluster, ensure
> `quarkus.kubernetes-client.devservices.enabled=false` is set (already the
> default in `%dev` profile).

### Build the container image

```bash
docker build -t wenischtech/chrono-reaper:1.0.0 .
docker push wenischtech/chrono-reaper:1.0.0
```

---

## Repository layout

```
ChronoReaper/
├── src/
│   ├── main/java/tech/wenisch/operator/
│   │   ├── TtlOperatorApplication.java   # Quarkus / JOSDK entry point
│   │   └── TtlController.java            # Scheduled TTL-check logic
│   └── main/resources/
│       └── application.properties        # Runtime configuration defaults
├── helm/chrono-reaper/                # Helm chart
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
│       ├── deployment.yaml
│       ├── serviceaccount.yaml
│       ├── clusterrole.yaml              # cluster-wide get/list/watch/delete
│       ├── clusterrolebinding.yaml
│       └── service.yaml
├── bundle/                               # OLM bundle for OperatorHub.io
│   ├── manifests/
│   │   └── chrono-reaper.v1.0.0.clusterserviceversion.yaml
│   ├── metadata/
│   │   └── annotations.yaml
│   ├── tests/scorecard/config.yaml
│   └── Dockerfile
├── Dockerfile                            # Operator container image (multi-stage)
└── pom.xml
```

---

## OLM / OperatorHub.io publishing

1. **Validate** the bundle with the Operator SDK scorecard:

   ```bash
   operator-sdk scorecard bundle/
   ```

2. **Build & push** the bundle image:

   ```bash
   docker build -t wenischtech/chrono-reaper-bundle:1.0.0 bundle/
   docker push wenischtech/chrono-reaper-bundle:1.0.0
   ```

3. **Submit** to OperatorHub.io by following the
   [contribution guide](https://operatorhub.io/contribute).

---

## License

[Apache License 2.0](LICENSE)
