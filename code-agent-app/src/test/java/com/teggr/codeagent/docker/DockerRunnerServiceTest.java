package com.teggr.codeagent.docker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.StreamType;

class DockerRunnerServiceTest {

    private final DockerClient dockerClient = mock(DockerClient.class);
    private final DockerRunnerService service = new DockerRunnerService(dockerClient, new DockerRunnerProperties());

    @Test
    void launchRejectsMissingGhToken() {
        assertThatThrownBy(() -> service.launch("https://github.com/fanduel/withdrawals", "runner-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("GH_TOKEN environment variable is not set");
    }

    @Test
    void launchDefaultsCopilotTokenToGitTokenWhenNotConfigured() throws Exception {
        DockerRunnerProperties properties = new DockerRunnerProperties();
        properties.setGitToken("git-token");
        DockerRunnerService runnerService = new DockerRunnerService(dockerClient, properties);

        stubInfo("linux");

        CreateContainerCmd createContainerCmd = mock(CreateContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        CreateContainerResponse createContainerResponse = mock(CreateContainerResponse.class);
        when(createContainerResponse.getId()).thenReturn("container-1");
        when(createContainerCmd.exec()).thenReturn(createContainerResponse);
        when(dockerClient.createContainerCmd(any())).thenReturn(createContainerCmd);

        StartContainerCmd startContainerCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("container-1")).thenReturn(startContainerCmd);

        stubPublishedPort("container-1", "4321");

        runnerService.launch("https://github.com/fanduel/withdrawals", "runner-1");

        verify(createContainerCmd).withEnv("GH_TOKEN=git-token",
                "COPILOT_GITHUB_TOKEN=git-token",
                "GIT_REPO_URL=https://github.com/fanduel/withdrawals");
    }

    @Test
    void launchLabelsTheContainerWithRunnerIdentity() throws Exception {
        DockerRunnerProperties properties = new DockerRunnerProperties();
        properties.setGitToken("git-token");
        DockerRunnerService runnerService = new DockerRunnerService(dockerClient, properties);

        stubInfo("linux");

        CreateContainerCmd createContainerCmd = mock(CreateContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        CreateContainerResponse createContainerResponse = mock(CreateContainerResponse.class);
        when(createContainerResponse.getId()).thenReturn("container-1");
        when(createContainerCmd.exec()).thenReturn(createContainerResponse);
        when(dockerClient.createContainerCmd(any())).thenReturn(createContainerCmd);
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));
        stubPublishedPort("container-1", "4321");

        runnerService.launch("https://github.com/fanduel/withdrawals", "runner-1");

        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(createContainerCmd).withLabels(labels.capture());
        assertThat(labels.getValue())
            .containsEntry(DockerRunnerService.MANAGED_LABEL, "true")
            .containsEntry(DockerRunnerService.RUNNER_ID_LABEL, "runner-1")
            .containsEntry(DockerRunnerService.REPO_URL_LABEL, "https://github.com/fanduel/withdrawals")
            .containsEntry(DockerRunnerService.WORKSPACE_PATH_LABEL, "/workspace/withdrawals")
            .containsKey(DockerRunnerService.CREATED_AT_LABEL);
    }

    @Test
    void stopLeavesTheContainerInPlaceSoItCanBeStartedAgain() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        when(dockerClient.stopContainerCmd("container-1")).thenReturn(stopCmd);

        service.stop("container-1");

        verify(dockerClient).stopContainerCmd("container-1");
        verify(dockerClient, never()).removeContainerCmd(any());
    }

    @Test
    void removeStopsAndDeletesTheContainer() {
        StopContainerCmd stopCmd = mock(StopContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        when(dockerClient.stopContainerCmd("container-1")).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd("container-1")).thenReturn(mock(RemoveContainerCmd.class));

        service.remove("container-1");

        verify(dockerClient).stopContainerCmd("container-1");
        verify(dockerClient).removeContainerCmd("container-1");
    }

