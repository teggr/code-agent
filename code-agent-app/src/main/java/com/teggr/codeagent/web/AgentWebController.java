package com.teggr.codeagent.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teggr.codeagent.agent.Agent;
import com.teggr.codeagent.agent.AgentConversation;
import com.teggr.codeagent.agent.AgentConversationStatus;
import com.teggr.codeagent.agent.AgentManager;
import com.teggr.codeagent.agent.GitRepositoryWorkspace;
import com.teggr.codeagent.schedule.ScheduledTaskService;

@Controller
public class AgentWebController {

    private final AgentManager agentManager;
    private final GitHubRepositoryService repositoryService;
    private final ScheduledTaskService scheduledTaskService;

    public AgentWebController(AgentManager agentManager, GitHubRepositoryService repositoryService,
            ScheduledTaskService scheduledTaskService) {
        this.agentManager = agentManager;
        this.repositoryService = repositoryService;
        this.scheduledTaskService = scheduledTaskService;
    }

    @GetMapping("/")
    public ModelAndView dashboard() {
        ModelAndView view = new ModelAndView("dashboard");
        view.addObject("agents", agentManager.list());
        view.addObject("scheduledTasks", scheduledTaskService.list());
        return view;
    }

    @GetMapping("/repositories")
    public ModelAndView repositoryResults(@RequestParam(value = "query", defaultValue = "") String query,
            @RequestParam(value = "page", defaultValue = "1") int page) {
        ModelAndView view = new ModelAndView("fragments :: repositoryResults");
        GitHubRepositoryService.RepositoryPage repositoryPage = repositoryService.findRepositories(query, page);
        view.addObject("repositories", repositoryPage.repositories());
        view.addObject("query", query);
        view.addObject("nextPage", Math.max(page, 1) + 1);
        view.addObject("hasMore", repositoryPage.hasMore());
        view.addObject("unavailable", repositoryPage.unavailable());
        return view;
    }

    @GetMapping("/repositories/selection")
    public ModelAndView repositorySelection(@RequestParam("repoUrl") String repoUrl,
            @RequestParam("fullName") String fullName) {
        ModelAndView view = new ModelAndView("fragments :: repositoryPicker");
        view.addObject("selectedRepositoryUrl", repoUrl);
        view.addObject("selectedRepositoryName", fullName);
        return view;
    }

    @GetMapping("/repositories/picker")
    public ModelAndView repositoryPicker() {
        return new ModelAndView("fragments :: repositoryPicker");
    }

    @GetMapping("/repositories/empty-selection")
    public ModelAndView emptyWorkspaceSelection() {
        ModelAndView view = new ModelAndView("fragments :: repositoryPicker");
        view.addObject("emptyWorkspaceSelected", true);
        return view;
    }

    @PostMapping("/agents")
    public ResponseEntity<Void> startAgent(@RequestParam(value = "repoUrl", required = false, defaultValue = "") String repoUrl,
            @RequestParam("prompt") String prompt) {
        if (repoUrl.isBlank()) {
            agentManager.startLocalAsync(prompt);
        } else {
            agentManager.startAsync(repoUrl, prompt);
        }
        return redirect("/");
    }

    @GetMapping("/agents/{agentId}")
    public ModelAndView agentDetail(@PathVariable String agentId) {
        Agent agent = requireAgent(agentId);
        AgentConversation conversation = agentManager.getConversation(agentId);
        if (conversation == null) {
            return agentView(agent);
        }
        return conversationView(agentId, conversation.id(), conversation);
    }

    @PostMapping("/agents/{agentId}/conversations")
    public ResponseEntity<Void> createConversation(@PathVariable String agentId) throws Exception {
        AgentConversation conversation = agentManager.createConversation(agentId);
        return redirect(conversationPath(agentId, conversation.id()));
    }

    @GetMapping("/agents/{agentId}/conversations/{conversationId}")
    public ModelAndView conversationDetail(@PathVariable String agentId, @PathVariable String conversationId) {
        return conversationView(agentId, conversationId, requireConversation(agentId, conversationId));
    }

    @PostMapping("/agents/{agentId}/conversations/{conversationId}/prompt")
    public ResponseEntity<Void> sendPrompt(@PathVariable String agentId, @PathVariable String conversationId,
            @RequestParam String prompt) {
        AgentConversation conversation = requireConversation(agentId, conversationId);
        if (!conversation.isReady()) {
            throw new IllegalStateException("Conversation " + conversationId + " has no connected harness session");
        }
        conversation.addMessage("user", prompt);
        conversation.setStatus(AgentConversationStatus.BUSY);
        agentManager.executor().submit(() -> {
            try {
                conversation.harnessSession().sendPrompt(prompt);
            } catch (Exception e) {
                conversation.setStatus(AgentConversationStatus.FAILED);
                conversation.addMessage("assistant", "Error: " + e.getMessage());
            }
        });
        return redirect(conversationPath(agentId, conversationId));
    }

