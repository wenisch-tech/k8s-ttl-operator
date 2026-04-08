# ChronoReaper Helm Chart

ChronoReaper is a Kubernetes operator that deletes resources when the
`wenisch.tech/ttl` annotation timestamp has passed.

This chart deploys the ChronoReaper operator and its required RBAC resources.

## Prerequisites

- Kubernetes `>= 1.25`
- Helm `>= 3.10`
- Cluster-admin (or equivalent) permissions for cluster-scoped RBAC resources

## Install

```bash
helm install chronoreaper ./helm/chronoreaper \
  --namespace chronoreaper \
  --create-namespace
```

## Upgrade

```bash
helm upgrade chronoreaper ./helm/chronoreaper \
  --namespace chronoreaper
```

## Uninstall

```bash
helm uninstall chronoreaper --namespace chronoreaper
```

## Configuration

Key values from `values.yaml`:

| Key | Type | Default | Description |
|---|---|---|---|
| `replicaCount` | int | `1` | Number of operator replicas |
| `image.repository` | string | `wenisch-tech/chronoreaper` | Operator image repository |
| `image.tag` | string | `""` | Image tag (falls back to chart `appVersion`) |
| `image.pullPolicy` | string | `IfNotPresent` | Image pull policy |
| `operator.checkInterval` | string | `60s` | TTL scan interval |
| `operator.dryRun` | bool | `false` | Log deletions without actually deleting |
| `serviceAccount.create` | bool | `true` | Create service account |
| `serviceAccount.name` | string | `chronoreaper` | Service account name |
| `resources.requests.cpu` | string | `100m` | Requested CPU |
| `resources.requests.memory` | string | `128Mi` | Requested memory |
| `resources.limits.cpu` | string | `500m` | CPU limit |
| `resources.limits.memory` | string | `256Mi` | Memory limit |
| `nodeSelector` | object | `{}` | Pod node selector |
| `tolerations` | list | `[]` | Pod tolerations |
| `affinity` | object | `{}` | Pod affinity |

### Example overrides

```bash
helm install chronoreaper ./helm/chronoreaper \
  --namespace chronoreaper \
  --create-namespace \
  --set operator.checkInterval=30s \
  --set operator.dryRun=true
```

## Health and Metrics

- Liveness: `/q/health/live` on port `8081`
- Readiness: `/q/health/ready` on port `8081`
- Metrics: `/q/metrics` on port `8081`

## Security Notes

The chart enables a restrictive default security context:

- `runAsNonRoot: true`
- `allowPrivilegeEscalation: false`
- `readOnlyRootFilesystem: true`
- Linux capabilities drop all (`ALL`)


## Source

- Repository: https://github.com/wenisch-tech/ChronoReaper
