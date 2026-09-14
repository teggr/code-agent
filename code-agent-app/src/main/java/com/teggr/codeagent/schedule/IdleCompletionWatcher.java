package com.teggr.codeagent.schedule;

import java.time.Instant;
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

    private final TaskScheduler taskScheduler;
    private final ScheduleProperties properties;
    private final Map<String, ScheduledFuture<?>> pendingTimers = new ConcurrentHashMap<>();

    public IdleCompletionWatcher(TaskScheduler taskScheduler, ScheduleProperties properties) {
        this.taskScheduler = taskScheduler;
        this.properties = properties;
    }

    public void watch(String agentId, AgentConversation conversation, Runnable onComplete) {
        conversation.addStatusListener(status -> onStatusChange(agentId, conversation, status, onComplete));
        if (conversation.status() == AgentConversationStatus.IDLE) {
            armTimer(agentId, conversation, onComplete);
        }
    }

    private void onStatusChange(String agentId, AgentConversation conversation, AgentConversationStatus status,
            Runnable onComplete) {
        if (status == AgentConversationStatus.IDLE && conversation.pendingQuestion() == null) {
            armTimer(agentId, conversation, onComplete);
        } else {
            cancelTimer(agentId);
        }
    }

    private void armTimer(String agentId, AgentConversation conversation, Runnable onComplete) {
        cancelTimer(agentId);
        Instant fireAt = Instant.now().plus(properties.getIdleTimeout());
        ScheduledFuture<?> future = taskScheduler.schedule(() -> fire(agentId, conversation, onComplete), fireAt);
        pendingTimers.put(agentId, future);
    }

    private void cancelTimer(String agentId) {
        ScheduledFuture<?> future = pendingTimers.remove(agentId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void fire(String agentId, AgentConversation conversation, Runnable onComplete) {
        pendingTimers.remove(agentId);
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
