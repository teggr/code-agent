package com.teggr.codeagent.agent.runtime.docker;

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
import com.teggr.codeagent.agent.GitRepositoryWorkspace;
import com.teggr.codeagent.agent.GitRepositoryWorkspaceProperties;
import com.teggr.codeagent.agent.runtime.AgentRuntimeInstance;
import com.teggr.codeagent.agent.runtime.AgentRuntimeRequest;
import com.teggr.codeagent.agent.runtime.DiscoveredAgent;
import com.teggr.codeagent.harness.copilot.CopilotHarnessProperties;

class DockerAgentRuntimeTest {

    private final DockerClient dockerClient = mock(DockerClient.class);
    private final DockerAgentRuntime service = runtime(new DockerAgentRuntimeProperties(), "", "");

    @Test
    void launchRejectsMissingGhToken() {
        assertThatThrownBy(() -> service.provision(request()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("GH_TOKEN environment variable is not set");
    }

    @Test
    void launchDefaultsCopilotTokenToGitTokenWhenNotConfigured() throws Exception {
        DockerAgentRuntimeProperties properties = new DockerAgentRuntimeProperties();
        DockerAgentRuntime agentRuntime = runtime(properties, "git-token", "");

        stubInfo("linux");

        CreateContainerCmd createContainerCmd = mock(CreateContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        CreateContainerResponse createContainerResponse = mock(CreateContainerResponse.class);
        when(createContainerResponse.getId()).thenReturn("container-1");
        when(createContainerCmd.exec()).thenReturn(createContainerResponse);
        when(dockerClient.createContainerCmd(any())).thenReturn(createContainerCmd);

        StartContainerCmd startContainerCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("container-1")).thenReturn(startContainerCmd);

        stubPublishedPort("container-1", "4321");

        agentRuntime.provision(request());

        verify(createContainerCmd).withEnv("GH_TOKEN=git-token",
                "COPILOT_GITHUB_TOKEN=git-token",
                "GIT_REPO_URL=https://github.com/fanduel/withdrawals");
    }

    @Test
    void launchLabelsTheContainerWithAgentIdentity() throws Exception {
        DockerAgentRuntimeProperties properties = new DockerAgentRuntimeProperties();
        DockerAgentRuntime agentRuntime = runtime(properties, "git-token", "");

        stubInfo("linux");

        CreateContainerCmd createContainerCmd = mock(CreateContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        CreateContainerResponse createContainerResponse = mock(CreateContainerResponse.class);
        when(createContainerResponse.getId()).thenReturn("container-1");
        when(createContainerCmd.exec()).thenReturn(createContainerResponse);
        when(dockerClient.createContainerCmd(any())).thenReturn(createContainerCmd);
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));
        stubPublishedPort("container-1", "4321");

        agentRuntime.provision(request());

        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(createContainerCmd).withLabels(labels.capture());
        assertThat(labels.getValue())
            .containsEntry(DockerAgentRuntime.MANAGED_LABEL, "true")
            .containsEntry(DockerAgentRuntime.AGENT_ID_LABEL, "agent-1")
            .containsEntry(DockerAgentRuntime.REPO_URL_LABEL, "https://github.com/fanduel/withdrawals")
            .containsEntry(DockerAgentRuntime.WORKSPACE_PATH_LABEL, "/workspace/withdrawals")
            .containsKey(DockerAgentRuntime.CREATED_AT_LABEL);
    }

    @Test
    void launchRejectsMissingGhTokenEvenForLocalWorkspace() {
        assertThatThrownBy(() -> service.provision(localRequest()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("GH_TOKEN environment variable is not set");
    }

    @Test
    void launchOfLocalWorkspaceSkipsGitRepoUrlAndUsesTheDefaultWorkspacePath() throws Exception {
        DockerAgentRuntimeProperties properties = new DockerAgentRuntimeProperties();
        DockerAgentRuntime agentRuntime = runtime(properties, "git-token", "");

        stubInfo("linux");

        CreateContainerCmd createContainerCmd = mock(CreateContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        CreateContainerResponse createContainerResponse = mock(CreateContainerResponse.class);
        when(createContainerResponse.getId()).thenReturn("container-1");
        when(createContainerCmd.exec()).thenReturn(createContainerResponse);
        when(dockerClient.createContainerCmd(any())).thenReturn(createContainerCmd);
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));
        stubPublishedPort("container-1", "4321");

        agentRuntime.provision(localRequest());

        verify(createContainerCmd).withEnv("GH_TOKEN=git-token", "COPILOT_GITHUB_TOKEN=git-token");
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.forClass(Map.class);
        verify(createContainerCmd).withLabels(labels.capture());
        assertThat(labels.getValue())
            .containsEntry(DockerAgentRuntime.REPO_URL_LABEL, "")
            .containsEntry(DockerAgentRuntime.WORKSPACE_PATH_LABEL, "/workspace");
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

        service.delete("container-1");

        verify(dockerClient).stopContainerCmd("container-1");
        verify(dockerClient).removeContainerCmd("container-1");
    }

    @Test
    void restartStartsTheContainerAgainAndPicksUpTheNewHostPort() {
        when(dockerClient.startContainerCmd("container-1")).thenReturn(mock(StartContainerCmd.class));
        stubPublishedPort("container-1", "51001");

        AgentRuntimeInstance instance = service.start(new AgentRuntimeInstance("container-1", 0,
            "/workspace/withdrawals", null));

        verify(dockerClient).startContainerCmd("container-1");
        assertThat(instance.harnessPort()).isEqualTo(51001);
        assertThat(instance.workingDirectory()).isEqualTo("/workspace/withdrawals");
        assertThat(instance.workspaceAccess().uri()).contains("/workspace/withdrawals");
    }

    @Test
    void discoveryRebuildsAgentsFromLabelsAndReReadsThePublishedPort() {
        Container running = managedContainer("container-1", Map.of(
                DockerAgentRuntime.MANAGED_LABEL, "true",
                DockerAgentRuntime.AGENT_ID_LABEL, "agent-1",
                DockerAgentRuntime.REPO_URL_LABEL, "https://github.com/fanduel/withdrawals",
                DockerAgentRuntime.WORKSPACE_PATH_LABEL, "/workspace/withdrawals"));
        Container exited = managedContainer("container-2", Map.of(
                DockerAgentRuntime.MANAGED_LABEL, "true",
                DockerAgentRuntime.AGENT_ID_LABEL, "agent-2",
                DockerAgentRuntime.REPO_URL_LABEL, "https://github.com/fanduel/withdrawals",
                DockerAgentRuntime.WORKSPACE_PATH_LABEL, "/workspace/withdrawals"));
        stubListContainers(running, exited);
        stubInspect("container-1", true, "51000");
        stubInspect("container-2", false, null);

        List<DiscoveredAgent> managed = service.discover();

        assertThat(managed).extracting(DiscoveredAgent::agentId).containsExactly("agent-1", "agent-2");
        assertThat(managed.get(0).running()).isTrue();
        assertThat(managed.get(0).instance().harnessPort()).isEqualTo(51000);
        assertThat(managed.get(0).instance().workspaceAccess().uri()).contains("/workspace/withdrawals");
        assertThat(managed.get(1).running()).isFalse();
        assertThat(managed.get(1).instance().harnessPort()).isZero();
    }

    @Test
    void discoveryReconstructsLocalWorkspaceWhenRepoUrlLabelIsBlank() {
        Container running = managedContainer("container-1", Map.of(
                DockerAgentRuntime.MANAGED_LABEL, "true",
                DockerAgentRuntime.AGENT_ID_LABEL, "agent-1",
                DockerAgentRuntime.REPO_URL_LABEL, "",
                DockerAgentRuntime.WORKSPACE_PATH_LABEL, "/workspace"));
        stubListContainers(running);
        stubInspect("container-1", true, "51000");

        List<DiscoveredAgent> managed = service.discover();

        assertThat(managed).extracting(DiscoveredAgent::workspace)
                .containsExactly(new com.teggr.codeagent.agent.LocalWorkspace());
    }

    @Test
    void listManagedRemovesContainersThatCarryNoAgentId() {
        stubListContainers(managedContainer("legacy-1", Map.of(DockerAgentRuntime.MANAGED_LABEL, "true")));
        StopContainerCmd stopCmd = mock(StopContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        when(dockerClient.stopContainerCmd(any())).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd(any())).thenReturn(mock(RemoveContainerCmd.class));

        assertThat(service.discover()).isEmpty();

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
        DockerAgentRuntimeProperties properties = new DockerAgentRuntimeProperties();
        properties.setSocketPath("/run/user/1000/podman/podman.sock");

        Bind bind = runtime(properties, "", "").resolveDockerSocketBind();

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

    private AgentRuntimeRequest request() {
        return new AgentRuntimeRequest("agent-1",
                new GitRepositoryWorkspace("https://github.com/fanduel/withdrawals"));
    }

    private AgentRuntimeRequest localRequest() {
        return new AgentRuntimeRequest("agent-1", new com.teggr.codeagent.agent.LocalWorkspace());
    }

    private DockerAgentRuntime runtime(DockerAgentRuntimeProperties runtimeProperties,
            String gitToken, String copilotToken) {
        GitRepositoryWorkspaceProperties gitProperties = new GitRepositoryWorkspaceProperties();
        gitProperties.setToken(gitToken);
        CopilotHarnessProperties copilotProperties = new CopilotHarnessProperties();
        copilotProperties.setToken(copilotToken);
        return new DockerAgentRuntime(dockerClient, runtimeProperties, gitProperties, copilotProperties);
    }

}
