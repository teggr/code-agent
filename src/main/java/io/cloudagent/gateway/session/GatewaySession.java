package io.cloudagent.gateway.session;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import java.io.Closeable;

/**
 * In-memory runtime handle for an active gateway session: the container it runs in plus the live
 * SDK connection to its headless Copilot CLI. This is deliberately not persisted; on gateway
 * restart, {@link SessionRecord}s are used to locate and reconnect to still-running containers.
 */
public final class GatewaySession {

    private final String sessionId;
    private final String agentType;
    private final String containerName;
    private final boolean persistent;
    private final CopilotClient client;
    private final CopilotSession copilotSession;
    private volatile Closeable eventSubscription;

    public GatewaySession(String sessionId, String agentType, String containerName, boolean persistent,
                          CopilotClient client, CopilotSession copilotSession) {
        this.sessionId = sessionId;
        this.agentType = agentType;
        this.containerName = containerName;
        this.persistent = persistent;
        this.client = client;
        this.copilotSession = copilotSession;
    }

    public String sessionId() {
        return sessionId;
    }

    public String agentType() {
        return agentType;
    }

    public String containerName() {
        return containerName;
    }

    public boolean persistent() {
        return persistent;
    }

    public CopilotClient client() {
        return client;
    }

    public CopilotSession copilotSession() {
        return copilotSession;
    }

    public void setEventSubscription(Closeable subscription) {
        this.eventSubscription = subscription;
    }

    /** Closes the SDK connection (not the container) for this session. */
    public void closeConnection() {
        try {
            if (eventSubscription != null) {
                eventSubscription.close();
            }
        } catch (Exception ignored) {
            // best-effort cleanup
        }
        copilotSession.close();
        client.close();
    }
}
