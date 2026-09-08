package com.teggr.codeagent.docker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.StreamType;

class DockerRunnerServiceTest {

    private final DockerClient dockerClient = mock(DockerClient.class);
    private final DockerRunnerService service = new DockerRunnerService(dockerClient, new DockerRunnerProperties());

    @Test
    void launchRejectsMissingGhToken() {
        assertThatThrownBy(() -> service.launch("https://github.com/fanduel/withdrawals"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("GH_TOKEN environment variable is not set");
    }

    @Test
    void launchRejectsMissingCopilotToken() {
        DockerRunnerProperties properties = new DockerRunnerProperties();
        properties.setGitToken("git-token");
        DockerRunnerService runnerService = new DockerRunnerService(dockerClient, properties);

        assertThatThrownBy(() -> runnerService.launch("https://github.com/fanduel/withdrawals"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("COPILOT_GITHUB_TOKEN environment variable is not set");
    }

    @Test
    void pruneOrphansStopsAndRemovesEveryLabeledContainer() {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class, org.mockito.Answers.RETURNS_SELF);
        Container orphan1 = mock(Container.class);
        Container orphan2 = mock(Container.class);
        when(orphan1.getId()).thenReturn("orphan-1");
        when(orphan2.getId()).thenReturn("orphan-2");
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(orphan1, orphan2));

        StopContainerCmd stopCmd = mock(StopContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.stopContainerCmd(any())).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd(any())).thenReturn(removeCmd);

        service.pruneOrphans();

        verify(listContainersCmd).withLabelFilter(Map.of(DockerRunnerService.MANAGED_LABEL, "true"));
        verify(listContainersCmd).withShowAll(true);
        verify(dockerClient).stopContainerCmd(eq("orphan-1"));
        verify(dockerClient).stopContainerCmd(eq("orphan-2"));
        verify(dockerClient).removeContainerCmd(eq("orphan-1"));
        verify(dockerClient).removeContainerCmd(eq("orphan-2"));
    }

    @Test
    void pruneOrphansDoesNothingWhenNoneFound() {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class, org.mockito.Answers.RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of());

        service.pruneOrphans();

        verify(dockerClient, never()).stopContainerCmd(any());
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