    @PostMapping("/agents/{agentId}/conversations/{conversationId}/answer")
    public ResponseEntity<Void> answerQuestion(@PathVariable String agentId, @PathVariable String conversationId,
            @RequestParam String questionId, @RequestParam String answer) {
        requireConversation(agentId, conversationId).answerQuestion(questionId, answer);
        return redirect(conversationPath(agentId, conversationId));
    }

    @PostMapping("/agents/{agentId}/conversations/{conversationId}/abort")
    public ResponseEntity<Void> abort(@PathVariable String agentId, @PathVariable String conversationId)
            throws Exception {
        AgentConversation conversation = requireConversation(agentId, conversationId);
        if (conversation.harnessSession() != null) {
            conversation.harnessSession().abort();
        }
        return redirect(conversationPath(agentId, conversationId));
    }

    @PostMapping("/agents/{agentId}/conversations/{conversationId}/remove")
    public ResponseEntity<Void> removeConversation(@PathVariable String agentId, @PathVariable String conversationId) {
        agentManager.removeConversation(agentId, conversationId);
        return redirect("/agents/" + agentId);
    }

    @GetMapping("/agents/{agentId}/conversations/{conversationId}/events")
    public SseEmitter conversationEvents(@PathVariable String agentId, @PathVariable String conversationId) {
        requireConversation(agentId, conversationId);
        return agentManager.subscribeConversation(conversationId);
    }

    @GetMapping("/agents/events")
    public SseEmitter dashboardEvents() {
        return agentManager.subscribeDashboard();
    }

    @GetMapping("/agents/{agentId}/events")
    public SseEmitter agentEvents(@PathVariable String agentId) {
        requireAgent(agentId);
        return agentManager.subscribe(agentId);
    }

    @PostMapping("/agents/{agentId}/stop")
    public ResponseEntity<Void> stopAgent(@PathVariable String agentId) {
        requireAgent(agentId);
        agentManager.stop(agentId);
        return redirect("/agents/" + agentId);
    }

    @PostMapping("/agents/{agentId}/start")
    public ResponseEntity<Void> restartAgent(@PathVariable String agentId) {
        requireAgent(agentId);
        agentManager.restart(agentId);
        return redirect("/agents/" + agentId);
    }

    @PostMapping("/agents/{agentId}/remove")
    public ResponseEntity<Void> removeAgent(@PathVariable String agentId) {
        requireAgent(agentId);
        agentManager.remove(agentId);
        return redirect("/");
    }

    private ModelAndView conversationView(String agentId, String conversationId,
            AgentConversation conversation) {
        Agent agent = conversation.agent();
        ModelAndView view = agentView(agent);
        view.addObject("agentId", agentId);
        view.addObject("conversationId", conversationId);
        view.addObject("conversationStatus", conversation.status());
        view.addObject("messages", conversation.messages());
        view.addObject("timelineMessages", conversation.timelineMessages());
        view.addObject("turnSummary", conversation.turnSummary().orElse(null));
        view.addObject("workspaceReady", conversation.isReady());
        view.addObject("promptable", conversation.isReady());
        view.addObject("pendingQuestion", conversation.pendingQuestion());
        return view;
    }

    private ModelAndView agentView(Agent agent) {
        ModelAndView view = new ModelAndView("agent");
        view.addObject("agentId", agent.id());
        view.addObject("conversationId", null);
        view.addObject("conversations", agentManager.conversations(agent.id()));
        view.addObject("repositoryUrl", agent.workspace() instanceof GitRepositoryWorkspace grw ? grw.repositoryUrl() : null);
        view.addObject("agentStatus", agent.status());
        view.addObject("conversationStatus", null);
        view.addObject("messages", java.util.List.of());
        view.addObject("timelineMessages", java.util.List.of());
        view.addObject("turnSummary", null);
        view.addObject("workspaceUri", workspaceUri(agent));
        view.addObject("workspaceReady", false);
        view.addObject("promptable", false);
        view.addObject("pendingQuestion", null);
        return view;
    }

    private String workspaceUri(Agent agent) {
        if (agent.runtimeInstance() == null || agent.runtimeInstance().workspaceAccess() == null) {
            return null;
        }
        return agent.runtimeInstance().workspaceAccess().uri();
    }

    private AgentConversation requireConversation(String agentId, String conversationId) {
        AgentConversation conversation = agentManager.getConversation(agentId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("No conversation " + conversationId + " on agent " + agentId);
        }
        return conversation;
    }

    private Agent requireAgent(String agentId) {
        Agent agent = agentManager.get(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("No agent with id " + agentId);
        }
        return agent;
    }

    private ResponseEntity<Void> redirect(String path) {
        return ResponseEntity.status(303).location(URI.create(path)).build();
    }

    private String conversationPath(String agentId, String conversationId) {
        return "/agents/" + agentId + "/conversations/" + conversationId;
    }
}