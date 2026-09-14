package com.teggr.codeagent.schedule;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import jakarta.annotation.PostConstruct;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import com.teggr.codeagent.agent.AgentConversation;
import com.teggr.codeagent.agent.AgentEventPublisher;
import com.teggr.codeagent.agent.AgentManager;

/** Registers a Spring cron trigger per enabled ScheduledAgentTask and provisions a fresh agent on each fire. */
@Component
public class AgentTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskScheduler.class);

    private final TaskScheduler taskScheduler;
    private final ScheduledAgentTaskRepository repository;
    private final AgentManager agentManager;
    private final IdleCompletionWatcher idleCompletionWatcher;
    private final AgentEventPublisher eventPublisher;
    private final ScheduledTaskFragmentRenderer fragments;
    private final Map<String, ScheduledFuture<?>> activeTriggers = new ConcurrentHashMap<>();

    public AgentTaskScheduler(TaskScheduler taskScheduler, ScheduledAgentTaskRepository repository,
            AgentManager agentManager, IdleCompletionWatcher idleCompletionWatcher,
            AgentEventPublisher eventPublisher, ScheduledTaskFragmentRenderer fragments) {
        this.taskScheduler = taskScheduler;
        this.repository = repository;
        this.agentManager = agentManager;
        this.idleCompletionWatcher = idleCompletionWatcher;
        this.eventPublisher = eventPublisher;
        this.fragments = fragments;
    }

    @PostConstruct
    void init() {
        List<ScheduledAgentTask> tasks = repository.findAll();
        for (ScheduledAgentTask task : tasks) {
            if (task.status() == ScheduledTaskStatus.RUNNING) {
                // In-flight run state doesn't survive a restart; the agent, if still running, is picked up separately by agent discovery.
                task.setStatus(ScheduledTaskStatus.SCHEDULED);
                task.setCurrentAgentId(null);
                repository.save(task);
            }
            if (task.enabled()) {
                register(task);
            }
        }
    }

    public void register(ScheduledAgentTask task) {
        unregister(task.id());
        CronTrigger trigger = new CronTrigger(task.cronExpression());
        ScheduledFuture<?> future = taskScheduler.schedule(() -> fire(task.id()), trigger);
        activeTriggers.put(task.id(), future);
    }

    public void unregister(String taskId) {
        ScheduledFuture<?> future = activeTriggers.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void fire(String taskId) {
        ScheduledAgentTask task = repository.findById(taskId).orElse(null);
        if (task == null || !task.enabled()) {
            return;
        }
        if (task.status() == ScheduledTaskStatus.RUNNING) {
            log.info("Skipping scheduled fire for task {}: previous run still active", taskId);
            return;
        }
        try {
            AgentConversation conversation = task.repositoryUrl() == null || task.repositoryUrl().isBlank()
                    ? agentManager.startLocalAsync(task.prompt())
                    : agentManager.startAsync(task.repositoryUrl(), task.prompt());
            String agentId = conversation.agent().id();
            task.setStatus(ScheduledTaskStatus.RUNNING);
            task.setLastTriggeredAt(Instant.now());
            task.setCurrentAgentId(agentId);
            repository.save(task);
            idleCompletionWatcher.watch(agentId, conversation, () -> complete(taskId, agentId));
        } catch (Exception e) {
            log.warn("Failed to start scheduled agent for task {}: {}", taskId, e.getMessage());
            task.setStatus(ScheduledTaskStatus.FAILED);
            repository.save(task);
        }
        publishList();
    }

    private void complete(String taskId, String agentId) {
        agentManager.remove(agentId);
        repository.findById(taskId).ifPresent(task -> {
            task.setStatus(ScheduledTaskStatus.SCHEDULED);
            task.setCurrentAgentId(null);
            task.setLastCompletedAt(Instant.now());
            repository.save(task);
        });
        publishList();
    }

    private void publishList() {
        eventPublisher.sendToDashboard("scheduledTaskList", fragments.scheduledTaskList(repository.findAll()));
    }
}
