package com.teggr.codeagent.schedule;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;

/** Persisted definition of a recurring agent run, independent of any live Agent/AgentConversation. */
@Entity
public class ScheduledAgentTask {

    @Id
    private String id;

    @Column(nullable = true)
    private String repositoryUrl;

    @Lob
    @Column(nullable = false)
    private String prompt;

    @Column(nullable = false)
    private String cronExpression;

    @Column(nullable = false)
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScheduledTaskStatus status = ScheduledTaskStatus.SCHEDULED;

    @Column(nullable = true)
    private String currentAgentId;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = true)
    private Instant lastTriggeredAt;

    @Column(nullable = true)
    private Instant lastCompletedAt;

    protected ScheduledAgentTask() {
        // JPA
    }

    public ScheduledAgentTask(String id, String repositoryUrl, String prompt, String cronExpression) {
        this.id = id;
        this.repositoryUrl = repositoryUrl;
        this.prompt = prompt;
        this.cronExpression = cronExpression;
    }

    public String id() {
        return id;
    }

    public String repositoryUrl() {
        return repositoryUrl;
    }

    public String prompt() {
        return prompt;
    }

    public String cronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public ScheduledTaskStatus status() {
        return status;
    }

    public void setStatus(ScheduledTaskStatus status) {
        this.status = status;
    }

    public String currentAgentId() {
        return currentAgentId;
    }

    public void setCurrentAgentId(String currentAgentId) {
        this.currentAgentId = currentAgentId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant lastTriggeredAt() {
        return lastTriggeredAt;
    }

    public void setLastTriggeredAt(Instant lastTriggeredAt) {
        this.lastTriggeredAt = lastTriggeredAt;
    }

    public Instant lastCompletedAt() {
        return lastCompletedAt;
    }

    public void setLastCompletedAt(Instant lastCompletedAt) {
        this.lastCompletedAt = lastCompletedAt;
    }
}
