package com.teggr.codeagent.docker;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.model.Container;

class DockerRunnerServiceTest {

    private final DockerClient dockerClient = mock(DockerClient.class);
    private final DockerRunnerService service = new DockerRunnerService(dockerClient, new DockerRunnerProperties());

    @Test
    void pruneOrphansStopsAndRemovesEveryLabeledContainer() {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class, org.mockito.Answers.RETURNS_SELF);
        Container orphan1 = mock(Container.class);
        Container orphan2 = mock(Container.class);
        when(orphan1.getId()).thenReturn("orphan-1");
        when(orphan2.getId()).thenReturn("orphan-2");
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of(orphan1, orphan2));

        StopContainerCmd stopCmd = mock(StopContainerCmd.class, org.mockito.Answers.RETURNS_SELF);
        RemoveContainerCmd removeCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.stopContainerCmd(any())).thenReturn(stopCmd);
        when(dockerClient.removeContainerCmd(any())).thenReturn(removeCmd);

        service.pruneOrphans();

        verify(listContainersCmd).withLabelFilter(Map.of(DockerRunnerService.MANAGED_LABEL, "true"));
        verify(listContainersCmd).withShowAll(true);
        verify(dockerClient).stopContainerCmd(eq("orphan-1"));
        verify(dockerClient).stopContainerCmd(eq("orphan-2"));
        verify(dockerClient).removeContainerCmd(eq("orphan-1"));
        verify(dockerClient).removeContainerCmd(eq("orphan-2"));
    }

    @Test
    void pruneOrphansDoesNothingWhenNoneFound() {
        ListContainersCmd listContainersCmd = mock(ListContainersCmd.class, org.mockito.Answers.RETURNS_SELF);
        when(dockerClient.listContainersCmd()).thenReturn(listContainersCmd);
        when(listContainersCmd.exec()).thenReturn(List.of());

        service.pruneOrphans();

        verify(dockerClient, never()).stopContainerCmd(any());
    }

}
