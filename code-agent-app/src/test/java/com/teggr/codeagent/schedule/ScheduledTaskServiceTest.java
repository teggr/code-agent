package com.teggr.codeagent.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.teggr.codeagent.agent.AgentEventPublisher;

class ScheduledTaskServiceTest {

    private final ScheduledAgentTaskRepository repository = mock(ScheduledAgentTaskRepository.class);
    private final AgentTaskScheduler scheduler = mock(AgentTaskScheduler.class);
    private final AgentEventPublisher eventPublisher = mock(AgentEventPublisher.class);
    private final ScheduledTaskFragmentRenderer fragments = mock(ScheduledTaskFragmentRenderer.class);
    private final ScheduledTaskService service = new ScheduledTaskService(repository, scheduler, eventPublisher,
            fragments);

    @Test
    void createValidatesCronRegistersAndPersists() {
        when(repository.findAll()).thenReturn(List.of());

        ScheduledAgentTask task = service.create("https://example.com/repo.git", "do the thing", "0 0 9 * * *");

        assertThat(task.repositoryUrl()).isEqualTo("https://example.com/repo.git");
        assertThat(task.prompt()).isEqualTo("do the thing");
        assertThat(task.status()).isEqualTo(ScheduledTaskStatus.SCHEDULED);
        verify(repository).save(task);
        verify(scheduler).register(task);
    }

    @Test
    void blankRepositoryUrlBecomesNullForEmptyWorkspace() {
        ScheduledAgentTask task = service.create("  ", "prompt", "0 0 9 * * *");

        assertThat(task.repositoryUrl()).isNull();
    }

    @Test
    void createRejectsInvalidCron() {
        assertThatThrownBy(() -> service.create(null, "prompt", "not a cron"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void pauseDisablesAndUnregisters() {
        ScheduledAgentTask task = new ScheduledAgentTask("task-1", null, "prompt", "0 0 9 * * *");
        when(repository.findById("task-1")).thenReturn(Optional.of(task));

        service.pause("task-1");

        assertThat(task.enabled()).isFalse();
        verify(repository).save(task);
        verify(scheduler).unregister("task-1");
    }

    @Test
    void resumeEnablesResetsStatusAndRegisters() {
        ScheduledAgentTask task = new ScheduledAgentTask("task-1", null, "prompt", "0 0 9 * * *");
        task.setEnabled(false);
        task.setStatus(ScheduledTaskStatus.FAILED);
        when(repository.findById("task-1")).thenReturn(Optional.of(task));

        service.resume("task-1");

        assertThat(task.enabled()).isTrue();
        assertThat(task.status()).isEqualTo(ScheduledTaskStatus.SCHEDULED);
        verify(scheduler).register(task);
    }

    @Test
    void deleteUnregistersAndRemovesFromRepository() {
        service.delete("task-1");

        verify(scheduler, times(1)).unregister("task-1");
        verify(repository).deleteById("task-1");
    }

    @Test
    void pauseUnknownTaskThrows() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pause("missing")).isInstanceOf(IllegalArgumentException.class);
    }
}
