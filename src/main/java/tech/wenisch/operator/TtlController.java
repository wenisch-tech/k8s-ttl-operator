package tech.wenisch.operator;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apps.*;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Core controller that periodically scans all Kubernetes resource types for the
 * {@code wenisch.tech/ttl} annotation and deletes resources whose TTL has expired.
 *
 * <p>The annotation value may be either:
 * <ul>
 *   <li>an ISO-8601 UTC timestamp, e.g. {@code 2024-12-31T23:59:59Z}, or</li>
 *   <li>a Unix epoch timestamp in seconds, e.g. {@code 1775666164}</li>
 * </ul>
 *
 * <p>If the value cannot be parsed in either format, an error is logged and the
 * resource is left untouched.
 *
 * <p>The controller runs on a configurable interval (default: every 60 seconds) and
 * covers the following resource categories:
 * <ul>
 *   <li>Workloads: Pod, Deployment, ReplicaSet, StatefulSet, DaemonSet, Job, CronJob</li>
 *   <li>Networking: Service, Ingress</li>
 *   <li>Config: ConfigMap, Secret</li>
 *   <li>Cluster-scoped: Namespace, CustomResourceDefinition, ServiceAccount,
 *       ClusterRole, ClusterRoleBinding</li>
 *   <li>Custom resources (all installed CRDs)</li>
 * </ul>
 */
@ApplicationScoped
public class TtlController {

    static final String TTL_ANNOTATION = "wenisch.tech/ttl";

    private static final Logger LOG = Logger.getLogger(TtlController.class);

