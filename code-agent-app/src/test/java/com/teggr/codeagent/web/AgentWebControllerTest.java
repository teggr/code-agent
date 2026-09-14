package com.teggr.codeagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.ModelAndView;

import com.teggr.codeagent.agent.Agent;
import com.teggr.codeagent.agent.AgentConnection;
import com.teggr.codeagent.agent.AgentConversation;
import com.teggr.codeagent.agent.AgentConversationStatus;
import com.teggr.codeagent.agent.AgentManager;
import com.teggr.codeagent.agent.AgentStatus;
import com.teggr.codeagent.agent.GitRepositoryWorkspace;
import com.teggr.codeagent.agent.runtime.AgentRuntimeInstance;
import com.teggr.codeagent.agent.runtime.WorkspaceAccess;
import com.teggr.codeagent.harness.AgentHarness;
import com.teggr.codeagent.harness.HarnessSession;

class AgentWebControllerTest {

    @Test
    void dashboardUsesCanonicalAgentsModel() {
        AgentManager manager = mock(AgentManager.class);
        when(manager.list()).thenReturn(List.of());
        AgentWebController controller = new AgentWebController(manager, mock(GitHubRepositoryService.class));

        ModelAndView view = controller.dashboard();

        assertThat(view.getViewName()).isEqualTo("dashboard");
        assertThat(view.getModel()).containsEntry("agents", List.of());
    }

    @Test
    void repositoryResultsReturnsPickerFragmentWithQueryResults() {
        AgentManager manager = mock(AgentManager.class);
        GitHubRepositoryService repositoryService = mock(GitHubRepositoryService.class);
        when(repositoryService.findRepositories("toolkit", 1)).thenReturn(new GitHubRepositoryService.RepositoryPage(
                List.of(new GitHubRepositoryService.Repository("teggr/j2html-toolkit",
                        "https://github.com/teggr/j2html-toolkit", "private", true,
                        Instant.parse("2026-09-10T10:00:00Z"))), false, false));
        AgentWebController controller = new AgentWebController(manager, repositoryService);

        ModelAndView view = controller.repositoryResults("toolkit", 1);

        assertThat(view.getViewName()).isEqualTo("fragments :: repositoryResults");
        assertThat(view.getModel()).containsEntry("query", "toolkit").containsEntry("hasMore", false)
                .containsEntry("unavailable", false).containsKey("repositories");
    }

    @Test
    void repositorySelectionRendersSelectedPickerState() {
        AgentWebController controller = controller(mock(AgentManager.class));

        ModelAndView view = controller.repositorySelection(repositoryUrl(), "teggr/repository");

        assertThat(view.getViewName()).isEqualTo("fragments :: repositoryPicker");
        assertThat(view.getModel()).containsEntry("selectedRepositoryUrl", repositoryUrl())
                .containsEntry("selectedRepositoryName", "teggr/repository");
    }

    @Test
    void startingAgentRedirectsToDashboard() {
        AgentManager manager = mock(AgentManager.class);

        ResponseEntity<Void> response = controller(manager).startAgent(repositoryUrl(), "Inspect the project");

        verify(manager).startAsync(repositoryUrl(), "Inspect the project");
        assertRedirect(response, "/");
    }

    @Test
    void startingAgentWithBlankRepoUrlStartsLocalWorkspace() {
        AgentManager manager = mock(AgentManager.class);

        ResponseEntity<Void> response = controller(manager).startAgent("", "Set up a new project");

        verify(manager).startLocalAsync("Set up a new project");
        assertRedirect(response, "/");
    }

    @Test
    void emptyWorkspaceSelectionRendersPickerInEmptyState() {
        AgentWebController controller = controller(mock(AgentManager.class));

        ModelAndView view = controller.emptyWorkspaceSelection();

        assertThat(view.getViewName()).isEqualTo("fragments :: repositoryPicker");
        assertThat(view.getModel()).containsEntry("emptyWorkspaceSelected", true);
    }

    @Test
    void agentDetailWithoutConversationUsesCanonicalStatusSplit() {
        AgentManager manager = mock(AgentManager.class);
        Agent agent = agent(AgentStatus.STOPPED, null);
        when(manager.get("agent-1")).thenReturn(agent);
        when(manager.getConversation("agent-1")).thenReturn(null);
        when(manager.conversations("agent-1")).thenReturn(List.of());

        ModelAndView view = controller(manager).agentDetail("agent-1");

        assertThat(view.getViewName()).isEqualTo("agent");
        assertThat(view.getModel()).containsEntry("agentStatus", AgentStatus.STOPPED)
                .containsEntry("conversationStatus", null)
                .containsEntry("conversationId", null)
                .containsEntry("repositoryUrl", repositoryUrl());
    }

    @Test
    void agentDetailForLocalWorkspaceAgentHasNullRepositoryUrl() {
        AgentManager manager = mock(AgentManager.class);
        AgentRuntimeInstance runtime = new AgentRuntimeInstance("container-1", 4321, "/workspace",
                new WorkspaceAccess("vscode://workspace", "code --new-window"));
        Agent agent = new Agent("agent-1", new com.teggr.codeagent.agent.LocalWorkspace(), runtime, null,
                AgentStatus.STOPPED);
        when(manager.get("agent-1")).thenReturn(agent);
        when(manager.getConversation("agent-1")).thenReturn(null);
        when(manager.conversations("agent-1")).thenReturn(List.of());

        ModelAndView view = controller(manager).agentDetail("agent-1");

        assertThat(view.getModel()).containsEntry("repositoryUrl", null);
    }

