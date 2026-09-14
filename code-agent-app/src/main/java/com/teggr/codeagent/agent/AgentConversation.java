package com.teggr.codeagent.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.teggr.codeagent.harness.HarnessHistoryEntry;
import com.teggr.codeagent.harness.HarnessSession;
import com.teggr.codeagent.harness.Question;

public class AgentConversation {

    private final String id;
    private volatile Agent agent;
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final AtomicReference<AgentConversationStatus> status =
            new AtomicReference<>(AgentConversationStatus.STARTING);
    private volatile HarnessSession harnessSession;
    private volatile AgentConversationListener listener;
    private final Map<String, CompletableFuture<String>> pendingQuestions = new ConcurrentHashMap<>();
    private volatile Question pendingQuestion;

    public AgentConversation(Agent agent) {
        this(UUID.randomUUID().toString(), agent);
    }

    public AgentConversation(String id, Agent agent) {
        this.id = id;
        this.agent = agent;
    }

    public String id() {
        return id;
    }

    public Agent agent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    public void setListener(AgentConversationListener listener) {
        this.listener = listener;
    }

    public void attachHarnessSession(HarnessSession session) {
        this.harnessSession = session;
        loadHistory(session);
        session.onMessage(content -> addMessage("assistant", content));
        session.onIdle(() -> {
            if (status() != AgentConversationStatus.FAILED) {
                setStatus(AgentConversationStatus.IDLE);
            }
        });
        session.onError(error -> {
            setStatus(AgentConversationStatus.FAILED);
            addMessage("assistant", "Copilot error: " + error);
        });
        session.onToolActivity(activity -> addMessage("tool", activity.summary()));
        session.onEvent(event -> addMessage("system", event.summary()));
        session.onQuestion(question -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingQuestions.put(question.id(), future);
            pendingQuestion = question;
            addMessage("question", question.prompt());
            AgentConversationListener current = listener;
            if (current != null) {
                current.onQuestionChange(this, question);
            }
            return future;
        });
    }

    private void loadHistory(HarnessSession session) {
        if (!messages.isEmpty()) {
            return;
        }
        try {
            List<HarnessHistoryEntry> history = session.history();
            if (history == null) {
                return;
            }
            for (HarnessHistoryEntry entry : history) {
                if (entry != null && entry.content() != null && !entry.content().isBlank()) {
                    messages.add(new ChatMessage(UUID.randomUUID().toString(), entry.role(), entry.content(), Instant.now()));
                }
            }
        } catch (Exception e) {
            System.err.println("Error loading conversation history for agent " + agent.id() + ": " + e.getMessage());
        }
    }

    public void detachHarnessSession() {
        harnessSession = null;
        pendingQuestion = null;
        pendingQuestions.values().forEach(future -> future.complete(""));
        pendingQuestions.clear();
    }

    public void answerQuestion(String questionId, String answer) {
        CompletableFuture<String> future = pendingQuestions.remove(questionId);
        if (future == null) {
            return;
        }
        pendingQuestion = null;
        addMessage("user", answer);
        AgentConversationListener current = listener;
        if (current != null) {
            current.onQuestionChange(this, null);
        }
        future.complete(answer);
    }

    public Question pendingQuestion() {
        return pendingQuestion;
    }

    public boolean isReady() {
        return agent.status() == AgentStatus.RUNNING && agent.connection() != null && harnessSession != null;
    }

    public AgentConversationStatus status() {
        return status.get();
    }

    public void setStatus(AgentConversationStatus newStatus) {
        AgentConversationStatus previous = status.getAndSet(newStatus);
        AgentConversationListener current = listener;
        if (current != null && previous != newStatus) {
            current.onStatusChange(this, newStatus);
        }
    }

    public HarnessSession harnessSession() {
        return harnessSession;
    }

    public void addMessage(String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        messages.add(new ChatMessage(UUID.randomUUID().toString(), role, content, Instant.now()));
        AgentConversationListener current = listener;
        if (current != null) {
            current.onMessage(this, messages.get(messages.size() - 1));
        }
    }

    public List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public String lastMessage() {
        return messages.isEmpty() ? "No messages yet" : messages.get(messages.size() - 1).content();
    }
}