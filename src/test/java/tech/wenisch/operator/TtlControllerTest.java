package tech.wenisch.operator;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TtlController}.
 *
 * <p>These tests focus on the TTL annotation parsing and the
 * {@link TtlController#maybeDelete(HasMetadata, Runnable)} core logic without
 * requiring a running Kubernetes cluster.
 */
@ExtendWith(MockitoExtension.class)
class TtlControllerTest {

    @Mock
    KubernetesClient kubernetesClient;

    private TtlController controller;

    @BeforeEach
    void setUp() {
        MeterRegistry registry = new SimpleMeterRegistry();
        controller = new TtlController();
        controller.client = kubernetesClient;
        controller.meterRegistry = registry;
        controller.dryRun = false;
        // Manually initialise counters (simulating @PostConstruct)
        controller.init();
    }

    // -------------------------------------------------------------------------
    // parseTtl tests
    // -------------------------------------------------------------------------

    @Test
    void parseTtl_validTimestamp_returnsInstant() {
        Pod pod = podWithAnnotation("2020-01-01T00:00:00Z");
        Optional<Instant> result = controller.parseTtl("2020-01-01T00:00:00Z", pod);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void parseTtl_invalidTimestamp_returnsEmpty() {
        Pod pod = podWithAnnotation("not-a-date");
        Optional<Instant> result = controller.parseTtl("not-a-date", pod);
        assertThat(result).isEmpty();
    }

    @Test
    void parseTtl_timestampWithLeadingSpaces_returnsInstant() {
        Pod pod = podWithAnnotation("  2025-06-01T12:00:00Z  ");
        Optional<Instant> result = controller.parseTtl("  2025-06-01T12:00:00Z  ", pod);
        assertThat(result).isPresent();
    }

    @Test
    void parseTtl_unixEpochSeconds_returnsInstant() {
        long epochSeconds = 1775666164L;
        Pod pod = podWithAnnotation(String.valueOf(epochSeconds));
        Optional<Instant> result = controller.parseTtl(String.valueOf(epochSeconds), pod);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(Instant.ofEpochSecond(epochSeconds));
    }

    @Test
    void parseTtl_unixEpochWithWhitespace_returnsInstant() {
        Pod pod = podWithAnnotation("  1775666164  ");
        Optional<Instant> result = controller.parseTtl("  1775666164  ", pod);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(Instant.ofEpochSecond(1775666164L));
    }

    @Test
    void parseTtl_epochZero_returnsEpochInstant() {
        Pod pod = podWithAnnotation("0");
        Optional<Instant> result = controller.parseTtl("0", pod);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(Instant.EPOCH);
    }

    // -------------------------------------------------------------------------
    // maybeDelete tests
    // -------------------------------------------------------------------------

    @Test
    void maybeDelete_noAnnotations_doesNotDelete() {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName("test-pod").withNamespace("default").endMetadata()
                .build();

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verifyNoInteractions(deleteAction);
    }

    @Test
    void maybeDelete_annotationAbsent_doesNotDelete() {
        Pod pod = podWithAnnotation(null);

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verifyNoInteractions(deleteAction);
    }

    @Test
    void maybeDelete_ttlInFuture_doesNotDelete() {
        // TTL is one year from now
        String futureTimestamp = Instant.now().plusSeconds(365L * 24 * 3600).toString();
        Pod pod = podWithAnnotation(futureTimestamp);

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verifyNoInteractions(deleteAction);
    }

    @Test
    void maybeDelete_ttlExpired_invokesDelete() {
        // TTL was one year ago
        String pastTimestamp = Instant.now().minusSeconds(365L * 24 * 3600).toString();
        Pod pod = podWithAnnotation(pastTimestamp);

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verify(deleteAction, times(1)).run();
    }

    @Test
    void maybeDelete_ttlExpired_dryRunEnabled_doesNotDelete() {
        controller.dryRun = true;
        String pastTimestamp = Instant.now().minusSeconds(3600).toString();
        Pod pod = podWithAnnotation(pastTimestamp);

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verifyNoInteractions(deleteAction);
    }

    @Test
    void maybeDelete_invalidTimestamp_doesNotDelete() {
        Pod pod = podWithAnnotation("bad-value");

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verifyNoInteractions(deleteAction);
    }

    @Test
    void maybeDelete_expiredEpochTimestamp_invokesDelete() {
        // A Unix epoch timestamp well in the past (2001-01-01)
        String pastEpoch = "978307200";
        Pod pod = podWithAnnotation(pastEpoch);

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verify(deleteAction, times(1)).run();
    }

    @Test
    void maybeDelete_futureEpochTimestamp_doesNotDelete() {
        // One year from now as Unix epoch
        long futureEpoch = Instant.now().plusSeconds(365L * 24 * 3600).getEpochSecond();
        Pod pod = podWithAnnotation(String.valueOf(futureEpoch));

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verifyNoInteractions(deleteAction);
    }

    @Test
    void maybeDelete_blankAnnotationValue_doesNotDelete() {
        Pod pod = podWithAnnotation("   ");

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(pod, deleteAction);

        verifyNoInteractions(deleteAction);
    }

    @Test
    void maybeDelete_deleteThrowsException_doesNotPropagate() {
        String pastTimestamp = Instant.now().minusSeconds(3600).toString();
        Pod pod = podWithAnnotation(pastTimestamp);

        Runnable deleteAction = mock(Runnable.class);
        doThrow(new RuntimeException("Simulated API error")).when(deleteAction).run();

        // Should not throw
        controller.maybeDelete(pod, deleteAction);
    }

    @Test
    void maybeDelete_worksForDeployment() {
        String pastTimestamp = Instant.now().minusSeconds(3600).toString();
        Deployment deployment = new DeploymentBuilder()
                .withNewMetadata()
                    .withName("my-deploy")
                    .withNamespace("default")
                    .addToAnnotations(TtlController.TTL_ANNOTATION, pastTimestamp)
                .endMetadata()
                .build();

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(deployment, deleteAction);

        verify(deleteAction, times(1)).run();
    }

    @Test
    void maybeDelete_worksForNamespace_clusterScoped() {
        String pastTimestamp = Instant.now().minusSeconds(3600).toString();
        Namespace namespace = new NamespaceBuilder()
                .withNewMetadata()
                    .withName("temp-namespace")
                    .addToAnnotations(TtlController.TTL_ANNOTATION, pastTimestamp)
                .endMetadata()
                .build();

        Runnable deleteAction = mock(Runnable.class);
        controller.maybeDelete(namespace, deleteAction);

        verify(deleteAction, times(1)).run();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Pod podWithAnnotation(String ttlValue) {
        if (ttlValue != null) {
            return new PodBuilder()
                    .withNewMetadata()
                        .withName("test-pod")
                        .withNamespace("default")
                        .addToAnnotations(TtlController.TTL_ANNOTATION, ttlValue)
                    .endMetadata()
                    .build();
        }
        return new PodBuilder()
                .withNewMetadata()
                    .withName("test-pod")
                    .withNamespace("default")
                .endMetadata()
                .build();
    }
}