    @Inject
    KubernetesClient client;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "ttl.dry-run", defaultValue = "false")
    boolean dryRun;

    private Counter deletedCounter;
    private Counter errorCounter;

    @jakarta.annotation.PostConstruct
    void init() {
        deletedCounter = Counter.builder("ttl_operator_resources_deleted_total")
                .description("Total number of resources deleted by the TTL operator")
                .register(meterRegistry);
        errorCounter = Counter.builder("ttl_operator_errors_total")
                .description("Total number of errors encountered by the TTL operator")
                .register(meterRegistry);
    }

    /**
     * Scheduled task that runs at a configurable interval to check all resource types.
     * Default interval is every 60 seconds, configurable via {@code ttl.check.interval}.
     */
    @Scheduled(every = "{ttl.check.interval:60s}")
    void checkAllResources() {
        LOG.debug("Running TTL check across all resource types...");

        // Namespaced workload resources
        checkPodsInAllNamespaces();
        checkDeployments();
        checkReplicaSets();
        checkStatefulSets();
        checkDaemonSets();
        checkJobs();
        checkCronJobs();

        // Namespaced networking / config
        checkServices();
        checkIngresses();
        checkConfigMaps();
        checkSecrets();
        checkServiceAccounts();

        // Cluster-scoped resources
        checkNamespaces();
        checkCustomResourceDefinitions();
        checkClusterRoles();
        checkClusterRoleBindings();

        // Dynamic: check instances of every installed CRD
        checkCustomResources();

        LOG.debug("TTL check completed.");
    }

    // -------------------------------------------------------------------------
    // Workloads
    // -------------------------------------------------------------------------

    void checkPodsInAllNamespaces() {
        try {
            client.pods().inAnyNamespace().list().getItems()
                    .forEach(pod -> maybeDelete(pod,
                            () -> client.pods()
                                    .inNamespace(pod.getMetadata().getNamespace())
                                    .withName(pod.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("pods", e);
        }
    }

    void checkDeployments() {
        try {
            client.apps().deployments().inAnyNamespace().list().getItems()
                    .forEach(d -> maybeDelete(d,
                            () -> client.apps().deployments()
                                    .inNamespace(d.getMetadata().getNamespace())
                                    .withName(d.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("deployments", e);
        }
    }

    void checkReplicaSets() {
        try {
            client.apps().replicaSets().inAnyNamespace().list().getItems()
                    .forEach(rs -> maybeDelete(rs,
                            () -> client.apps().replicaSets()
                                    .inNamespace(rs.getMetadata().getNamespace())
                                    .withName(rs.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("replicasets", e);
        }
    }

    void checkStatefulSets() {
        try {
            client.apps().statefulSets().inAnyNamespace().list().getItems()
                    .forEach(ss -> maybeDelete(ss,
                            () -> client.apps().statefulSets()
                                    .inNamespace(ss.getMetadata().getNamespace())
                                    .withName(ss.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("statefulsets", e);
        }
    }

    void checkDaemonSets() {
        try {
            client.apps().daemonSets().inAnyNamespace().list().getItems()
                    .forEach(ds -> maybeDelete(ds,
                            () -> client.apps().daemonSets()
                                    .inNamespace(ds.getMetadata().getNamespace())
                                    .withName(ds.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("daemonsets", e);
        }
    }

    void checkJobs() {
        try {
            client.batch().v1().jobs().inAnyNamespace().list().getItems()
                    .forEach(j -> maybeDelete(j,
                            () -> client.batch().v1().jobs()
                                    .inNamespace(j.getMetadata().getNamespace())
                                    .withName(j.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("jobs", e);
        }
    }

    void checkCronJobs() {
        try {
            client.batch().v1().cronjobs().inAnyNamespace().list().getItems()
                    .forEach(cj -> maybeDelete(cj,
                            () -> client.batch().v1().cronjobs()
                                    .inNamespace(cj.getMetadata().getNamespace())
                                    .withName(cj.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("cronjobs", e);
        }
    }

    // -------------------------------------------------------------------------
    // Networking / Config
    // -------------------------------------------------------------------------

    void checkServices() {
        try {
            client.services().inAnyNamespace().list().getItems()
                    .forEach(svc -> maybeDelete(svc,
                            () -> client.services()
                                    .inNamespace(svc.getMetadata().getNamespace())
                                    .withName(svc.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("services", e);
        }
    }

    void checkIngresses() {
        try {
            client.network().v1().ingresses().inAnyNamespace().list().getItems()
                    .forEach(ing -> maybeDelete(ing,
                            () -> client.network().v1().ingresses()
                                    .inNamespace(ing.getMetadata().getNamespace())
                                    .withName(ing.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("ingresses", e);
        }
    }

    void checkConfigMaps() {
        try {
            client.configMaps().inAnyNamespace().list().getItems()
                    .forEach(cm -> maybeDelete(cm,
                            () -> client.configMaps()
                                    .inNamespace(cm.getMetadata().getNamespace())
                                    .withName(cm.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("configmaps", e);
        }
    }

    void checkSecrets() {
        try {
            client.secrets().inAnyNamespace().list().getItems()
                    .forEach(s -> maybeDelete(s,
                            () -> client.secrets()
                                    .inNamespace(s.getMetadata().getNamespace())
                                    .withName(s.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("secrets", e);
        }
    }

    void checkServiceAccounts() {
        try {
            client.serviceAccounts().inAnyNamespace().list().getItems()
                    .forEach(sa -> maybeDelete(sa,
                            () -> client.serviceAccounts()
                                    .inNamespace(sa.getMetadata().getNamespace())
                                    .withName(sa.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("serviceaccounts", e);
        }
    }

    // -------------------------------------------------------------------------
    // Cluster-scoped resources
    // -------------------------------------------------------------------------

    void checkNamespaces() {
        try {
            client.namespaces().list().getItems()
                    .forEach(ns -> maybeDelete(ns,
                            () -> client.namespaces()
                                    .withName(ns.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("namespaces", e);
        }
    }

    void checkCustomResourceDefinitions() {
        try {
            client.apiextensions().v1().customResourceDefinitions().list().getItems()
                    .forEach(crd -> maybeDelete(crd,
                            () -> client.apiextensions().v1().customResourceDefinitions()
                                    .withName(crd.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("customresourcedefinitions", e);
        }
    }

    void checkClusterRoles() {
        try {
            client.rbac().clusterRoles().list().getItems()
                    .forEach(cr -> maybeDelete(cr,
                            () -> client.rbac().clusterRoles()
                                    .withName(cr.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("clusterroles", e);
        }
    }

    void checkClusterRoleBindings() {
        try {
            client.rbac().clusterRoleBindings().list().getItems()
                    .forEach(crb -> maybeDelete(crb,
                            () -> client.rbac().clusterRoleBindings()
                                    .withName(crb.getMetadata().getName())
                                    .delete()));
        } catch (Exception e) {
            handleError("clusterrolebindings", e);
        }
    }

    // -------------------------------------------------------------------------
    // Dynamic CRD instances
    // -------------------------------------------------------------------------

    /**
     * Dynamically discovers all installed CRDs and checks their instances for
     * expired TTL annotations.
     */
    void checkCustomResources() {
        try {
            List<CustomResourceDefinition> crds =
                    client.apiextensions().v1().customResourceDefinitions().list().getItems();

            for (CustomResourceDefinition crd : crds) {
                String group = crd.getSpec().getGroup();
                String kind = crd.getSpec().getNames().getKind();
                String plural = crd.getSpec().getNames().getPlural();
                String scope = crd.getSpec().getScope(); // Namespaced or Cluster

                // Use the storage version
                Optional<String> storageVersion = crd.getSpec().getVersions().stream()
                        .filter(v -> Boolean.TRUE.equals(v.getStorage()))
                        .map(v -> v.getName())
                        .findFirst();

                if (storageVersion.isEmpty()) {
                    continue;
                }

                ResourceDefinitionContext context = new ResourceDefinitionContext.Builder()
                        .withGroup(group)
                        .withVersion(storageVersion.get())
                        .withPlural(plural)
                        .withKind(kind)
                        .withNamespaced("Namespaced".equals(scope))
                        .build();

                try {
                    List<GenericKubernetesResource> instances =
                            client.genericKubernetesResources(context).inAnyNamespace().list().getItems();

                    for (GenericKubernetesResource instance : instances) {
                        maybeDelete(instance, () -> {
                            String namespace = instance.getMetadata().getNamespace();
                            String name = instance.getMetadata().getName();
                            if (namespace != null && !namespace.isBlank()) {
                                client.genericKubernetesResources(context)
                                        .inNamespace(namespace).withName(name).delete();
                            } else {
                                client.genericKubernetesResources(context)
                                        .withName(name).delete();
                            }
                        });
                    }
                } catch (Exception e) {
                    LOG.warnf("Failed to check custom resources for CRD %s/%s: %s",
                            group, kind, e.getMessage());
                }
            }
        } catch (Exception e) {
            handleError("custom-resources", e);
        }
    }

    // -------------------------------------------------------------------------
    // Core TTL logic
    // -------------------------------------------------------------------------

    /**
     * Checks whether the given resource has an expired TTL annotation and, if so,
     * invokes the provided delete action.
     *
     * @param resource     the Kubernetes resource to inspect
     * @param deleteAction the action that deletes the resource
     */
    void maybeDelete(HasMetadata resource, Runnable deleteAction) {
        Map<String, String> annotations = resource.getMetadata().getAnnotations();
        if (annotations == null) {
            return;
        }

        String ttlValue = annotations.get(TTL_ANNOTATION);
        if (ttlValue == null || ttlValue.isBlank()) {
            return;
        }

        Optional<Instant> expiry = parseTtl(ttlValue, resource);
        if (expiry.isEmpty()) {
            return;
        }

        if (Instant.now().isAfter(expiry.get())) {
            String name = resource.getMetadata().getName();
            String namespace = resource.getMetadata().getNamespace();
            String kind = resource.getKind();

            if (dryRun) {
                LOG.infof("[DRY-RUN] Would delete %s %s/%s (TTL was %s)",
                        kind, namespace, name, ttlValue);
                return;
            }

            LOG.infof("Deleting %s %s/%s — TTL %s has expired",
                    kind, namespace != null ? namespace : "(cluster)", name, ttlValue);
            try {
                deleteAction.run();
                if (deletedCounter != null) {
                    deletedCounter.increment();
                }
            } catch (Exception e) {
                LOG.errorf(e, "Failed to delete %s %s/%s", kind, namespace, name);
                if (errorCounter != null) {
                    errorCounter.increment();
                }
            }
        }
    }

    /**
     * Parses a TTL annotation value into an {@link Instant}.
     *
     * <p>Two formats are accepted (leading/trailing whitespace is ignored):
     * <ol>
     *   <li><b>ISO-8601 UTC</b> — e.g. {@code 2024-12-31T23:59:59Z}</li>
     *   <li><b>Unix epoch seconds</b> — e.g. {@code 1775666164}</li>
     * </ol>
     *
     * <p>If the value cannot be parsed in either format an error is logged and
     * {@link Optional#empty()} is returned so the resource is left untouched.
     *
     * @param value    the raw annotation value
     * @param resource the resource owning the annotation (used for log context)
     * @return an {@link Optional} containing the parsed instant, or empty if invalid
     */
    Optional<Instant> parseTtl(String value, HasMetadata resource) {
        String trimmed = value.trim();

        // 1. Try ISO-8601 first (e.g. "2024-12-31T23:59:59Z")
        try {
            return Optional.of(Instant.parse(trimmed));
        } catch (Exception ignored) {
            // fall through to epoch parsing
        }

        // 2. Try Unix epoch seconds (e.g. "1775666164")
        try {
            long epochSeconds = Long.parseLong(trimmed);
            return Optional.of(Instant.ofEpochSecond(epochSeconds));
        } catch (NumberFormatException ignored) {
            // fall through to error
        }

        LOG.errorf("Cannot parse %s annotation value '%s' on %s/%s: " +
                        "expected an ISO-8601 UTC timestamp (e.g. 2024-12-31T23:59:59Z) " +
                        "or a Unix epoch in seconds (e.g. 1775666164)",
                TTL_ANNOTATION, value,
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName());
        return Optional.empty();
    }

    private void handleError(String resourceType, Exception e) {
        LOG.errorf(e, "Error while checking TTL for resource type: %s", resourceType);
        if (errorCounter != null) {
            errorCounter.increment();
        }
    }
}
