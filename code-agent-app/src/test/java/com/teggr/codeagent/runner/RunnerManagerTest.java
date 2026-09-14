package com.teggr.codeagent.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.teggr.codeagent.harness.AgentHarness;
import com.teggr.codeagent.harness.AgentHarnessFactory;
import com.teggr.codeagent.harness.HarnessSession;
import com.teggr.codeagent.docker.ContainerLaunch;
import com.teggr.codeagent.docker.DockerRunnerService;
import com.teggr.codeagent.docker.ManagedContainer;

class RunnerManagerTest {

    private final DockerRunnerService dockerRunnerService = mock(DockerRunnerService.class);
    private final AgentHarnessFactory agentHarnessFactory = mock(AgentHarnessFactory.class);
    private final RunnerEventPublisher eventPublisher = mock(RunnerEventPublisher.class);
    private final RunnerManager runnerManager = new RunnerManager(dockerRunnerService, agentHarnessFactory,
            eventPublisher);

    @Test
    void startTwiceForSameRepoProducesDistinctRunners() throws Exception {
        String repoUrl = "https://github.com/teggr/j2html-toolkit";
        when(dockerRunnerService.launch(eq(repoUrl), anyString()))
            .thenReturn(new ContainerLaunch("container-1", 1111))
            .thenReturn(new ContainerLaunch("container-2", 2222));
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

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
        when(dockerRunnerService.launch(eq(repoUrl), anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        RunnerSession session = runnerManager.start(repoUrl, prompt);

        assertThat(session.runner().repoUrl()).isEqualTo(repoUrl);
        assertThat(session.messages()).extracting("content").contains(prompt);
        assertThat(session.status()).isEqualTo(RunnerStatus.BUSY);
    }

    @Test
    void createsIndependentSessionsOnTheSameRunner() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        HarnessSession firstAgent = mock(HarnessSession.class);
        HarnessSession secondAgent = mock(HarnessSession.class);
        when(dockerRunnerService.launch(anyString(), anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(harness.createSession(anyString())).thenReturn(firstAgent, secondAgent);
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        Runner runner = runnerManager.start("https://github.com/teggr/j2html-toolkit");
        RunnerSession first = runnerManager.getSession(runner.id());
        RunnerSession second = runnerManager.createSession(runner.id());

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.runner().id()).isEqualTo(first.runner().id());
        assertThat(second.runner().containerLaunch()).isEqualTo(first.runner().containerLaunch());
        assertThat(second.runner().harness()).isSameAs(harness);
        assertThat(runnerManager.sessions(runner.id())).containsExactlyInAnyOrder(first, second);
        verify(harness, times(2)).createSession(anyString());
    }

    @Test
    void sessionErrorMarksRunnerFailedAndAddsErrorMessage() {
        HarnessSession agentSession = mock(HarnessSession.class);
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
        HarnessSession agentSession = mock(HarnessSession.class);
        ArgumentCaptor<java.util.function.Function<com.teggr.codeagent.harness.Question, java.util.concurrent.CompletableFuture<String>>> questionHandlerCaptor =
                ArgumentCaptor.forClass(java.util.function.Function.class);

        RunnerSession session = new RunnerSession(new Runner("runner-1", "repo", new ContainerLaunch("container-1", 1111), null));
        session.attachAgent(agentSession);
        verify(agentSession).onQuestion(questionHandlerCaptor.capture());

        com.teggr.codeagent.harness.Question question = new com.teggr.codeagent.harness.Question("question-1",
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
    void stopClosesTheAgentAndTheContainerButKeepsTheRunner() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(anyString(), anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        Runner runner = runnerManager.start("https://github.com/teggr/j2html-toolkit");
        runnerManager.stop(runner.id());

        verify(harness).close();
        verify(dockerRunnerService).stop("container-1");
        verify(dockerRunnerService, never()).remove(anyString());
        RunnerSession session = runnerManager.getSession(runner.id());
        assertThat(session).isNotNull();
        assertThat(session.status()).isEqualTo(RunnerStatus.STOPPED);
        assertThat(session.agentSession()).isNull();
    }

    @Test
    void removeDeletesTheContainerAndForgetsTheRunner() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(anyString(), anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        Runner runner = runnerManager.start("https://github.com/teggr/j2html-toolkit");
        runnerManager.remove(runner.id());

        verify(harness).close();
        verify(dockerRunnerService).remove("container-1");
        assertThat(runnerManager.get(runner.id())).isNull();
    }

    @Test
    void startBringsAStoppedRunnerBackWithAResumedAgentSession() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(anyString(), anyString()))
            .thenReturn(new ContainerLaunch("container-1", 1111, "/workspace/j2html-toolkit"));
        HarnessSession created = mock(HarnessSession.class);
        HarnessSession resumed = mock(HarnessSession.class);
        when(harness.createSession(anyString())).thenReturn(created);
        when(harness.resumeSession(anyString())).thenReturn(resumed);
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Runner runner = runnerManager.start("https://github.com/teggr/j2html-toolkit");
        runnerManager.stop(runner.id());
        when(dockerRunnerService.restart("container-1", "/workspace/j2html-toolkit"))
            .thenReturn(new ContainerLaunch("container-1", 2222, "/workspace/j2html-toolkit"));

        runnerManager.restart(runner.id());

        RunnerSession session = runnerManager.getSession(runner.id());
        await(() -> session.status() == RunnerStatus.IDLE);
        verify(dockerRunnerService).restart("container-1", "/workspace/j2html-toolkit");
        verify(harness).resumeSession(session.id());
        assertThat(session.runner().containerLaunch().hostPort()).isEqualTo(2222);
    }

        @Test
        void restartReconnectsEverySessionOnTheRunner() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(anyString(), anyString()))
            .thenReturn(new ContainerLaunch("container-1", 1111, "/workspace/j2html-toolkit"));
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class), mock(HarnessSession.class));
        when(harness.resumeSession(anyString())).thenReturn(mock(HarnessSession.class), mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Runner runner = runnerManager.start("https://github.com/teggr/j2html-toolkit");
        RunnerSession sibling = runnerManager.createSession(runner.id());
        runnerManager.stop(runner.id());
        when(dockerRunnerService.restart("container-1", "/workspace/j2html-toolkit"))
            .thenReturn(new ContainerLaunch("container-1", 2222, "/workspace/j2html-toolkit"));

        runnerManager.restart(runner.id());

        await(() -> runnerManager.sessions(runner.id()).stream()
            .allMatch(child -> child.status() == RunnerStatus.IDLE));
        assertThat(runnerManager.getSession(runner.id()).runner().containerLaunch().hostPort()).isEqualTo(2222);
        assertThat(sibling.runner().containerLaunch().hostPort()).isEqualTo(2222);
        verify(harness, times(2)).createSession(anyString());
        verify(harness, times(2)).resumeSession(anyString());
        }

    @Test
    void restartKeepsFailedConversationWithoutReplacingItsAgentSession() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(anyString(), anyString()))
            .thenReturn(new ContainerLaunch("container-1", 1111, "/workspace/j2html-toolkit"));
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class), mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Runner runner = runnerManager.start("https://github.com/teggr/j2html-toolkit");
        RunnerSession first = runnerManager.getSession(runner.id());
        RunnerSession sibling = runnerManager.createSession(runner.id());
        runnerManager.stop(runner.id());
        when(dockerRunnerService.restart("container-1", "/workspace/j2html-toolkit"))
            .thenReturn(new ContainerLaunch("container-1", 2222, "/workspace/j2html-toolkit"));
        when(harness.resumeSession(first.id())).thenThrow(new RuntimeException("session unavailable"));
        when(harness.resumeSession(sibling.id())).thenReturn(mock(HarnessSession.class));

        runnerManager.restart(runner.id());

        await(() -> first.status() == RunnerStatus.FAILED && sibling.status() == RunnerStatus.IDLE);
        assertThat(first.agentSession()).isNull();
        assertThat(sibling.agentSession()).isNotNull();
        verify(harness, times(2)).createSession(anyString());
    }

    @Test
    void removingOneSessionKeepsItsSiblingAndRunnerAlive() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(dockerRunnerService.launch(anyString(), anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class), mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        Runner runner = runnerManager.start("https://github.com/teggr/j2html-toolkit");
        RunnerSession first = runnerManager.getSession(runner.id());
        RunnerSession sibling = runnerManager.createSession(runner.id());

        runnerManager.removeSession(runner.id(), first.id());

        assertThat(runnerManager.getSession(runner.id())).isSameAs(sibling);
        assertThat(runnerManager.sessions(runner.id())).containsExactly(sibling);
        verify(dockerRunnerService, never()).remove(anyString());
    }

    @Test
    void stopAllStopsEveryRunnerEvenWhenOneFails() throws Exception {
        AgentHarness failingHarness = mock(AgentHarness.class);
        AgentHarness healthyHarness = mock(AgentHarness.class);
        doThrow(new RuntimeException("boom")).when(failingHarness).close();
        when(failingHarness.createSession(anyString())).thenReturn(mock(HarnessSession.class));
        when(healthyHarness.createSession(anyString())).thenReturn(mock(HarnessSession.class));
        when(dockerRunnerService.launch(anyString(), anyString()))
            .thenReturn(new ContainerLaunch("container-1", 1111))
            .thenReturn(new ContainerLaunch("container-2", 2222));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(failingHarness).thenReturn(healthyHarness);

        runnerManager.start("https://github.com/teggr/j2html-toolkit");
        runnerManager.start("https://github.com/teggr/j2html-toolkit");

        runnerManager.stopAll();

        verify(dockerRunnerService).stop("container-1");
        verify(dockerRunnerService).stop("container-2");
        assertThat(runnerManager.list()).extracting(RunnerSession::status)
            .containsOnly(RunnerStatus.STOPPED);
    }

    @Test
    void adoptExistingReconnectsARunningContainerUnderItsOriginalRunnerId() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        HarnessSession resumed = mock(HarnessSession.class);
        when(harness.listSessionIds()).thenReturn(List.of("session-1"));
        when(harness.resumeSession("session-1")).thenReturn(resumed);
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        when(dockerRunnerService.listManaged()).thenReturn(List.of(new ManagedContainer("runner-1",
                "https://github.com/teggr/j2html-toolkit", new ContainerLaunch("container-1", 1111), true)));

        runnerManager.adoptExisting();

        await(() -> runnerManager.getSession("runner-1") != null
                && runnerManager.getSession("runner-1").status() == RunnerStatus.IDLE);
        RunnerSession session = runnerManager.getSession("runner-1");
        assertThat(session).isNotNull();
        assertThat(session.runner().harness()).isSameAs(harness);
        assertThat(session.runner().repoUrl()).isEqualTo("https://github.com/teggr/j2html-toolkit");
        assertThat(session.id()).isEqualTo("session-1");
        verify(dockerRunnerService, never()).restart(anyString(), anyString());
        verify(dockerRunnerService, never()).launch(anyString(), anyString());
    }

    @Test
    void adoptExistingStartsAStoppedContainerBeforeConnecting() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.listSessionIds()).thenReturn(List.of("session-1"));
        when(harness.resumeSession("session-1")).thenReturn(mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        when(dockerRunnerService.listManaged()).thenReturn(List.of(new ManagedContainer("runner-1",
                "https://github.com/teggr/j2html-toolkit",
                new ContainerLaunch("container-1", 0, "/workspace/j2html-toolkit"), false)));
        when(dockerRunnerService.restart("container-1", "/workspace/j2html-toolkit"))
            .thenReturn(new ContainerLaunch("container-1", 3333, "/workspace/j2html-toolkit"));

        runnerManager.adoptExisting();

        await(() -> runnerManager.getSession("runner-1") != null
                && runnerManager.getSession("runner-1").status() == RunnerStatus.IDLE);
        RunnerSession session = runnerManager.getSession("runner-1");
        verify(dockerRunnerService).restart("container-1", "/workspace/j2html-toolkit");
        assertThat(session.runner().containerLaunch().hostPort()).isEqualTo(3333);
    }

    @Test
    void adoptExistingRestoresEveryPersistedSessionForTheRunner() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.listSessionIds()).thenReturn(List.of("session-1", "session-2"));
        when(harness.resumeSession(anyString())).thenReturn(mock(HarnessSession.class), mock(HarnessSession.class));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        when(dockerRunnerService.listManaged()).thenReturn(List.of(new ManagedContainer("runner-1",
                "https://github.com/teggr/j2html-toolkit",
                new ContainerLaunch("container-1", 1111, "/workspace/j2html-toolkit"), true)));

        runnerManager.adoptExisting();

        await(() -> runnerManager.sessions("runner-1").size() == 2);
        assertThat(runnerManager.sessions("runner-1")).extracting(RunnerSession::id)
                .containsExactlyInAnyOrder("session-1", "session-2");
        verify(harness, times(2)).resumeSession(anyString());
    }

    @Test
    void adoptExistingCreatesOneConversationWhenNoPersistedSessionsExist() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        HarnessSession created = mock(HarnessSession.class);
        when(harness.listSessionIds()).thenReturn(List.of());
        when(harness.createSession(anyString())).thenReturn(created);
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        when(dockerRunnerService.listManaged()).thenReturn(List.of(new ManagedContainer("runner-1",
                "https://github.com/teggr/j2html-toolkit",
                new ContainerLaunch("container-1", 1111, "/workspace/j2html-toolkit"), true)));

        runnerManager.adoptExisting();

        await(() -> runnerManager.sessions("runner-1").size() == 1);
        RunnerSession conversation = runnerManager.sessions("runner-1").iterator().next();
        verify(harness).createSession(conversation.id());
        verify(harness, never()).resumeSession(anyString());
        assertThat(conversation.status()).isEqualTo(RunnerStatus.IDLE);
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void startStopsContainerWhenHarnessConnectFails() throws Exception {
        when(dockerRunnerService.launch(anyString(), anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenThrow(new RuntimeException("connect failed"));

        try {
            runnerManager.start("https://github.com/teggr/j2html-toolkit");
        } catch (RuntimeException expected) {
            // expected: connect failure should propagate after cleanup
        }

        verify(dockerRunnerService, times(1)).remove("container-1");
        assertThat(runnerManager.list()).isEmpty();
    }

    @Test
    void startAsyncRemovesContainerWhenHarnessConnectFails() throws Exception {
        when(dockerRunnerService.launch(anyString(), anyString())).thenReturn(new ContainerLaunch("container-1", 1111));
        when(agentHarnessFactory.connect(anyInt(), anyString(), any())).thenThrow(new RuntimeException("connect failed"));

        RunnerSession session = runnerManager.startAsync("https://github.com/teggr/j2html-toolkit", "Inspect the project");

        await(() -> session.status() == RunnerStatus.FAILED);
        verify(dockerRunnerService).remove("container-1");
    }

}
