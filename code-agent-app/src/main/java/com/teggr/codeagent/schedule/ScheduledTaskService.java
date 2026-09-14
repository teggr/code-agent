package com.teggr.codeagent.schedule;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import com.teggr.codeagent.agent.AgentEventPublisher;

/** CRUD facade over scheduled task definitions, keeping the live scheduler registrations in sync. */
@Service
public class ScheduledTaskService {

    private final ScheduledAgentTaskRepository repository;
    private final AgentTaskScheduler scheduler;
    private final AgentEventPublisher eventPublisher;
    private final ScheduledTaskFragmentRenderer fragments;

    public ScheduledTaskService(ScheduledAgentTaskRepository repository, AgentTaskScheduler scheduler,
            AgentEventPublisher eventPublisher, ScheduledTaskFragmentRenderer fragments) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.eventPublisher = eventPublisher;
        this.fragments = fragments;
    }

    public ScheduledAgentTask create(String repositoryUrl, String prompt, String cronExpression) {
        validateCron(cronExpression);
        ScheduledAgentTask task = new ScheduledAgentTask(UUID.randomUUID().toString(),
                repositoryUrl == null || repositoryUrl.isBlank() ? null : repositoryUrl, prompt, cronExpression);
        repository.save(task);
        scheduler.register(task);
        publishList();
        return task;
    }

    public void pause(String taskId) {
        ScheduledAgentTask task = require(taskId);
        task.setEnabled(false);
        repository.save(task);
        scheduler.unregister(taskId);
        publishList();
    }

    public void resume(String taskId) {
        ScheduledAgentTask task = require(taskId);
        task.setEnabled(true);
        task.setStatus(ScheduledTaskStatus.SCHEDULED);
        repository.save(task);
        scheduler.register(task);
        publishList();
    }

    public void delete(String taskId) {
        scheduler.unregister(taskId);
        repository.deleteById(taskId);
        publishList();
    }

    public Collection<ScheduledAgentTask> list() {
        return List.copyOf(repository.findAll());
    }

    private void publishList() {
        eventPublisher.sendToDashboard("scheduledTaskList", fragments.scheduledTaskList(list()));
    }

    private ScheduledAgentTask require(String taskId) {
        return repository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("No scheduled task with id " + taskId));
    }

    private void validateCron(String cronExpression) {
        new CronTrigger(cronExpression);
    }
}