    @Test
    void restartStartsTheContainerAgainAndPicksUpTheNewHostPort() {
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));
        stubPublishedPort("container-1", "51001");

        ContainerLaunch launch = service.restart("container-1", "/workspace/withdrawals");

        verify(dockerClient).startContainerCmd("container-1");
        assertThat(launch.hostPort()).isEqualTo(51001);
        assertThat(launch.workspacePath()).isEqualTo("/workspace/withdrawals");
        assertThat(launch.devContainerUri()).contains("/workspace/withdrawals");
    }

    @Test
    void listManagedRebuildsRunnersFromLabelsAndReReadsThePublishedPort() {
        Container running = managedContainer("container-1", Map.of(
                DockerRunnerService.MANAGED_LABEL, "true",
                DockerRunnerService.RUNNER_ID_LABEL, "runner-1",
                DockerRunnerService.REPO_URL_LABEL, "https://github.com/fanduel/withdrawals",
                DockerRunnerService.WORKSPACE_PATH_LABEL, "/workspace/withdrawals"));
        Container exited = managedContainer("container-2", Map.of(
                DockerRunnerService.MANAGED_LABEL, "true",
                DockerRunnerService.RUNNER_ID_LABEL, "runner-2",
                DockerRunnerService.REPO_URL_LABEL, "https://github.com/fanduel/withdrawals",
                DockerRunnerService.WORKSPACE_PATH_LABEL, "/workspace/withdrawals"));
        stubListContainers(running, exited);
        stubInspect("container-1", true, "51000");
        stubInspect("container-2", false, null);

        List<ManagedContainer> managed = service.listManaged();

        assertThat(managed).extracting(ManagedContainer::runnerId).containsExactly("runner-1", "runner-2");
        assertThat(managed.get(0).running()).isTrue();
        assertThat(managed.get(0).launch().hostPort()).isEqualTo(51000);
        assertThat(managed.get(0).launch().devContainerUri()).contains("/workspace/withdrawals");
        assertThat(managed.get(1).running()).isFalse();
        assertThat(managed.get(1).launch().hostPort()).isZero();
    }

    @Test
    void listManagedRemovesContainersThatCarryNoRunnerId() {
        stubListContainers(managedContainer("legacy-1", Map.of(DockerRunnerService.MANAGED_LABEL, "true")));
        StopContainerCmd stopCmd = mock(StopContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        when(dockerClient.stopContainerCmd(any())).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd(any())).thenReturn(mock(RemoveContainerCmd.class));

        assertThat(service.listManaged()).isEmpty();

        verify(dockerClient).stopContainerCmd("legacy-1");
        verify(dockerClient).removeContainerCmd("legacy-1");
    }

    private Container managedContainer(String id, Map<String, String> labels) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getLabels()).thenReturn(labels);
        return container;
    }

    private void stubListContainers(Container... containers) {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class, org.mockito.Answers.RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(containers));
    }

    private void stubInspect(String containerId, boolean running, String hostPortSpec) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(running);
        when(response.getState()).thenReturn(state);
        if (running) {
            var networkSettings = networkSettings(hostPortSpec);
            when(response.getNetworkSettings()).thenReturn(networkSettings);
        }
        when(inspectCmd.exec()).thenReturn(response);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
    }

    private void stubPublishedPort(String containerId, String hostPortSpec) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        var networkSettings = networkSettings(hostPortSpec);
        when(response.getNetworkSettings()).thenReturn(networkSettings);
        when(inspectCmd.exec()).thenReturn(response);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
    }

    private com.github.dockerjava.api.model.NetworkSettings networkSettings(String hostPortSpec) {
        com.github.dockerjava.api.model.NetworkSettings networkSettings =
                mock(com.github.dockerjava.api.model.NetworkSettings.class);
        com.github.dockerjava.api.model.Ports ports = mock(com.github.dockerjava.api.model.Ports.class);
        com.github.dockerjava.api.model.Ports.Binding binding = mock(com.github.dockerjava.api.model.Ports.Binding.class);
        when(binding.getHostPortSpec()).thenReturn(hostPortSpec);
        Map<ExposedPort, com.github.dockerjava.api.model.Ports.Binding[]> bindings =
                Map.of(ExposedPort.tcp(4321), new com.github.dockerjava.api.model.Ports.Binding[] {binding});
        when(ports.getBindings()).thenReturn(bindings);
        when(networkSettings.getPorts()).thenReturn(ports);
        return networkSettings;
    }

    @Test
    void socketBindDefaultsToTheDockerCompatibilityPath() {
        stubInfo("linux");

        Bind bind = service.resolveDockerSocketBind();

        assertThat(bind.getPath()).isEqualTo("/var/run/docker.sock");
        assertThat(bind.getVolume().getPath()).isEqualTo("/var/run/docker.sock");
    }

    @Test
    void socketBindHonoursTheConfiguredSourcePath() {
        stubInfo("linux");
        DockerRunnerProperties properties = new DockerRunnerProperties();
        properties.setDockerSocketPath("/run/user/1000/podman/podman.sock");

        Bind bind = new DockerRunnerService(dockerClient, properties).resolveDockerSocketBind();

        assertThat(bind.getPath()).isEqualTo("/run/user/1000/podman/podman.sock");
        assertThat(bind.getVolume().getPath()).isEqualTo("/var/run/docker.sock");
    }

    @Test
    void socketBindRejectsWindowsContainerMode() {
        stubInfo("windows");

        assertThatThrownBy(service::resolveDockerSocketBind)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Linux-container mode");
    }

    @Test
    void verifyRunningPassesWhileTheContainerIsUp() {
        stubState(true, 0L);

        service.verifyRunning("container-1");
    }

    @Test
    void verifyRunningReportsTheExitCodeAndLogTailWhenTheContainerHasExited() throws Exception {
        stubState(false, 1L);
        LogContainerCmd logCmd = mock(LogContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        when(dockerClient.logContainerCmd("container-1")).thenReturn(logCmd);
        when(logCmd.exec(any())).thenAnswer(invocation -> {
            ResultCallback<Frame> callback = invocation.getArgument(0);
            callback.onNext(new Frame(StreamType.STDERR, "ERROR: not authorised\n".getBytes(UTF_8)));
            return callback;
        });

        assertThatThrownBy(() -> service.verifyRunning("container-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exited with code 1")
                .hasMessageContaining("ERROR: not authorised");
    }

    private void stubInfo(String osType) {
        InfoCmd infoCmd = mock(InfoCmd.class);
        Info info = mock(Info.class);
        when(info.getOsType()).thenReturn(osType);
        when(infoCmd.exec()).thenReturn(info);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
    }

    private void stubState(boolean running, long exitCode) {
        InspectContainerCmd inspectCmd = mock(InspectContainerCmd.class);
        InspectContainerResponse response = mock(InspectContainerResponse.class);
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(running);
        when(state.getExitCodeLong()).thenReturn(exitCode);
        when(response.getState()).thenReturn(state);
        when(inspectCmd.exec()).thenReturn(response);
        when(dockerClient.inspectContainerCmd("container-1")).thenReturn(inspectCmd);
    }

}
