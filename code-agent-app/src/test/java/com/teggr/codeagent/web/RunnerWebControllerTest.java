package com.teggr.codeagent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.ModelAndView;

import com.teggr.codeagent.runner.RunnerManager;

class RunnerWebControllerTest {

    @Test
    void repositoryResultsReturnsPickerFragmentWithQueryResults() {
        RunnerManager runnerManager = mock(RunnerManager.class);
        GitHubRepositoryService repositoryService = mock(GitHubRepositoryService.class);
        when(repositoryService.findRepositories("toolkit", 1)).thenReturn(new GitHubRepositoryService.RepositoryPage(
                List.of(new GitHubRepositoryService.Repository("teggr/j2html-toolkit",
                        "https://github.com/teggr/j2html-toolkit", "private", true, Instant.parse("2026-09-10T10:00:00Z"))),
                false, false));
        RunnerWebController controller = new RunnerWebController(runnerManager, repositoryService);

        ModelAndView view = controller.repositoryResults("toolkit", 1);

        assertThat(view.getViewName()).isEqualTo("fragments :: repositoryResults");
        assertThat(view.getModel()).containsEntry("query", "toolkit").containsEntry("hasMore", false)
                .containsEntry("unavailable", false).containsKey("repositories");
    }

    @Test
    void repositorySelectionRendersSelectedPickerState() {
        RunnerWebController controller = new RunnerWebController(mock(RunnerManager.class), mock(GitHubRepositoryService.class));

        ModelAndView view = controller.repositorySelection("https://github.com/teggr/j2html-toolkit", "teggr/j2html-toolkit");

        assertThat(view.getViewName()).isEqualTo("fragments :: repositoryPicker");
        assertThat(view.getModel()).containsEntry("selectedRepositoryUrl", "https://github.com/teggr/j2html-toolkit")
                .containsEntry("selectedRepositoryName", "teggr/j2html-toolkit");
    }
}