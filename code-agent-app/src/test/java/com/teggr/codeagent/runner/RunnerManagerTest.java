package com.teggr.codeagent.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.agent.AgentHarnessFactory;
import com.teggr.codeagent.agent.AgentSession;
import com.teggr.codeagent.docker.ContainerLaunch;
import com.teggr.codeagent.docker.DockerRunnerService;

class RunnerManagerTest {

    private final DockerRunnerService dockerRunnerService = mock(DockerRunnerService.class);
    private final AgentHarnessFactory agentHarnessFactory = mock(AgentHarnessFactory.class);
    private final RunnerEventPublisher eventPublisher = mock(RunnerEventPublisher.class);
    private final RunnerManager runnerManager = new RunnerManager(dockerRunnerService, agentHarnessFactory,
            eventPublisher);

    @Test
    void startTwiceForSameRepoProducesDistinctRunners() throws Exception {
        String repoUrl = "https://github.com/teggr/j2html-toolkit";
        when(dockerRunnerService.launch(repoUrl))
            .thenReturn(new ContainerLaunch("container-1", 1111))
            .thenReturn(new ContainerLaunch("container-2", 2222));
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.createSession()).thenReturn(mock(com.teggr.codeagent.agent.AgentSession.class));
        when(agentHarnessFactory.connect(anyInt(), any())).thenReturn(harness);

        Runner first = runnerManager.start(repoUrl);
        Runner second = runnerManager.start(repoUrl);

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.containerLaunch().containerId()).isEqualTo("container-1");
        assertThat(second.containerLaunch().containerId()).isEqualTo("container-2");
        assertThat(runnerManager.list()).extracting(session -> session.runner().id())
            .containsExactlyInAnyOrder(first.id(), second.id());
    }

    @Test
    void startWithPromptCreatesSessionAndStoresPromptInHistory() throws Exception {
        String repoUrl = "https://github.com/teggr/j2html-toolkit";
        String prompt = "List the README headings";
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(repoUrl)).thenReturn(new ContainerLaunch("container-1", 1111));
        when(harness.createSession()).thenReturn(mock(com.teggr.codeagent.agent.AgentSession.class));
        when(agentHarnessFactory.connect(anyInt(), any())).thenReturn(harness);

        RunnerSession session = runnerManager.start(repoUrl, prompt);

        assertThat(session.runner().repoUrl()).isEqualTo(repoUrl);
        assertThat(session.messages()).extracting("content").contains(prompt);
        assertThat(session.status()).isEqualTo(RunnerStatus.BUSY);
    }

    @Test
    void sessionErrorMarksRunnerFailedAndAddsErrorMessage() {
        AgentSession agentSession = mock(AgentSession.class);
        ArgumentCaptor<Runnable> idleListenerCaptor = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<java.util.function.Consumer<String>> errorListenerCaptor = ArgumentCaptor.forClass(java.util.function.Consumer.class);

        RunnerSession session = new RunnerSession(new Runner("runner-1", "repo", new ContainerLaunch("container-1", 1111), null));
        session.attachAgent(agentSession);
        verify(agentSession).onIdle(idleListenerCaptor.capture());
        verify(agentSession).onError(errorListenerCaptor.capture());

        errorListenerCaptor.getValue().accept("authentication expired");
        idleListenerCaptor.getValue().run();

        assertThat(session.status()).isEqualTo(RunnerStatus.FAILED);
        assertThat(session.messages()).extracting("content").contains("Copilot error: authentication expired");
    }

    @Test
    void answeringAQuestionCompletesTheAgentsPendingFutureAndRecordsBothMessages() {
        AgentSession agentSession = mock(AgentSession.class);
        ArgumentCaptor<java.util.function.Function<com.teggr.codeagent.agent.Question, java.util.concurrent.CompletableFuture<String>>> questionHandlerCaptor =
                ArgumentCaptor.forClass(java.util.function.Function.class);

        RunnerSession session = new RunnerSession(new Runner("runner-1", "repo", new ContainerLaunch("container-1", 1111), null));
        session.attachAgent(agentSession);
        verify(agentSession).onQuestion(questionHandlerCaptor.capture());

        com.teggr.codeagent.agent.Question question = new com.teggr.codeagent.agent.Question("question-1",
                "Which environment?", java.util.List.of("dev", "prod"));
        java.util.concurrent.CompletableFuture<String> answerFuture = questionHandlerCaptor.getValue().apply(question);

        assertThat(session.pendingQuestion()).isEqualTo(question);
        assertThat(session.messages()).extracting("content").contains("Which environment?");

        session.answerQuestion("question-1", "prod");

        assertThat(answerFuture).isCompletedWithValue("prod");
        assertThat(session.pendingQuestion()).isNull();
        assertThat(session.messages()).extracting("content").contains("prod");
    }

    @Test
    void stopClosesHarnessAndContainerAndRemovesRunner() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(harness.createSession()).thenReturn(mock(com.teggr.codeagent.agent.AgentSession.class));
        when(agentHarnessFactory.connect(anyInt(), any())).thenReturn(harness);

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
        when(failingHarness.createSession()).thenReturn(mock(com.teggr.codeagent.agent.AgentSession.class));
        when(healthyHarness.createSession()).thenReturn(mock(com.teggr.codeagent.agent.AgentSession.class));
        when(dockerRunnerService.launch(anyString()))
            .thenReturn(new ContainerLaunch("container-1", 1111))
            .thenReturn(new ContainerLaunch("container-2", 2222));
        when(agentHarnessFactory.connect(anyInt(), any())).thenReturn(failingHarness).thenReturn(healthyHarness);

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
        when(agentHarnessFactory.connect(anyInt(), any())).thenThrow(new RuntimeException("connect failed"));

        try {
            runnerManager.start("https://github.com/teggr/j2html-toolkit");
        } catch (RuntimeException expected) {
            // expected: connect failure should propagate after cleanup
        }

        verify(dockerRunnerService, times(1)).stop("container-1");
        assertThat(runnerManager.list()).isEmpty();
    }

}
