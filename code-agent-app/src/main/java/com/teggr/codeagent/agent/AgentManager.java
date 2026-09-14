package com.teggr.codeagent.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.teggr.codeagent.agent.runtime.AgentRuntime;
import com.teggr.codeagent.agent.runtime.AgentRuntimeInstance;
import com.teggr.codeagent.agent.runtime.AgentRuntimeRequest;
import com.teggr.codeagent.agent.runtime.DiscoveredAgent;
import com.teggr.codeagent.harness.AgentHarness;
import com.teggr.codeagent.harness.AgentHarnessFactory;

@Service
public class AgentManager {

    private final AgentRuntime agentRuntime;
    private final AgentHarnessFactory harnessFactory;
    private final AgentEventPublisher eventPublisher;
    private final Map<String, Agent> agents = new ConcurrentHashMap<>();
    private final Map<String, Map<String, AgentConversation>> conversationsByAgent = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AgentManager(AgentRuntime agentRuntime, AgentHarnessFactory harnessFactory,
            AgentEventPublisher eventPublisher) {
        this.agentRuntime = agentRuntime;
        this.harnessFactory = harnessFactory;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    void init() {
        eventPublisher.setDashboardAgents(() -> List.copyOf(agents.values()));
    }

    public Agent start(String repositoryUrl) throws Exception {
        String agentId = UUID.randomUUID().toString();
        GitRepositoryWorkspace workspace = new GitRepositoryWorkspace(repositoryUrl);
        AgentRuntimeInstance instance = agentRuntime.provision(new AgentRuntimeRequest(agentId, workspace));
        AgentConnection connection;
        try {
            connection = connect(instance);
        } catch (Exception e) {
            agentRuntime.delete(instance.runtimeId());
            throw e;
        }
        Agent agent = new Agent(agentId, workspace, instance, connection, AgentStatus.RUNNING);
        AgentConversation conversation = new AgentConversation(agent);
        wireEvents(conversation);
        try {
            conversation.attachHarnessSession(connection.harness().createSession(conversation.id()));
        } catch (Exception e) {
            closeAfterFailedStart(connection, agentId);
            agentRuntime.delete(instance.runtimeId());
            throw e;
        }
        conversation.setStatus(AgentConversationStatus.IDLE);
        register(agent, conversation);
        eventPublisher.publishAgentList();
        return agent;
    }

    public AgentConversation startAsync(String repositoryUrl, String prompt) {
        return startAsync(new GitRepositoryWorkspace(repositoryUrl), prompt);
    }

    public AgentConversation startLocalAsync(String prompt) {
        return startAsync(new LocalWorkspace(), prompt);
    }

    private AgentConversation startAsync(WorkspaceSpec workspace, String prompt) {
        String agentId = UUID.randomUUID().toString();
        Agent agent = new Agent(agentId, workspace, null, null, AgentStatus.PROVISIONING);
        AgentConversation conversation = new AgentConversation(agent);
        wireEvents(conversation);
        conversation.addMessage("user", prompt);
        register(agent, conversation);
        eventPublisher.publishAgentList();

        executor.submit(() -> {
            AgentRuntimeInstance instance = null;
            AgentConnection connection = null;
            try {
                instance = agentRuntime.provision(new AgentRuntimeRequest(agentId, agent.workspace()));
                updateAgent(agent.withConnection(instance, null, AgentStatus.CONNECTING));
                connection = connect(instance);
                Agent connected = new Agent(agentId, agent.workspace(), instance, connection, AgentStatus.RUNNING);
                updateAgent(connected);
                conversation.attachHarnessSession(connection.harness().createSession(conversation.id()));
                eventPublisher.publishWorkspaceLink(conversation);
                conversation.setStatus(AgentConversationStatus.BUSY);
                conversation.harnessSession().sendPrompt(prompt);
            } catch (Exception e) {
                closeAfterFailedStart(connection, agentId);
                if (instance != null) {
                    agentRuntime.delete(instance.runtimeId());
                }
                updateAgent(currentAgent(agentId).withStatus(AgentStatus.FAILED));
                conversation.setStatus(AgentConversationStatus.FAILED);
                conversation.addMessage("assistant", "Error starting agent: " + e.getMessage());
            }
        });
        return conversation;
    }

    public AgentConversation start(String repositoryUrl, String prompt) throws Exception {
        AgentConversation conversation = startAsync(repositoryUrl, prompt);
        while (conversation.status() == AgentConversationStatus.STARTING) {
            Thread.sleep(25);
        }
        return conversation;
    }

    public AgentConversation createConversation(String agentId) throws Exception {
        Agent agent = requireAgent(agentId);
        if (agent.connection() == null) {
            throw new IllegalStateException("Agent " + agentId + " has no connected harness");
        }
        AgentConversation conversation = new AgentConversation(agent);
        wireEvents(conversation);
        conversation.attachHarnessSession(agent.connection().harness().createSession(conversation.id()));
        conversation.setStatus(AgentConversationStatus.IDLE);
        conversationsByAgent.computeIfAbsent(agentId, ignored -> new ConcurrentHashMap<>())
                .put(conversation.id(), conversation);
        eventPublisher.publishAgentList();
        return conversation;
    }

    public void removeConversation(String agentId, String conversationId) {
        AgentConversation conversation = requireConversation(agentId, conversationId);
        conversationsByAgent.get(agentId).remove(conversationId);
        conversation.detachHarnessSession();
        eventPublisher.publishAgentList();
    }

    public void stop(String agentId) {
        Agent agent = requireAgent(agentId);
        for (AgentConversation conversation : conversations(agentId)) {
            conversation.detachHarnessSession();
        }
        closeConnection(agent.connection(), agentId);
        agentRuntime.stop(agent.runtimeInstance().runtimeId());
        updateAgent(new Agent(agent.id(), agent.workspace(), agent.runtimeInstance(), null, AgentStatus.STOPPED));
        eventPublisher.publishAgentList();
    }

    public void restart(String agentId) {
        Agent agent = requireAgent(agentId);
        if (agent.connection() != null) {
            return;
        }
        updateAgent(agent.withStatus(AgentStatus.CONNECTING));
        eventPublisher.publishAgentList();
        executor.submit(() -> reconnect(agentId, true));
    }

    public void remove(String agentId) {
        Agent agent = agents.remove(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("No agent with id " + agentId);
        }
        List<AgentConversation> conversations = List.copyOf(conversations(agentId));
        conversationsByAgent.remove(agentId);
        closeConnection(agent.connection(), agentId);
        agentRuntime.delete(agent.runtimeInstance().runtimeId());
        Agent removed = new Agent(agent.id(), agent.workspace(), agent.runtimeInstance(), null, AgentStatus.REMOVED);
        conversations.forEach(conversation -> {
            conversation.setAgent(removed);
            conversation.detachHarnessSession();
        });
        eventPublisher.publishAgentList();
    }

    public void adoptExisting() {
        for (DiscoveredAgent discovered : agentRuntime.discover()) {
            if (!agents.containsKey(discovered.agentId())) {
                executor.submit(() -> adopt(discovered));
            }
        }
        eventPublisher.publishAgentList();
    }

    private void adopt(DiscoveredAgent discovered) {
        String agentId = discovered.agentId();
        try {
            AgentRuntimeInstance instance = discovered.running()
                    ? discovered.instance() : agentRuntime.start(discovered.instance());
            AgentConnection connection = connect(instance);
            Agent agent = new Agent(agentId, discovered.workspace(), instance, connection, AgentStatus.RUNNING);
            agents.put(agentId, agent);
            List<String> conversationIds = connection.harness().listSessionIds();
            if (conversationIds == null || conversationIds.isEmpty()) {
                AgentConversation conversation = new AgentConversation(agent);
                wireEvents(conversation);
                conversation.attachHarnessSession(connection.harness().createSession(conversation.id()));
                conversation.addMessage("system", "Reconnected to an existing agent; no persisted conversation history was found.");
                conversation.setStatus(AgentConversationStatus.IDLE);
                register(agent, conversation);
            } else {
                for (String conversationId : conversationIds) {
                    AgentConversation conversation = new AgentConversation(conversationId, agent);
                    wireEvents(conversation);
                    try {
                        conversation.attachHarnessSession(connection.harness().resumeSession(conversationId));
                        conversation.setStatus(AgentConversationStatus.IDLE);
                    } catch (Exception e) {
                        conversation.setStatus(AgentConversationStatus.FAILED);
                        conversation.addMessage("system", "The earlier conversation could not be resumed.");
                    }
                    register(agent, conversation);
                }
            }
        } catch (Exception e) {
            System.err.println("[agent " + agentId + "] Adoption failed: " + e.getMessage());
        }
        eventPublisher.publishAgentList();
    }

    private void reconnect(String agentId, boolean startRuntime) {
        Agent agent = requireAgent(agentId);
        try {
            AgentRuntimeInstance instance = startRuntime
                    ? agentRuntime.start(agent.runtimeInstance()) : agent.runtimeInstance();
            AgentConnection connection = connect(instance);
            Agent connected = new Agent(agent.id(), agent.workspace(), instance, connection, AgentStatus.RUNNING);
            updateAgent(connected);
            for (AgentConversation conversation : conversations(agentId)) {
                try {
                    conversation.attachHarnessSession(connection.harness().resumeSession(conversation.id()));
                    eventPublisher.publishWorkspaceLink(conversation);
                    conversation.setStatus(AgentConversationStatus.IDLE);
                } catch (Exception e) {
                    conversation.setStatus(AgentConversationStatus.FAILED);
                    conversation.addMessage("system", "The earlier conversation could not be resumed.");
                }
            }
        } catch (Exception e) {
            updateAgent(currentAgent(agentId).withStatus(AgentStatus.FAILED));
        }
        eventPublisher.publishAgentList();
    }

    private AgentConnection connect(AgentRuntimeInstance instance) throws Exception {
        AgentHarness harness = harnessFactory.connect(instance.harnessPort(), instance.workingDirectory(),
                () -> agentRuntime.verifyRunning(instance.runtimeId()));
        return new AgentConnection(harness);
    }

    private void register(Agent agent, AgentConversation conversation) {
        agents.putIfAbsent(agent.id(), agent);
        conversationsByAgent.computeIfAbsent(agent.id(), ignored -> new ConcurrentHashMap<>())
                .put(conversation.id(), conversation);
    }

    private void wireEvents(AgentConversation conversation) {
        conversation.setListener(new AgentConversationListener() {
            @Override
            public void onMessage(AgentConversation source, ChatMessage message) {
                eventPublisher.publishMessage(source, message);
            }

            @Override
            public void onStatusChange(AgentConversation source, AgentConversationStatus status) {
                eventPublisher.publishStatus(source, status);
            }

            @Override
            public void onQuestionChange(AgentConversation source, com.teggr.codeagent.harness.Question question) {
                eventPublisher.publishQuestion(source, question);
            }
        });
    }

    private void updateAgent(Agent agent) {
        agents.put(agent.id(), agent);
        conversations(agent.id()).forEach(conversation -> conversation.setAgent(agent));
    }

    private Agent currentAgent(String agentId) {
        return agents.get(agentId);
    }

    private Agent requireAgent(String agentId) {
        Agent agent = agents.get(agentId);
        if (agent == null) {
            throw new IllegalArgumentException("No agent with id " + agentId);
        }
        return agent;
    }

    private AgentConversation requireConversation(String agentId, String conversationId) {
        AgentConversation conversation = getConversation(agentId, conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("No conversation " + conversationId + " on agent " + agentId);
        }
        return conversation;
    }

    private void closeAfterFailedStart(AgentConnection connection, String agentId) {
        closeConnection(connection, agentId);
    }

    private void closeConnection(AgentConnection connection, String agentId) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception e) {
            System.err.println("Error closing harness connection for agent " + agentId + ": " + e.getMessage());
        }
    }

    public Agent get(String agentId) {
        return agents.get(agentId);
    }

    public AgentConversation getConversation(String agentId) {
        return conversations(agentId).stream().findFirst().orElse(null);
    }

    public AgentConversation getConversation(String agentId, String conversationId) {
        Map<String, AgentConversation> conversations = conversationsByAgent.get(agentId);
        return conversations == null ? null : conversations.get(conversationId);
    }

    public Collection<AgentConversation> conversations(String agentId) {
        Map<String, AgentConversation> conversations = conversationsByAgent.get(agentId);
        return conversations == null ? List.of() : List.copyOf(conversations.values());
    }

    public Collection<Agent> list() {
        return List.copyOf(agents.values());
    }

    public SseEmitter subscribe(String agentId) {
        return eventPublisher.subscribe(agentId);
    }

    public SseEmitter subscribeConversation(String conversationId) {
        return eventPublisher.subscribeConversation(conversationId);
    }

    public SseEmitter subscribeDashboard() {
        return eventPublisher.subscribeDashboard();
    }

    public ExecutorService executor() {
        return executor;
    }

    public void stopAll() {
        for (String agentId : new ArrayList<>(agents.keySet())) {
            try {
                stop(agentId);
            } catch (Exception e) {
                System.err.println("Error stopping agent " + agentId + ": " + e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        stopAll();
        eventPublisher.closeAll();
        executor.shutdownNow();
    }
}