package com.teggr.codeagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.teggr.codeagent.agent.runtime.AgentRuntime;
import com.teggr.codeagent.agent.runtime.AgentRuntimeInstance;
import com.teggr.codeagent.agent.runtime.DiscoveredAgent;
import com.teggr.codeagent.agent.runtime.WorkspaceAccess;
import com.teggr.codeagent.harness.AgentHarness;
import com.teggr.codeagent.harness.AgentHarnessFactory;
import com.teggr.codeagent.harness.HarnessSession;

class AgentManagerTest {

    private final AgentRuntime agentRuntime = mock(AgentRuntime.class);
    private final AgentHarnessFactory harnessFactory = mock(AgentHarnessFactory.class);
    private final AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
    private final AgentManager manager = new AgentManager(agentRuntime, harnessFactory, eventPublisher);

    @Test
    void sameRepositoryProducesDistinctAgents() throws Exception {
        AgentHarness harness = harness();
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111), runtime("container-2", 2222));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        Agent first = manager.start(repositoryUrl());
        Agent second = manager.start(repositoryUrl());

        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(manager.list()).containsExactlyInAnyOrder(first, second);
        assertThat(first.workspace()).isEqualTo(new GitRepositoryWorkspace(repositoryUrl()));
    }

    @Test
    void createsIndependentConversationsOnOneAgentWithExactHarnessIds() throws Exception {
        AgentHarness harness = harness();
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        Agent agent = manager.start(repositoryUrl());
        AgentConversation first = manager.getConversation(agent.id());
        AgentConversation second = manager.createConversation(agent.id());

        assertThat(manager.conversations(agent.id())).containsExactlyInAnyOrder(first, second);
        verify(harness).createSession(first.id());
        verify(harness).createSession(second.id());
    }

    @Test
    void stoppingAgentRetainsConversationStatusAndHistory() throws Exception {
        AgentHarness harness = harness();
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Agent agent = manager.start(repositoryUrl());
        AgentConversation conversation = manager.getConversation(agent.id());
        conversation.addMessage("user", "remember this");

        manager.stop(agent.id());

        assertThat(manager.get(agent.id()).status()).isEqualTo(AgentStatus.STOPPED);
        assertThat(conversation.status()).isEqualTo(AgentConversationStatus.IDLE);
        assertThat(conversation.messages().stream().map(message -> message.content()).toList())
            .contains("remember this");
        assertThat(conversation.harnessSession()).isNull();
        verify(agentRuntime).stop("container-1");
        verify(agentRuntime, never()).delete(anyString());
    }

    @Test
    void restartResumesEveryConversationByItsExactIdAndIsolatesFailure() throws Exception {
        AgentHarness harness = harness();
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Agent agent = manager.start(repositoryUrl());
        AgentConversation first = manager.getConversation(agent.id());
        AgentConversation sibling = manager.createConversation(agent.id());
        manager.stop(agent.id());
        when(agentRuntime.start(any())).thenReturn(runtime("container-1", 2222));
        when(harness.resumeSession(first.id())).thenThrow(new RuntimeException("missing"));
        when(harness.resumeSession(sibling.id())).thenReturn(mock(HarnessSession.class));

        manager.restart(agent.id());

        await(() -> first.status() == AgentConversationStatus.FAILED
                && sibling.status() == AgentConversationStatus.IDLE);
        assertThat(manager.get(agent.id()).status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(first.harnessSession()).isNull();
        assertThat(sibling.harnessSession()).isNotNull();
        verify(harness).resumeSession(first.id());
        verify(harness).resumeSession(sibling.id());
        verify(harness, times(2)).createSession(anyString());
        verify(eventPublisher, never()).publishWorkspaceLink(first);
        verify(eventPublisher).publishWorkspaceLink(sibling);
    }

    @Test
    void removingFinalConversationDoesNotDeleteAgent() throws Exception {
        AgentHarness harness = harness();
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Agent agent = manager.start(repositoryUrl());
        AgentConversation conversation = manager.getConversation(agent.id());

        manager.removeConversation(agent.id(), conversation.id());

        assertThat(manager.get(agent.id())).isEqualTo(agent);
        assertThat(manager.conversations(agent.id())).isEmpty();
        verify(agentRuntime, never()).delete(anyString());
    }

    @Test
    void adoptionRestoresPersistedConversationIds() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.listSessionIds()).thenReturn(List.of("conversation-1", "conversation-2"));
        when(harness.resumeSession(anyString())).thenReturn(mock(HarnessSession.class));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        when(agentRuntime.discover()).thenReturn(List.of(discovered(true)));

        manager.adoptExisting();

        await(() -> manager.conversations("agent-1").size() == 2);
        assertThat(manager.conversations("agent-1").stream().map(conversation -> conversation.id()).toList())
                .containsExactlyInAnyOrder("conversation-1", "conversation-2");
        verify(harness).resumeSession("conversation-1");
        verify(harness).resumeSession("conversation-2");
    }

    @Test
    void adoptionCreatesOneConversationWhenNoPersistedConversationExists() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.listSessionIds()).thenReturn(List.of());
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        when(agentRuntime.discover()).thenReturn(List.of(discovered(true)));

        manager.adoptExisting();

        await(() -> manager.conversations("agent-1").size() == 1);
        AgentConversation conversation = manager.conversations("agent-1").iterator().next();
        verify(harness).createSession(conversation.id());
        verify(harness, never()).resumeSession(anyString());
    }

    @Test
    void synchronousStartDeletesProvisionedRuntimeWhenConversationCreationFails() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.createSession(anyString())).thenThrow(new RuntimeException("create failed"));
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        assertThatThrownBy(() -> manager.start(repositoryUrl())).hasMessage("create failed");

        verify(harness).close();
        verify(agentRuntime).delete("container-1");
        assertThat(manager.list()).isEmpty();
    }

    @Test
    void startWithPromptStoresHistoryAndSendsPromptWithSplitStatuses() throws Exception {
        AgentHarness harness = harness();
        HarnessSession session = mock(HarnessSession.class);
        when(harness.createSession(anyString())).thenReturn(session);
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        AgentConversation conversation = manager.start(repositoryUrl(), "List the README headings");

        await(() -> conversation.status() == AgentConversationStatus.BUSY);
        assertThat(conversation.agent().status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(conversation.messages().stream().map(message -> message.content()).toList())
                .contains("List the README headings");
        verify(session).sendPrompt("List the README headings");
    }

    @Test
    void startLocalAsyncProvisionsLocalWorkspaceAndSendsPrompt() throws Exception {
        AgentHarness harness = harness();
        HarnessSession session = mock(HarnessSession.class);
        when(harness.createSession(anyString())).thenReturn(session);
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);

        AgentConversation conversation = manager.startLocalAsync("Set up a new project");

        await(() -> conversation.status() == AgentConversationStatus.BUSY);
        assertThat(conversation.agent().workspace()).isEqualTo(new LocalWorkspace());
        verify(agentRuntime).provision(org.mockito.ArgumentMatchers
                .argThat(request -> request.workspace() instanceof LocalWorkspace));
        verify(session).sendPrompt("Set up a new project");
    }

    @Test
    void deletingAgentClosesConnectionDeletesRuntimeAndRemovesAgent() throws Exception {
        AgentHarness harness = harness();
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Agent agent = manager.start(repositoryUrl());

        manager.remove(agent.id());

        verify(harness).close();
        verify(agentRuntime).delete("container-1");
        assertThat(manager.get(agent.id())).isNull();
        assertThat(manager.conversations(agent.id())).isEmpty();
    }

    @Test
    void restartRefreshesPortAndResumesEveryConversation() throws Exception {
        AgentHarness harness = harness();
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Agent agent = manager.start(repositoryUrl());
        AgentConversation first = manager.getConversation(agent.id());
        AgentConversation sibling = manager.createConversation(agent.id());
        manager.stop(agent.id());
        when(agentRuntime.start(any())).thenReturn(runtime("container-1", 2222));
        when(harness.resumeSession(anyString())).thenReturn(mock(HarnessSession.class));

        manager.restart(agent.id());

        await(() -> manager.get(agent.id()).runtimeInstance().harnessPort() == 2222
            && manager.conversations(agent.id()).stream()
                .allMatch(conversation -> conversation.harnessSession() != null
                    && conversation.status() == AgentConversationStatus.IDLE));
        assertThat(manager.get(agent.id()).runtimeInstance().harnessPort()).isEqualTo(2222);
        assertThat(first.agent().runtimeInstance().harnessPort()).isEqualTo(2222);
        assertThat(sibling.agent().runtimeInstance().harnessPort()).isEqualTo(2222);
        verify(harness).resumeSession(first.id());
        verify(harness).resumeSession(sibling.id());
    }

    @Test
    void removingOneConversationPreservesSiblingAndAgent() throws Exception {
        AgentHarness harness = harness();
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        Agent agent = manager.start(repositoryUrl());
        AgentConversation first = manager.getConversation(agent.id());
        AgentConversation sibling = manager.createConversation(agent.id());

        manager.removeConversation(agent.id(), first.id());

        assertThat(manager.get(agent.id())).isEqualTo(agent);
        assertThat(manager.conversations(agent.id())).containsExactly(sibling);
        assertThat(first.harnessSession()).isNull();
        verify(agentRuntime, never()).delete(anyString());
    }

    @Test
    void stopAllContinuesAfterOneHarnessCloseFails() throws Exception {
        AgentHarness failingHarness = harness();
        AgentHarness healthyHarness = harness();
        doThrow(new RuntimeException("close failed")).when(failingHarness).close();
        when(agentRuntime.provision(any()))
                .thenReturn(runtime("container-1", 1111), runtime("container-2", 2222));
        when(harnessFactory.connect(anyInt(), anyString(), any()))
                .thenReturn(failingHarness, healthyHarness);
        Agent first = manager.start(repositoryUrl());
        Agent second = manager.start(repositoryUrl());

        manager.stopAll();

        verify(agentRuntime).stop("container-1");
        verify(agentRuntime).stop("container-2");
        assertThat(manager.get(first.id()).status()).isEqualTo(AgentStatus.STOPPED);
        assertThat(manager.get(second.id()).status()).isEqualTo(AgentStatus.STOPPED);
    }

    @Test
    void adoptionOfRunningRuntimeKeepsPortAndDoesNotStartOrProvision() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.listSessionIds()).thenReturn(List.of("conversation-1"));
        when(harness.resumeSession("conversation-1")).thenReturn(mock(HarnessSession.class));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        when(agentRuntime.discover()).thenReturn(List.of(discovered(true)));

        manager.adoptExisting();

        await(() -> manager.get("agent-1") != null);
        assertThat(manager.get("agent-1").status()).isEqualTo(AgentStatus.RUNNING);
        assertThat(manager.get("agent-1").runtimeInstance().harnessPort()).isEqualTo(1111);
        verify(agentRuntime, never()).start(any());
        verify(agentRuntime, never()).provision(any());
    }

    @Test
    void adoptionOfStoppedRuntimeStartsBeforeConnectingAndUsesRefreshedPort() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.listSessionIds()).thenReturn(List.of("conversation-1"));
        when(harness.resumeSession("conversation-1")).thenReturn(mock(HarnessSession.class));
        when(harnessFactory.connect(anyInt(), anyString(), any())).thenReturn(harness);
        when(agentRuntime.discover()).thenReturn(List.of(discovered(false)));
        when(agentRuntime.start(any())).thenReturn(runtime("container-1", 3333));

        manager.adoptExisting();

        await(() -> manager.get("agent-1") != null);
        assertThat(manager.get("agent-1").runtimeInstance().harnessPort()).isEqualTo(3333);
        verify(agentRuntime).start(any());
        verify(harnessFactory).connect(org.mockito.ArgumentMatchers.eq(3333), anyString(), any());
    }

        @Test
        void failedAdoptionRegistersUnavailableAgentWithRecoveryConversation() throws Exception {
        when(agentRuntime.discover()).thenReturn(List.of(discovered(true)));
        when(harnessFactory.connect(anyInt(), anyString(), any()))
            .thenThrow(new RuntimeException("connect failed"));

        manager.adoptExisting();

        await(() -> manager.get("agent-1") != null);
        Agent agent = manager.get("agent-1");
        assertThat(agent.status()).isEqualTo(AgentStatus.UNAVAILABLE);
        assertThat(agent.connection()).isNull();
        assertThat(agent.runtimeInstance().runtimeId()).isEqualTo("container-1");
        assertThat(manager.conversations("agent-1")).hasSize(1);
        assertThat(manager.conversations("agent-1").iterator().next().messages())
            .extracting(message -> message.content())
            .contains("Unable to reconnect to the existing agent runtime: connect failed");
        }

        @Test
        void unavailableRunningAgentReconnectsWithoutStartingRuntime() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.listSessionIds()).thenReturn(List.of("conversation-1"));
        when(harness.resumeSession("conversation-1")).thenReturn(mock(HarnessSession.class));
        when(agentRuntime.discover()).thenReturn(List.of(discovered(true)));
        when(harnessFactory.connect(anyInt(), anyString(), any()))
            .thenThrow(new RuntimeException("connect failed"))
            .thenReturn(harness);

        manager.adoptExisting();
        await(() -> manager.get("agent-1") != null && manager.get("agent-1").status() == AgentStatus.UNAVAILABLE);

        manager.restart("agent-1");

        await(() -> manager.get("agent-1").status() == AgentStatus.RUNNING
            && manager.conversations("agent-1").stream()
                .anyMatch(conversation -> conversation.id().equals("conversation-1")));
        assertThat(manager.conversations("agent-1").stream().map(conversation -> conversation.id()).toList())
            .containsExactly("conversation-1");
        verify(agentRuntime, never()).start(any());
        }

        @Test
        void unavailableStoppedAgentStartsRuntimeBeforeReconnect() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.listSessionIds()).thenReturn(List.of("conversation-1"));
        when(harness.resumeSession("conversation-1")).thenReturn(mock(HarnessSession.class));
        DiscoveredAgent stopped = new DiscoveredAgent("agent-1", new GitRepositoryWorkspace(repositoryUrl()),
            runtime("container-1", 0), false);
        when(agentRuntime.discover()).thenReturn(List.of(stopped));
        when(agentRuntime.start(stopped.instance()))
            .thenReturn(runtime("container-1", 3333))
            .thenReturn(runtime("container-1", 4444));
        when(harnessFactory.connect(anyInt(), anyString(), any()))
            .thenThrow(new RuntimeException("connect failed"))
            .thenReturn(harness);

        manager.adoptExisting();
        await(() -> manager.get("agent-1") != null && manager.get("agent-1").status() == AgentStatus.UNAVAILABLE);

        manager.restart("agent-1");

        await(() -> manager.get("agent-1").status() == AgentStatus.RUNNING);
        assertThat(manager.get("agent-1").runtimeInstance().harnessPort()).isEqualTo(4444);
        verify(agentRuntime, times(2)).start(any());
        }

        @Test
        void reconnectFailureKeepsUnavailableAgentVisible() throws Exception {
        when(agentRuntime.discover()).thenReturn(List.of(discovered(true)));
        when(harnessFactory.connect(anyInt(), anyString(), any()))
            .thenThrow(new RuntimeException("first failure"))
            .thenThrow(new RuntimeException("second failure"));

        manager.adoptExisting();
        await(() -> manager.get("agent-1") != null && manager.get("agent-1").status() == AgentStatus.UNAVAILABLE);

        manager.restart("agent-1");

        await(() -> manager.get("agent-1").status() == AgentStatus.CONNECTING);
        await(() -> manager.get("agent-1").status() == AgentStatus.UNAVAILABLE
            && manager.conversations("agent-1").stream()
                .flatMap(conversation -> conversation.messages().stream())
                .anyMatch(message -> message.content().contains("second failure")));
        }

        @Test
        void unavailableAgentCanBeStoppedAndRemoved() throws Exception {
        when(agentRuntime.discover()).thenReturn(List.of(discovered(true)));
        when(harnessFactory.connect(anyInt(), anyString(), any()))
            .thenThrow(new RuntimeException("connect failed"));

        manager.adoptExisting();
        await(() -> manager.get("agent-1") != null && manager.get("agent-1").status() == AgentStatus.UNAVAILABLE);

        manager.stop("agent-1");

        assertThat(manager.get("agent-1").status()).isEqualTo(AgentStatus.STOPPED);
        verify(agentRuntime).stop("container-1");

        manager.remove("agent-1");

        assertThat(manager.get("agent-1")).isNull();
        verify(agentRuntime).delete("container-1");
        }

    @Test
    void synchronousStartDeletesProvisionedRuntimeWhenHarnessConnectFails() throws Exception {
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("connect failed"));

        assertThatThrownBy(() -> manager.start(repositoryUrl())).hasMessage("connect failed");

        verify(agentRuntime).delete("container-1");
        assertThat(manager.list()).isEmpty();
    }

    @Test
    void asynchronousStartDeletesProvisionedRuntimeWhenHarnessConnectFails() throws Exception {
        when(agentRuntime.provision(any())).thenReturn(runtime("container-1", 1111));
        when(harnessFactory.connect(anyInt(), anyString(), any()))
                .thenThrow(new RuntimeException("connect failed"));

        AgentConversation conversation = manager.startAsync(repositoryUrl(), "Inspect the project");

        await(() -> conversation.status() == AgentConversationStatus.FAILED);
        assertThat(conversation.agent().status()).isEqualTo(AgentStatus.FAILED);
        assertThat(conversation.messages().stream().map(message -> message.content()).toList())
                .contains("Error starting agent: connect failed");
        verify(agentRuntime).delete("container-1");
    }

    private AgentHarness harness() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        when(harness.createSession(anyString())).thenReturn(mock(HarnessSession.class));
        return harness;
    }

    private static AgentRuntimeInstance runtime(String runtimeId, int port) {
        return new AgentRuntimeInstance(runtimeId, port, "/workspace/repository",
                new WorkspaceAccess("vscode://workspace", "code --new-window"));
    }

    private static DiscoveredAgent discovered(boolean running) {
        return new DiscoveredAgent("agent-1", new GitRepositoryWorkspace(repositoryUrl()),
                runtime("container-1", 1111), running);
    }

    private static String repositoryUrl() {
        return "https://github.com/teggr/j2html-toolkit";
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}