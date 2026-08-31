package io.cloudagent.gateway.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import io.cloudagent.gateway.agent.AgentDefinition;
import io.cloudagent.gateway.agent.AgentRegistry;
import io.cloudagent.gateway.copilot.CopilotClientFactory;
import io.cloudagent.gateway.copilot.CopilotConnectionProperties;
import io.cloudagent.gateway.docker.ContainerInfo;
import io.cloudagent.gateway.docker.ContainerManager;
import io.cloudagent.gateway.docker.CreatedContainer;
import io.cloudagent.gateway.docker.DockerProperties;
import io.cloudagent.gateway.docker.HostPortAllocator;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionManagerTest {

    private static final AgentDefinition AGENT =
            new AgentDefinition("cloud-agent:latest", 4321, null, Map.of(), true);

    private AgentRegistry agentRegistry;
    private ContainerManager containerManager;
    private CopilotClientFactory copilotClientFactory;
    private SessionRepository sessionRepository;
    private HostPortAllocator portAllocator;
    private CopilotClient client;
    private CopilotSession copilotSession;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() throws Exception {
        agentRegistry = mock(AgentRegistry.class);
        containerManager = mock(ContainerManager.class);
        copilotClientFactory = mock(CopilotClientFactory.class);
        sessionRepository = mock(SessionRepository.class);
        portAllocator = new HostPortAllocator(
                new DockerProperties("cloud-agent-net", "./data/workspaces", "cloud-agent", 30, 45700, 45710));
        client = mock(CopilotClient.class);
        copilotSession = mock(CopilotSession.class);

        when(agentRegistry.require(anyString())).thenReturn(AGENT);
        when(copilotClientFactory.connect(anyString(), anyInt())).thenReturn(client);
        when(client.createSession(any())).thenReturn(CompletableFuture.completedFuture(copilotSession));
        when(client.resumeSession(anyString(), any())).thenReturn(CompletableFuture.completedFuture(copilotSession));
        when(copilotSession.on(any())).thenReturn(() -> { });

        sessionManager = new SessionManager(agentRegistry, containerManager, copilotClientFactory, sessionRepository,
                new AgentEventHub(), new ObjectMapper(),
                new CopilotConnectionProperties(30, 500, "gpt-5.4"), portAllocator);
    }

    @Test
    void createSessionConnectsToTheLoopbackHostPortNotTheDockerHostname() {
        when(containerManager.createContainer(anyString(), eq(AGENT), eq(true)))
                .thenReturn(new CreatedContainer("cloud-agent-abc", 45703));

        SessionRecord record = sessionManager.createSession("default", true);

        verify(copilotClientFactory).connect("127.0.0.1", 45703);
        verify(containerManager, never()).hostname(anyString());
        assertThat(record.containerName()).isEqualTo("cloud-agent-abc");
        assertThat(record.hostPort()).isEqualTo(45703);
    }

    @Test
    void createSessionPersistsTheHostPort() {
        when(containerManager.createContainer(anyString(), eq(AGENT), eq(true)))
                .thenReturn(new CreatedContainer("cloud-agent-abc", 45704));

        sessionManager.createSession("default", true);

        verify(sessionRepository).save(org.mockito.ArgumentMatchers.argThat(r -> r.hostPort() == 45704));
    }

    @Test
    void reconnectReusesThePersistedHostPortAndDoesNotCreateANewContainer() {
        SessionRecord stored = storedRecord(45705);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(stored));
        when(containerManager.findBySession("session-1"))
                .thenReturn(Optional.of(new ContainerInfo("id", "cloud-agent-session-1", true, "running", 45705)));

        SessionRecord record = sessionManager.reconnect("session-1");

        verify(copilotClientFactory).connect("127.0.0.1", 45705);
        verify(containerManager, never()).createContainer(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        assertThat(record.hostPort()).isEqualTo(45705);
        assertThat(portAllocator.isReserved(45705)).isTrue();
    }

    @Test
    void reconnectFallsBackToDockersReportedMappingForLegacyRecords() {
        SessionRecord legacy = storedRecord(0);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(legacy));
        when(containerManager.findBySession("session-1"))
                .thenReturn(Optional.of(new ContainerInfo("id", "cloud-agent-session-1", true, "running", null)));
        when(containerManager.findHostPort("cloud-agent-session-1", 4321)).thenReturn(Optional.of(45706));

        SessionRecord record = sessionManager.reconnect("session-1");

        verify(copilotClientFactory).connect("127.0.0.1", 45706);
        assertThat(record.hostPort()).isEqualTo(45706);
    }

    @Test
    void stoppingAnEphemeralSessionReleasesItsHostPort() {
        portAllocator.reserve(45707);
        SessionRecord stored = new SessionRecord("session-2", "default", "cloud-agent-session-2", 45707, false,
                SessionStatus.RUNNING, Instant.now(), Instant.now());
        when(sessionRepository.findById("session-2")).thenReturn(Optional.of(stored));

        sessionManager.stopSession("session-2");

        verify(containerManager).removeContainer("cloud-agent-session-2");
        assertThat(portAllocator.isReserved(45707)).isFalse();
    }

    @Test
    void stoppingAPersistentSessionKeepsItsHostPortReserved() {
        portAllocator.reserve(45708);
        SessionRecord stored = storedRecord(45708);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(stored));

        sessionManager.stopSession("session-1");

        verify(containerManager, never()).removeContainer(anyString());
        verify(sessionRepository).save(org.mockito.ArgumentMatchers.argThat(
                r -> r.hostPort() == 45708 && r.status() == SessionStatus.STOPPED));
        assertThat(portAllocator.isReserved(45708)).isTrue();
    }

    private static SessionRecord storedRecord(int hostPort) {
        return new SessionRecord("session-1", "default", "cloud-agent-session-1", hostPort, true,
                SessionStatus.STOPPED, Instant.now(), Instant.now());
    }
}
