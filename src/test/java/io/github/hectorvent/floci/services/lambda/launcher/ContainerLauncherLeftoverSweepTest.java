package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.model.Container;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The startup sweep that removes the Lambda containers a previous run of this Floci left on the
 * Docker daemon. Docker is a mock: the listing returns every container whatever the label filter
 * says, so the sweep's own ownership checks are what these tests exercise.
 *
 * <p>Plain {@code mock()} rather than the Mockito extension, for the strict-stubbing reason given
 * in {@link ContainerLauncherVolumeNamingTest}.
 */
class ContainerLauncherLeftoverSweepTest {

    private static final String OWNER = "4566";
    private static final String CURRENT_RUN = "run-current";

    private ContainerLifecycleManager lifecycleManager;
    private EmulatorConfig.LambdaServiceConfig lambdaConfig;
    private EmulatorConfig config;
    private List<Container> listed;

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        DockerClient dockerClient = mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        listed = new ArrayList<>();
        ListContainersCmd list = mock(ListContainersCmd.class, Answers.RETURNS_SELF);
        when(list.exec()).thenAnswer(invocation -> List.copyOf(listed));
        when(dockerClient.listContainersCmd()).thenReturn(list);
        config = config("floci-aws", null, true);
    }

    @Test
    void removesContainersAPreviousRunOfThisFlociLeft() {
        listed.add(container("a1", "/floci-aws-orders-1111", OWNER, "run-previous"));
        listed.add(container("a2", "/floci-aws-billing-2222", OWNER, "run-older"));

        assertTrue(launcher().removeLeftoverContainers(CURRENT_RUN));

        verify(lifecycleManager).removeIfExistsStrict("a1");
        verify(lifecycleManager).removeIfExistsStrict("a2");
    }

    @Test
    void removesAnExitedAndARunningLeftoverAlike() {
        // Docker reports state per container; the sweep lists with showAll, and a running
        // leftover polls a Runtime API port that died with the previous process.
        Container running = container("r1", "/floci-aws-orders-1111", OWNER, "run-previous");
        when(running.getState()).thenReturn("running");
        Container exited = container("e1", "/floci-aws-orders-2222", OWNER, "run-previous");
        when(exited.getState()).thenReturn("exited");
        listed.add(running);
        listed.add(exited);

        assertTrue(launcher().removeLeftoverContainers(CURRENT_RUN));

        verify(lifecycleManager).removeIfExistsStrict("r1");
        verify(lifecycleManager).removeIfExistsStrict("e1");
    }

    @Test
    void leavesTheWarmContainerOfTheCurrentRun() {
        listed.add(container("warm", "/floci-aws-orders-1111", OWNER, CURRENT_RUN));
        listed.add(container("orphan", "/floci-aws-orders-2222", OWNER, "run-previous"));

        assertTrue(launcher().removeLeftoverContainers(CURRENT_RUN));

        verify(lifecycleManager, never()).removeIfExistsStrict("warm");
        verify(lifecycleManager).removeIfExistsStrict("orphan");
    }

    @Test
    void leavesAnotherFlociInstancesContainers() {
        // A different API port is a different Floci on the same daemon.
        listed.add(container("other-port", "/floci-aws-orders-1111", "4577", "run-previous"));
        // A different resource namespace puts a different identity on the owner label.
        listed.add(container("other-ns", "/floci-aws-orders-2222", "ci1/4566", "run-previous"));
        // The same identity but another Lambda name prefix is another deployment's container.
        listed.add(container("other-prefix", "/acme-orders-3333", OWNER, "run-previous"));

        assertTrue(launcher().removeLeftoverContainers(CURRENT_RUN));

        verify(lifecycleManager, never()).removeIfExistsStrict(anyString());
    }

    @Test
    void leavesContainersWithoutTheOwnerLabelOrOfAnotherService() {
        Container unlabelled = container("plain", "/floci-aws-orders-1111", null, null);
        when(unlabelled.getLabels()).thenReturn(Map.of());
        listed.add(unlabelled);
        Container otherService = container("ecs", "/floci-aws-orders-2222", OWNER, "run-previous");
        when(otherService.getLabels()).thenReturn(Map.of(
                "io.floci.service", "ecs", "io.floci.owner", OWNER, "io.floci.lambda.run", "run-previous"));
        listed.add(otherService);
        Container nullLabels = container("null", "/floci-aws-orders-3333", OWNER, "run-previous");
        when(nullLabels.getLabels()).thenReturn(null);
        listed.add(nullLabels);

        assertTrue(launcher().removeLeftoverContainers(CURRENT_RUN));

        verify(lifecycleManager, never()).removeIfExistsStrict(anyString());
    }

    @Test
    void honoursTheConfiguredPrefixAndResourceNamespace() {
        config = config("acme", "ci1", true);
        listed.add(container("mine", "/acme-ci1-orders-1111", "ci1/4566", "run-previous"));
        listed.add(container("default-prefix", "/floci-aws-ci1-orders-2222", "ci1/4566", "run-previous"));

        assertTrue(launcher().removeLeftoverContainers(CURRENT_RUN));

        verify(lifecycleManager).removeIfExistsStrict("mine");
        verify(lifecycleManager, never()).removeIfExistsStrict("default-prefix");
    }

    @Test
    void reportsFailureWhenAContainerWillNotGoAndStillRemovesTheRest() {
        listed.add(container("stuck", "/floci-aws-orders-1111", OWNER, "run-previous"));
        listed.add(container("gone", "/floci-aws-orders-2222", OWNER, "run-previous"));
        doThrow(new RuntimeException("conflict")).when(lifecycleManager).removeIfExistsStrict("stuck");

        assertFalse(launcher().removeLeftoverContainers(CURRENT_RUN));

        verify(lifecycleManager).removeIfExistsStrict("gone");
    }

    @Test
    void reportsFailureWhenDockerCannotBeListed() {
        when(lifecycleManager.getDockerClient()).thenThrow(new IllegalStateException("no daemon"));

        assertFalse(launcher().removeLeftoverContainers(CURRENT_RUN));
    }

    @Test
    void sweepRunsOnceAfterASuccessAndIsRetriedAfterAFailure() {
        ContainerLauncher launcher = launcher();
        listed.add(container("orphan", "/floci-aws-orders-1111", OWNER, "run-previous"));
        doThrow(new RuntimeException("conflict")).when(lifecycleManager).removeIfExistsStrict("orphan");

        launcher.sweepLeftoversOnce();
        launcher.sweepLeftoversOnce();
        verify(lifecycleManager, times(2)).removeIfExistsStrict("orphan");

        doNothing().when(lifecycleManager).removeIfExistsStrict("orphan");
        launcher.sweepLeftoversOnce();
        launcher.sweepLeftoversOnce();
        verify(lifecycleManager, times(3)).removeIfExistsStrict("orphan");
    }

    @Test
    void sweepDoesNothingWhenReconciliationIsOff() {
        config = config("floci-aws", null, false);
        listed.add(container("orphan", "/floci-aws-orders-1111", OWNER, "run-previous"));

        launcher().sweepLeftoversOnce();

        verify(lifecycleManager, never()).getDockerClient();
        verify(lifecycleManager, never()).removeIfExistsStrict(anyString());
    }

    private ContainerLauncher launcher() {
        return new ContainerLauncher(mock(ContainerBuilder.class), lifecycleManager,
                mock(ContainerLogStreamer.class), mock(ImageResolver.class),
                mock(RuntimeApiServerFactory.class), mock(DockerHostResolver.class), config,
                mock(EcrRegistryManager.class), mock(LambdaLayerService.class),
                mock(LaunchedContainerAwsEnv.class), mock(LambdaExecutionRoleCredentials.class));
    }

    private static Container container(String id, String name, String owner, String run) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getNames()).thenReturn(new String[] {name});
        Map<String, String> labels = new HashMap<>();
        labels.put("io.floci.service", "lambda");
        if (owner != null) {
            labels.put("io.floci.owner", owner);
            labels.put("floci_owner_port", owner);
        }
        if (run != null) {
            labels.put("io.floci.lambda.run", run);
        }
        when(container.getLabels()).thenReturn(labels);
        return container;
    }

    private EmulatorConfig config(String prefix, String namespace, boolean reconcile) {
        EmulatorConfig result = mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        lambdaConfig = mock(EmulatorConfig.LambdaServiceConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        when(result.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambdaConfig);
        when(lambdaConfig.containerNamePrefix()).thenReturn("floci-aws".equals(prefix)
                ? Optional.empty() : Optional.of(prefix));
        when(lambdaConfig.reconcileContainersOnStartup()).thenReturn(reconcile);
        when(result.docker()).thenReturn(docker);
        when(docker.resourceNamespace()).thenReturn(Optional.ofNullable(namespace));
        when(result.port()).thenReturn(4566);
        return result;
    }
}
