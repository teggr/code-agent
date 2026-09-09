package com.teggr.codeagent.runner;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.teggr.codeagent.agent.AgentSession;
import com.teggr.codeagent.agent.Question;

public class RunnerSession {

    private volatile Runner runner;
    private final List<ChatMessage> messages = new CopyOnWriteArrayList<>();
    private final AtomicReference<RunnerStatus> status = new AtomicReference<>(RunnerStatus.STARTING);
    private volatile AgentSession agentSession;
    private volatile RunnerSessionListener listener;
    private final Map<String, CompletableFuture<String>> pendingQuestions = new ConcurrentHashMap<>();
    private volatile Question pendingQuestion;

    public RunnerSession(Runner runner) {
        this.runner = runner;
    }

    /** Registers the single listener that receives message and status events. */
    public void setListener(RunnerSessionListener listener) {
        this.listener = listener;
    }

    /** Attaches the agent session and registers message listeners exactly once. */
    public void attachAgent(AgentSession agentSession) {
        this.agentSession = agentSession;
        agentSession.onMessage(content -> addMessage("assistant", content));
        agentSession.onIdle(() -> {
            if (status() != RunnerStatus.FAILED) {
                setStatus(RunnerStatus.IDLE);
            }
        });
        agentSession.onError(error -> {
            setStatus(RunnerStatus.FAILED);
            addMessage("assistant", "Copilot error: " + error);
        });
        agentSession.onToolActivity(activity -> addMessage("tool", activity.summary()));
        agentSession.onEvent(event -> addMessage("system", event.summary()));
        agentSession.onQuestion(question -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            pendingQuestions.put(question.id(), future);
            pendingQuestion = question;
            addMessage("question", question.prompt());
            RunnerSessionListener current = listener;
            if (current != null) {
                current.onQuestionChange(this, question);
            }
            return future;
        });
    }

    /** Completes a pending agent question with the user's answer; no-op if the question is unknown or already answered. */
    public void answerQuestion(String questionId, String answer) {
        CompletableFuture<String> future = pendingQuestions.remove(questionId);
        if (future == null) {
            return;
        }
        pendingQuestion = null;
        addMessage("user", answer);
        RunnerSessionListener current = listener;
        if (current != null) {
            current.onQuestionChange(this, null);
        }
        future.complete(answer);
    }

    public Question pendingQuestion() {
        return pendingQuestion;
    }

    public Runner runner() {
        return runner;
    }


    public void setRunner(Runner runner) {
        this.runner = runner;
    }

    /** False until the real container/agent has connected; the placeholder devContainerUri points nowhere until then. */
    public boolean isReady() {
        return runner.harness() != null;
    }

    public RunnerStatus status() {
        return status.get();
    }

    public void setStatus(RunnerStatus newStatus) {
        RunnerStatus previous = this.status.getAndSet(newStatus);
        RunnerSessionListener current = listener;
        if (current != null && previous != newStatus) {
            current.onStatusChange(this, newStatus);
        }
    }

    public AgentSession agentSession() {
        return agentSession;
    }

    public void addMessage(String role, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if ("assistant".equals(role)) {
            System.out.println("[runner " + runner.id() + "] Assistant message captured: " + abbreviate(content));
        }
        ChatMessage message = new ChatMessage(UUID.randomUUID().toString(), role, content, Instant.now());
        this.messages.add(message);
        RunnerSessionListener current = listener;
        if (current != null) {
            current.onMessage(this, message);
        }
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
