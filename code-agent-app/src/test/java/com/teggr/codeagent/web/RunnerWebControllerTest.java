package com.teggr.codeagent.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RunnerWebController.class)
class RunnerWebControllerTest {

    private static final String UNKNOWN = "does-not-exist";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private com.teggr.codeagent.runner.RunnerManager runnerManager;

    @Test
    void detailPageOfAnUnknownRunnerIsNotFound() throws Exception {
        when(runnerManager.getSession(UNKNOWN)).thenReturn(null);

        mockMvc.perform(get("/runners/{id}", UNKNOWN)).andExpect(status().isNotFound());
    }

    @Test
    void eventStreamOfAnUnknownRunnerIsNotFound() throws Exception {
        when(runnerManager.getSession(UNKNOWN)).thenReturn(null);

        mockMvc.perform(get("/runners/{id}/events", UNKNOWN)).andExpect(status().isNotFound());

        verify(runnerManager, never()).subscribe(anyString());
    }

    @Test
    void stoppingAnUnknownRunnerIsNotFoundAndNeverReachesTheManager() throws Exception {
        when(runnerManager.getSession(UNKNOWN)).thenReturn(null);

        mockMvc.perform(post("/runners/{id}/stop", UNKNOWN)).andExpect(status().isNotFound());

        verify(runnerManager, never()).stop(anyString());
    }

    @Test
    void removingAnUnknownRunnerIsNotFoundAndNeverReachesTheManager() throws Exception {
        when(runnerManager.getSession(UNKNOWN)).thenReturn(null);

        mockMvc.perform(post("/runners/{id}/remove", UNKNOWN)).andExpect(status().isNotFound());

        verify(runnerManager, never()).remove(anyString());
    }

    @Test
    void stoppingAKnownRunnerStillReachesTheManager() throws Exception {
        when(runnerManager.getSession("runner-1")).thenReturn(
                new com.teggr.codeagent.runner.RunnerSession(new com.teggr.codeagent.runner.Runner(
                        "runner-1", "repo", new com.teggr.codeagent.docker.ContainerLaunch("container-1", 1111), null)));

        mockMvc.perform(post("/runners/{id}/stop", "runner-1")).andExpect(status().isSeeOther());

        verify(runnerManager).stop("runner-1");
    }

}
