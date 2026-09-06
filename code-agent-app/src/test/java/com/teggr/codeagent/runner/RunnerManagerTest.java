package com.teggr.codeagent.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.agent.AgentHarnessFactory;
import com.teggr.codeagent.docker.ContainerLaunch;
import com.teggr.codeagent.docker.DockerRunnerService;

class RunnerManagerTest {

    private final DockerRunnerService dockerRunnerService = mock(DockerRunnerService.class);
    private final AgentHarnessFactory agentHarnessFactory = mock(AgentHarnessFactory.class);
    private final RunnerManager runnerManager = new RunnerManager(dockerRunnerService, agentHarnessFactory);

    @Test
    void startTwiceForSameRepoProducesDistinctRunners() throws Exception {
        String repoUrl = "https://github.com/teggr/j2html-toolkit";
        when(dockerRunnerService.launch(repoUrl))
            .thenReturn(new ContainerLaunch("container-1", 1111))
            .thenReturn(new ContainerLaunch("container-2", 2222));
        when(agentHarnessFactory.connect(anyInt())).thenReturn(mock(AgentHarness.class));

        Runner first = runnerManager.start(repoUrl);
        Runner second = runnerManager.start(repoUrl);

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.containerLaunch().containerId()).isEqualTo("container-1");
        assertThat(second.containerLaunch().containerId()).isEqualTo("container-2");
        assertThat(runnerManager.list()).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void stopClosesHarnessAndContainerAndRemovesRunner() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(agentHarnessFactory.connect(anyInt())).thenReturn(harness);

        Runner runner = runnerManager.start("https://github.com/teggr/j2html-toolkit");
        runnerManager.stop(runner.id());

        verify(harness).close();
        verify(dockerRunnerService).stop("container-1");
        assertThat(runnerManager.get(runner.id())).isNull();
    }

    @Test
    void stopAllStopsEveryRunnerEvenWhenOneFails() throws Exception {
        AgentHarness failingHarness = mock(AgentHarness.class);
        AgentHarness healthyHarness = mock(AgentHarness.class);
        doThrow(new RuntimeException("boom")).when(failingHarness).close();
        when(dockerRunnerService.launch(anyString()))
            .thenReturn(new ContainerLaunch("container-1", 1111))
            .thenReturn(new ContainerLaunch("container-2", 2222));
        when(agentHarnessFactory.connect(anyInt())).thenReturn(failingHarness).thenReturn(healthyHarness);

        runnerManager.start("https://github.com/teggr/j2html-toolkit");
        runnerManager.start("https://github.com/teggr/j2html-toolkit");

        runnerManager.stopAll();

        assertThat(runnerManager.list()).isEmpty();
        verify(dockerRunnerService).stop("container-1");
        verify(dockerRunnerService).stop("container-2");
    }

    @Test
    void startStopsContainerWhenHarnessConnectFails() throws Exception {
        when(dockerRunnerService.launch(anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(agentHarnessFactory.connect(anyInt())).thenThrow(new RuntimeException("connect failed"));

        try {
            runnerManager.start("https://github.com/teggr/j2html-toolkit");
        } catch (RuntimeException expected) {
            // expected: connect failure should propagate after cleanup
        }

        verify(dockerRunnerService, times(1)).stop("container-1");
        assertThat(runnerManager.list()).isEmpty();
    }

}
