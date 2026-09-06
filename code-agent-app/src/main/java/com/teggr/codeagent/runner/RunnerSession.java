package com.teggr.codeagent.runner;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.teggr.codeagent.agent.AgentSession;

public class RunnerSession {

    private volatile Runner runner;
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final AtomicReference<RunnerStatus> status = new AtomicReference<>(RunnerStatus.STARTING);
    private volatile AgentSession agentSession;

    public RunnerSession(Runner runner) {
        this.runner = runner;
    }

    /** Attaches the agent session and registers message listeners exactly once. */
    public void attachAgent(AgentSession agentSession) {
        this.agentSession = agentSession;
        agentSession.onMessage(content -> addMessage("assistant", content));
        agentSession.onIdle(() -> setStatus(RunnerStatus.IDLE));
    }

    public Runner runner() {
        return runner;
    }

    public void setRunner(Runner runner) {
        this.runner = runner;
    }

    public RunnerStatus status() {
        return status.get();
    }

    public void setStatus(RunnerStatus newStatus) {
        this.status.set(newStatus);
    }

    public AgentSession agentSession() {
        return agentSession;
    }

    public void addMessage(String role, String content) {
        if ("assistant".equals(role)) {
            System.out.println("[runner " + runner.id() + "] Assistant message captured: " + abbreviate(content));
        }
        this.messages.add(new ChatMessage(UUID.randomUUID().toString(), role, content, Instant.now()));
    }

    private static String abbreviate(String content) {
        if (content == null) {
            return "<null>";
        }
        String singleLine = content.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 200 ? singleLine : singleLine.substring(0, 200) + "...";
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public String lastMessage() {
        if (messages.isEmpty()) {
            return "No messages yet";
        }
        return messages.get(messages.size() - 1).content();
    }
}
