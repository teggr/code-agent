package com.teggr.codeagent.schedule;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import com.teggr.codeagent.agent.AgentConversation;
import com.teggr.codeagent.agent.AgentConversationStatus;

/** Removes a scheduler-started agent once its conversation has been idle for a fixed timeout, treating that as task completion. */
@Component
public class IdleCompletionWatcher {

    private static final Logger log = LoggerFactory.getLogger(IdleCompletionWatcher.class);
    private static final DateTimeFormatter SHUTDOWN_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final Runnable NO_OP = () -> {
    };

    private final TaskScheduler taskScheduler;
    private final ScheduleProperties properties;
    private final Map<String, ScheduledFuture<?>> pendingTimers = new ConcurrentHashMap<>();
    private final Map<String, Instant> pendingShutdowns = new ConcurrentHashMap<>();

    public IdleCompletionWatcher(TaskScheduler taskScheduler, ScheduleProperties properties) {
        this.taskScheduler = taskScheduler;
        this.properties = properties;
    }

    public void watch(String agentId, AgentConversation conversation, Runnable onComplete) {
        watch(agentId, conversation, onComplete, NO_OP);
    }

    public void watch(String agentId, AgentConversation conversation, Runnable onComplete,
            Runnable onTimerStateChanged) {
        conversation.addStatusListener(
                status -> onStatusChange(agentId, conversation, status, onComplete, onTimerStateChanged));
        if (conversation.status() == AgentConversationStatus.IDLE) {
            armTimer(agentId, conversation, onComplete, onTimerStateChanged);
        }
    }

    /** Rough wall-clock time each task's currently running agent is expected to be torn down, keyed by task id. */
    public Map<String, String> shutdownEstimates(Iterable<ScheduledAgentTask> tasks) {
        Map<String, String> estimates = new LinkedHashMap<>();
        for (ScheduledAgentTask task : tasks) {
            Instant shutdownAt = task.currentAgentId() == null ? null : pendingShutdowns.get(task.currentAgentId());
            if (shutdownAt != null) {
                estimates.put(task.id(), SHUTDOWN_TIME_FORMAT.format(shutdownAt));
            }
        }
        return estimates;
    }

    private void onStatusChange(String agentId, AgentConversation conversation, AgentConversationStatus status,
            Runnable onComplete, Runnable onTimerStateChanged) {
        if (status == AgentConversationStatus.IDLE && conversation.pendingQuestion() == null) {
            armTimer(agentId, conversation, onComplete, onTimerStateChanged);
        } else {
            cancelTimer(agentId, onTimerStateChanged);
        }
    }

    private void armTimer(String agentId, AgentConversation conversation, Runnable onComplete,
            Runnable onTimerStateChanged) {
        cancelExistingTimer(agentId);
        Instant fireAt = Instant.now().plus(properties.getIdleTimeout());
        pendingShutdowns.put(agentId, fireAt);
        ScheduledFuture<?> future = taskScheduler
                .schedule(() -> fire(agentId, conversation, onComplete, onTimerStateChanged), fireAt);
        pendingTimers.put(agentId, future);
        onTimerStateChanged.run();
    }

    private void cancelTimer(String agentId, Runnable onTimerStateChanged) {
        boolean cancelled = cancelExistingTimer(agentId);
        if (cancelled) {
            onTimerStateChanged.run();
        }
    }

    private boolean cancelExistingTimer(String agentId) {
        ScheduledFuture<?> future = pendingTimers.remove(agentId);
        boolean hadShutdown = pendingShutdowns.remove(agentId) != null;
        if (future != null) {
            future.cancel(false);
        }
        return future != null || hadShutdown;
    }

    private void fire(String agentId, AgentConversation conversation, Runnable onComplete,
            Runnable onTimerStateChanged) {
        pendingTimers.remove(agentId);
        pendingShutdowns.remove(agentId);
        if (conversation.status() != AgentConversationStatus.IDLE || conversation.pendingQuestion() != null) {
            return;
        }
        try {
            onComplete.run();
        } catch (Exception e) {
            log.warn("Error completing scheduled agent {}: {}", agentId, e.getMessage());
        }
    }
}