    @Test
    void conversationDetailIncludesConversationStateAndCanonicalIds() throws Exception {
        AgentManager manager = mock(AgentManager.class);
        AgentConversation conversation = readyConversation();
        conversation.addMessage("assistant", "Ready");
        when(manager.getConversation("agent-1", "conversation-1")).thenReturn(conversation);
        when(manager.conversations("agent-1")).thenReturn(List.of(conversation));

        ModelAndView view = controller(manager).conversationDetail("agent-1", "conversation-1");

        assertThat(view.getModel()).containsEntry("agentId", "agent-1")
                .containsEntry("conversationId", "conversation-1")
                .containsEntry("agentStatus", AgentStatus.RUNNING)
                .containsEntry("conversationStatus", AgentConversationStatus.IDLE)
                .containsEntry("workspaceReady", true)
                .containsEntry("promptable", true);
        assertThat((List<?>) view.getModel().get("messages")).hasSize(1);
    }

    @Test
    void creatingConversationRedirectsToCanonicalConversationPath() throws Exception {
        AgentManager manager = mock(AgentManager.class);
        AgentConversation conversation = new AgentConversation("conversation-2", agent(AgentStatus.RUNNING, null));
        when(manager.createConversation("agent-1")).thenReturn(conversation);

        ResponseEntity<Void> response = controller(manager).createConversation("agent-1");

        assertRedirect(response, "/agents/agent-1/conversations/conversation-2");
    }

    @Test
    void promptRecordsMessageSetsBusySendsToHarnessAndRedirects() throws Exception {
        AgentManager manager = mock(AgentManager.class);
        AgentConversation conversation = readyConversation();
        HarnessSession session = conversation.harnessSession();
        when(manager.getConversation("agent-1", "conversation-1")).thenReturn(conversation);
        ExecutorService executor = immediateExecutor();
        when(manager.executor()).thenReturn(executor);

        ResponseEntity<Void> response = controller(manager)
                .sendPrompt("agent-1", "conversation-1", "Run the tests");

        assertThat(conversation.status()).isEqualTo(AgentConversationStatus.BUSY);
        assertThat(conversation.messages()).extracting("content").contains("Run the tests");
        verify(session).sendPrompt("Run the tests");
        assertRedirect(response, "/agents/agent-1/conversations/conversation-1");
    }

    @Test
    void abortDelegatesToHarnessAndRedirectsToConversation() throws Exception {
        AgentManager manager = mock(AgentManager.class);
        AgentConversation conversation = readyConversation();
        when(manager.getConversation("agent-1", "conversation-1")).thenReturn(conversation);

        ResponseEntity<Void> response = controller(manager).abort("agent-1", "conversation-1");

        verify(conversation.harnessSession()).abort();
        assertRedirect(response, "/agents/agent-1/conversations/conversation-1");
    }

    @Test
    void removingConversationPreservesAgentRouteAsRedirectTarget() {
        AgentManager manager = mock(AgentManager.class);
        AgentConversation conversation = new AgentConversation("conversation-1", agent(AgentStatus.RUNNING, null));
        when(manager.getConversation("agent-1", "conversation-1")).thenReturn(conversation);

        ResponseEntity<Void> response = controller(manager).removeConversation("agent-1", "conversation-1");

        verify(manager).removeConversation("agent-1", "conversation-1");
        assertRedirect(response, "/agents/agent-1");
    }

    @Test
    void stoppingAgentRedirectsToCanonicalAgentDetail() {
        AgentManager manager = managerWithAgent();

        ResponseEntity<Void> response = controller(manager).stopAgent("agent-1");

        verify(manager).stop("agent-1");
        assertRedirect(response, "/agents/agent-1");
    }

    @Test
    void restartingAgentRedirectsToCanonicalAgentDetail() {
        AgentManager manager = managerWithAgent();

        ResponseEntity<Void> response = controller(manager).restartAgent("agent-1");

        verify(manager).restart("agent-1");
        assertRedirect(response, "/agents/agent-1");
    }

    @Test
    void deletingAgentRedirectsToDashboard() {
        AgentManager manager = managerWithAgent();

        ResponseEntity<Void> response = controller(manager).removeAgent("agent-1");

        verify(manager).remove("agent-1");
        assertRedirect(response, "/");
    }

    private static AgentWebController controller(AgentManager manager) {
        return new AgentWebController(manager, mock(GitHubRepositoryService.class));
    }

    private static AgentManager managerWithAgent() {
        AgentManager manager = mock(AgentManager.class);
        when(manager.get("agent-1")).thenReturn(agent(AgentStatus.RUNNING, null));
        return manager;
    }

    private static AgentConversation readyConversation() throws Exception {
        AgentHarness harness = mock(AgentHarness.class);
        AgentConversation conversation = new AgentConversation("conversation-1",
                agent(AgentStatus.RUNNING, new AgentConnection(harness)));
        HarnessSession session = mock(HarnessSession.class);
        conversation.attachHarnessSession(session);
        conversation.setStatus(AgentConversationStatus.IDLE);
        return conversation;
    }

    private static Agent agent(AgentStatus status, AgentConnection connection) {
        AgentRuntimeInstance runtime = new AgentRuntimeInstance("container-1", 4321, "/workspace/repository",
                new WorkspaceAccess("vscode://workspace", "code --new-window"));
        return new Agent("agent-1", new GitRepositoryWorkspace(repositoryUrl()), runtime, connection, status);
    }

    private static ExecutorService immediateExecutor() {
        ExecutorService executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return CompletableFuture.completedFuture(null);
        });
        return executor;
    }

    private static void assertRedirect(ResponseEntity<Void> response, String location) {
        assertThat(response.getStatusCode().value()).isEqualTo(303);
        assertThat(response.getHeaders().getLocation()).isEqualTo(URI.create(location));
    }

    private static String repositoryUrl() {
        return "https://github.com/teggr/repository";
    }
}