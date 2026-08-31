package io.cloudagent.gateway.web;

import io.cloudagent.gateway.session.AgentEventHub;
import java.io.IOException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Streams Copilot agent events for one session to a connected WebSocket client. Clients connect
 * to {@code /ws/sessions/{sessionId}} (see {@link WebSocketConfig}) and receive one JSON-encoded
 * agent event per message.
 */
@Component
public class AgentEventWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentEventWebSocketHandler.class);
    private static final String SESSION_ID_ATTR = "cloud-agent-session-id";
    private static final String SUBSCRIBER_ATTR = "cloud-agent-subscriber";

    private final AgentEventHub eventHub;

    public AgentEventWebSocketHandler(AgentEventHub eventHub) {
        this.eventHub = eventHub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = extractSessionId(session);
        Consumer<String> subscriber = event -> send(session, event);
        session.getAttributes().put(SESSION_ID_ATTR, sessionId);
        session.getAttributes().put(SUBSCRIBER_ATTR, subscriber);
        eventHub.subscribe(sessionId, subscriber);
        log.info("WebSocket client subscribed to session {}", sessionId);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String sessionId = (String) session.getAttributes().get(SESSION_ID_ATTR);
        @SuppressWarnings("unchecked")
        Consumer<String> subscriber = (Consumer<String>) session.getAttributes().get(SUBSCRIBER_ATTR);
        if (sessionId != null && subscriber != null) {
            eventHub.unsubscribe(sessionId, subscriber);
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException e) {
            log.warn("Failed to send agent event to WebSocket client", e);
        }
    }

    private String extractSessionId(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        String[] parts = path.split("/");
        return parts[parts.length - 1];
    }
}
