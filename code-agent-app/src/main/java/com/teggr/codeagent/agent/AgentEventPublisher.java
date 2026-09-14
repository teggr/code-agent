package com.teggr.codeagent.agent;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentEventPublisher {

    private static final long EMITTER_TIMEOUT_MS = 30L * 60L * 1000L;

    private final FragmentRenderer fragments;
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> perAgent = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> perConversation = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> dashboardSubscribers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SseEmitter> allEmitters = new CopyOnWriteArrayList<>();
    private volatile Supplier<List<Agent>> dashboardAgents;

    public AgentEventPublisher(FragmentRenderer fragments) {
        this.fragments = fragments;
    }

    public void setDashboardAgents(Supplier<List<Agent>> agents) {
        dashboardAgents = agents;
    }

    public SseEmitter subscribe(String agentId) {
        return register(perAgent.computeIfAbsent(agentId, ignored -> new CopyOnWriteArrayList<>()));
    }

    public SseEmitter subscribeConversation(String conversationId) {
        return register(perConversation.computeIfAbsent(conversationId, ignored -> new CopyOnWriteArrayList<>()));
    }

    public SseEmitter subscribeDashboard() {
        return register(dashboardSubscribers);
    }

    public void publishMessage(AgentConversation conversation, ChatMessage message) {
        if (message.turnSummary()) {
            String html = fragments.turnSummary(message);
            sendAll(perConversation.get(conversation.id()), "turnSummary", html);
            sendAll(perAgent.get(conversation.agent().id()), "turnSummary", html);
            return;
        }
        String html = fragments.message(message);
        sendAll(perConversation.get(conversation.id()), "message", html);
        sendAll(perAgent.get(conversation.agent().id()), "message", html);
        if (message.fullMessage()) {
            publishAgentList();
        }
    }

    public void publishStatus(AgentConversation conversation, AgentConversationStatus status) {
        String html = fragments.conversationStatus(status);
        sendAll(perConversation.get(conversation.id()), "conversationStatus", html);
        sendAll(perAgent.get(conversation.agent().id()), "conversationStatus", html);
        publishAgentList();
    }

    public void publishQuestion(AgentConversation conversation, com.teggr.codeagent.harness.Question question) {
        String html = fragments.pendingQuestion(conversation.agent().id(), conversation.id(), question);
        sendAll(perConversation.get(conversation.id()), "question", html);
        sendAll(perAgent.get(conversation.agent().id()), "question", html);
    }

    public void publishWorkspaceLink(AgentConversation conversation) {
        var instance = conversation.agent().runtimeInstance();
        String workspaceUri = instance == null || instance.workspaceAccess() == null
                ? null : instance.workspaceAccess().uri();
        String html = fragments.vscodeLink(workspaceUri, conversation.isReady());
        sendAll(perConversation.get(conversation.id()), "vscode", html);
        sendAll(perAgent.get(conversation.agent().id()), "vscode", html);
    }

    public void publishAgentList() {
        Supplier<List<Agent>> supplier = dashboardAgents;
        if (supplier != null) {
            sendAll(dashboardSubscribers, "agentList", fragments.agentList(supplier.get()));
        }
    }

    /** Allows other packages (e.g. schedule) to push updates onto the same dashboard SSE connection. */
    public void sendToDashboard(String event, String html) {
        sendAll(dashboardSubscribers, event, html);
    }

    public void closeAll() {
        allEmitters.forEach(emitter -> {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        });
    }

    protected SseEmitter newEmitter() {
        return new SseEmitter(EMITTER_TIMEOUT_MS);
    }

    private SseEmitter register(CopyOnWriteArrayList<SseEmitter> registry) {
        SseEmitter emitter = newEmitter();
        registry.add(emitter);
        allEmitters.add(emitter);
        Runnable drop = () -> {
            registry.remove(emitter);
            allEmitters.remove(emitter);
        };
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(error -> drop.run());
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException e) {
            drop.run();
        }
        return emitter;
    }

    private void sendAll(CopyOnWriteArrayList<SseEmitter> registry, String event, String html) {
        if (registry == null || registry.isEmpty()) {
            return;
        }
        String[] lines = html.split("\r\n|\r|\n");
        for (SseEmitter emitter : registry) {
            try {
                SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event().name(event);
                for (String line : lines) {
                    eventBuilder.data(line);
                }
                emitter.send(eventBuilder);
            } catch (Exception e) {
                registry.remove(emitter);
                allEmitters.remove(emitter);
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }
    }
}