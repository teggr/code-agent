package io.cloudagent.gateway.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import io.cloudagent.gateway.agent.AgentDefinition;
import io.cloudagent.gateway.agent.AgentRegistry;
import io.cloudagent.gateway.copilot.CopilotClientFactory;
import io.cloudagent.gateway.copilot.CopilotConnectionProperties;
import io.cloudagent.gateway.docker.ContainerInfo;
import io.cloudagent.gateway.docker.ContainerManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the full lifecycle of a gateway session: creating/starting the agent container,
 * attaching the Copilot SDK to its headless CLI, forwarding prompts, and streaming events out to
 * WebSocket subscribers via {@link AgentEventHub}. Session metadata is persisted through
 * {@link SessionRepository} so persistent sessions can be reconnected to after a gateway restart.
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final AgentRegistry agentRegistry;
    private final ContainerManager containerManager;
    private final CopilotClientFactory copilotClientFactory;
    private final SessionRepository sessionRepository;
    private final AgentEventHub eventHub;
    private final ObjectMapper objectMapper;
    private final CopilotConnectionProperties copilotProperties;
    private final Map<String, GatewaySession> activeSessions = new ConcurrentHashMap<>();

    public SessionManager(AgentRegistry agentRegistry, ContainerManager containerManager,
                          CopilotClientFactory copilotClientFactory, SessionRepository sessionRepository,
                          AgentEventHub eventHub, ObjectMapper objectMapper,
                          CopilotConnectionProperties copilotProperties) {
        this.agentRegistry = agentRegistry;
        this.containerManager = containerManager;
        this.copilotClientFactory = copilotClientFactory;
        this.copilotProperties = copilotProperties;
        this.sessionRepository = sessionRepository;
        this.eventHub = eventHub;
        this.objectMapper = objectMapper;
    }

    /** Creates a brand-new session: a fresh container plus a fresh Copilot conversation. */
    public SessionRecord createSession(String agentType, Boolean persistentOverride) {
        AgentDefinition agent = agentRegistry.require(agentType);
        boolean persistent = persistentOverride != null ? persistentOverride : agent.persistent();
        String sessionId = UUID.randomUUID().toString();

        String containerName = containerManager.createContainer(sessionId, agent, persistent);
        containerManager.startContainer(containerName);

        CopilotClient client = copilotClientFactory.connect(containerManager.hostname(containerName), agent.copilotPort());
        try {
            SessionConfig config = new SessionConfig()
                    .setSessionId(sessionId)
                    .setModel(copilotProperties.model())
                    .setWorkingDirectory("/workspace")
                    .setStreaming(true)
                    .setOnPermissionRequest(PermissionHandler.APPROVE_ALL);
            CopilotSession copilotSession = client.createSession(config).get();
            return register(sessionId, agentType, containerName, persistent, client, copilotSession);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.close();
            throw new SessionException("Interrupted while creating Copilot session " + sessionId, e);
        } catch (ExecutionException e) {
            client.close();
            throw new SessionException("Failed to create Copilot session " + sessionId, e.getCause());
        }
    }

    /**
     * Reattaches to an existing persistent session: starts its container if stopped and resumes
     * the Copilot conversation, without losing prior context.
     */
    public SessionRecord reconnect(String sessionId) {
        SessionRecord record = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Unknown session: " + sessionId));
        if (activeSessions.containsKey(sessionId)) {
            return record;
        }
        AgentDefinition agent = agentRegistry.require(record.agentType());

        Optional<ContainerInfo> container = containerManager.findBySession(sessionId);
        if (container.isEmpty()) {
            throw new IllegalStateException("No container found for session " + sessionId);
        }
        if (!container.get().running()) {
            containerManager.startContainer(record.containerName());
        }

        CopilotClient client = copilotClientFactory.connect(containerManager.hostname(record.containerName()), agent.copilotPort());
        try {
            CopilotSession copilotSession = client.resumeSession(sessionId, new com.github.copilot.rpc.ResumeSessionConfig()
                    .setModel(copilotProperties.model())).get();
            return register(sessionId, record.agentType(), record.containerName(), record.persistent(), client, copilotSession);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.close();
            throw new SessionException("Interrupted while resuming Copilot session " + sessionId, e);
        } catch (ExecutionException e) {
            client.close();
            throw new SessionException("Failed to resume Copilot session " + sessionId, e.getCause());
        }
    }

    private SessionRecord register(String sessionId, String agentType, String containerName, boolean persistent,
                                    CopilotClient client, CopilotSession copilotSession) {
        GatewaySession gatewaySession = new GatewaySession(sessionId, agentType, containerName, persistent, client, copilotSession);
        gatewaySession.setEventSubscription(copilotSession.on(event -> broadcast(sessionId, event)));
        activeSessions.put(sessionId, gatewaySession);

        Instant now = Instant.now();
        SessionRecord record = new SessionRecord(sessionId, agentType, containerName, persistent,
                SessionStatus.RUNNING, now, now);
        sessionRepository.save(record);
        return record;
    }

    private void broadcast(String sessionId, com.github.copilot.generated.SessionEvent event) {
        try {
            eventHub.publish(sessionId, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn("Failed to serialize/broadcast event for session {}", sessionId, e);
        }
    }

    /** Sends a prompt and waits for the assistant's final response (blocking, prototype-simple). */
    public String sendPrompt(String sessionId, String prompt) {
        GatewaySession session = activeSessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("Session " + sessionId + " is not active; reconnect first");
        }
        try {
            AssistantMessageEvent response = session.copilotSession()
                    .sendAndWait(new MessageOptions().setPrompt(prompt))
                    .get();
            return response.getData() != null ? response.getData().content() : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SessionException("Interrupted while sending prompt to session " + sessionId, e);
        } catch (ExecutionException e) {
            throw new SessionException("Failed to send prompt to session " + sessionId, e.getCause());
        }
    }

    public List<SessionRecord> listSessions() {
        return sessionRepository.findAll();
    }

    public Optional<SessionRecord> getSession(String sessionId) {
        return sessionRepository.findById(sessionId);
    }

    /** Stops a session: disconnects the SDK, stops the container, and for ephemeral sessions removes it. */
    public void stopSession(String sessionId) {
        SessionRecord record = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Unknown session: " + sessionId));

        GatewaySession active = activeSessions.remove(sessionId);
        if (active != null) {
            active.closeConnection();
        }
        containerManager.stopContainer(record.containerName());

        if (record.persistent()) {
            sessionRepository.save(new SessionRecord(record.sessionId(), record.agentType(), record.containerName(),
                    true, SessionStatus.STOPPED, record.createdAt(), Instant.now()));
        } else {
            containerManager.removeContainer(record.containerName());
            sessionRepository.delete(sessionId);
        }
    }
}
