package com.teggr.codeagent.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import com.teggr.codeagent.agent.Agent;
import com.teggr.codeagent.agent.AgentConversation;
import com.teggr.codeagent.agent.AgentConversationStatus;
import com.teggr.codeagent.agent.AgentStatus;

class IdleCompletionWatcherTest {

    private final ThreadPoolTaskScheduler taskScheduler = newRunningScheduler();
    private final ScheduleProperties properties = new ScheduleProperties();
    private final IdleCompletionWatcher watcher = new IdleCompletionWatcher(taskScheduler, properties);

    private static ThreadPoolTaskScheduler newRunningScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.initialize();
        return scheduler;
    }

    @AfterEach
    void shutdown() {
        taskScheduler.shutdown();
    }

    @Test
    void completesAfterIdleTimeoutElapses() throws InterruptedException {
        properties.setIdleTimeout(Duration.ofMillis(50));
        AgentConversation conversation = new AgentConversation(new Agent("agent-1", null, null, null, AgentStatus.RUNNING));
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean completed = new AtomicBoolean(false);

        watcher.watch("agent-1", conversation, () -> {
            completed.set(true);
            latch.countDown();
        });
        conversation.setStatus(AgentConversationStatus.BUSY);
        conversation.setStatus(AgentConversationStatus.IDLE);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(completed).isTrue();
    }

    @Test
    void newActivityCancelsPendingCompletion() throws InterruptedException {
        properties.setIdleTimeout(Duration.ofMillis(200));
        AgentConversation conversation = new AgentConversation(new Agent("agent-1", null, null, null, AgentStatus.RUNNING));
        AtomicBoolean completed = new AtomicBoolean(false);

        watcher.watch("agent-1", conversation, () -> completed.set(true));
        conversation.setStatus(AgentConversationStatus.IDLE);
        conversation.setStatus(AgentConversationStatus.BUSY);

        Thread.sleep(400);

        assertThat(completed).isFalse();
    }

    @Test
    void shutdownEstimatesReflectArmedAndCancelledTimer() {
        properties.setIdleTimeout(Duration.ofMinutes(5));
        AgentConversation conversation = new AgentConversation(new Agent("agent-1", null, null, null, AgentStatus.RUNNING));
        ScheduledAgentTask task = new ScheduledAgentTask("task-1", null, "prompt", "0 0 9 * * *");
        task.setCurrentAgentId("agent-1");

        watcher.watch("agent-1", conversation, () -> { });
        conversation.setStatus(AgentConversationStatus.IDLE);

        assertThat(watcher.shutdownEstimates(List.of(task))).containsKey("task-1");

        conversation.setStatus(AgentConversationStatus.BUSY);

        assertThat(watcher.shutdownEstimates(List.of(task))).isEmpty();
    }
}
