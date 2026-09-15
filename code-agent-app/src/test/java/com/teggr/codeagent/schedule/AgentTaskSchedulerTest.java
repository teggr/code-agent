package com.teggr.codeagent.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import com.teggr.codeagent.agent.Agent;
import com.teggr.codeagent.agent.AgentConversation;
import com.teggr.codeagent.agent.AgentEventPublisher;
import com.teggr.codeagent.agent.AgentManager;
import com.teggr.codeagent.agent.AgentStatus;

class AgentTaskSchedulerTest {

    private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
    private final ScheduledAgentTaskRepository repository = mock(ScheduledAgentTaskRepository.class);
    private final AgentManager agentManager = mock(AgentManager.class);
    private final IdleCompletionWatcher idleCompletionWatcher = mock(IdleCompletionWatcher.class);
    private final AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
    private final ScheduledTaskFragmentRenderer fragments = mock(ScheduledTaskFragmentRenderer.class);
    private final AgentTaskScheduler scheduler = new AgentTaskScheduler(taskScheduler, repository, agentManager,
            idleCompletionWatcher, eventPublisher, fragments);

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private ScheduledFuture<Object> mockFuture() {
        return (ScheduledFuture) mock(ScheduledFuture.class);
    }

    @Test
    void registerSchedulesCronTrigger() {
        ScheduledAgentTask task = new ScheduledAgentTask("task-1", null, "prompt", "0 0 9 * * *");
        doReturn(mockFuture()).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        scheduler.register(task);

        verify(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    }

    @Test
    void fireStartsAgentAndMarksTaskRunning() {
        ScheduledAgentTask task = new ScheduledAgentTask("task-1", "https://example.com/repo.git", "prompt",
                "0 0 9 * * *");
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(mockFuture()).when(taskScheduler).schedule(runnableCaptor.capture(), any(Trigger.class));
        when(repository.findById("task-1")).thenReturn(Optional.of(task));
        AgentConversation conversation = mock(AgentConversation.class);
        Agent agent = new Agent("agent-1", null, null, null, AgentStatus.RUNNING);
        when(conversation.agent()).thenReturn(agent);
        when(agentManager.startAsync("https://example.com/repo.git", "prompt")).thenReturn(conversation);

        scheduler.register(task);
        runnableCaptor.getValue().run();

        assertThat(task.status()).isEqualTo(ScheduledTaskStatus.RUNNING);
        assertThat(task.currentAgentId()).isEqualTo("agent-1");
        verify(idleCompletionWatcher).watch(any(), any(), any(), any());
    }

    @Test
    void fireSkipsWhenPreviousRunStillActive() {
        ScheduledAgentTask task = new ScheduledAgentTask("task-1", null, "prompt", "0 0 9 * * *");
        task.setStatus(ScheduledTaskStatus.RUNNING);
        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        doReturn(mockFuture()).when(taskScheduler).schedule(runnableCaptor.capture(), any(Trigger.class));
        when(repository.findById("task-1")).thenReturn(Optional.of(task));

        scheduler.register(task);
        runnableCaptor.getValue().run();

        verify(agentManager, never()).startAsync(any(), any());
        verify(agentManager, never()).startLocalAsync(any());
    }

    @Test
    void resetsInFlightRunningTasksOnStartup() {
        ScheduledAgentTask task = new ScheduledAgentTask("task-1", null, "prompt", "0 0 9 * * *");
        task.setStatus(ScheduledTaskStatus.RUNNING);
        task.setCurrentAgentId("stale-agent");
        when(repository.findAll()).thenReturn(List.of(task));
        doReturn(mockFuture()).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));

        scheduler.init();

        assertThat(task.status()).isEqualTo(ScheduledTaskStatus.SCHEDULED);
        assertThat(task.currentAgentId()).isNull();
    }
}
