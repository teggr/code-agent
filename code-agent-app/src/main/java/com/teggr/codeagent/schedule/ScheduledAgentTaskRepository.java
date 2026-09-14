package com.teggr.codeagent.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScheduledAgentTaskRepository extends JpaRepository<ScheduledAgentTask, String> {
}
