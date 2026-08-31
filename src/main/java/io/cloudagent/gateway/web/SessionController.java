package io.cloudagent.gateway.web;

import io.cloudagent.gateway.session.SessionManager;
import io.cloudagent.gateway.session.SessionRecord;
import io.cloudagent.gateway.web.dto.CreateSessionRequest;
import io.cloudagent.gateway.web.dto.PromptRequest;
import io.cloudagent.gateway.web.dto.PromptResponse;
import io.cloudagent.gateway.web.dto.SessionResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal REST API for the Cloud Agent Gateway prototype. See {@code scripts/test-session.sh} for
 * end-to-end curl examples.
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionManager sessionManager;

    public SessionController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse createSession(@RequestBody CreateSessionRequest request) {
        SessionRecord record = sessionManager.createSession(request.agentType(), request.persistent());
        return SessionResponse.from(record);
    }

    @GetMapping
    public List<SessionResponse> listSessions() {
        return sessionManager.listSessions().stream().map(SessionResponse::from).toList();
    }

    @GetMapping("/{sessionId}")
    public SessionResponse getSession(@PathVariable String sessionId) {
        return sessionManager.getSession(sessionId)
                .map(SessionResponse::from)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown session: " + sessionId));
    }

    @PostMapping("/{sessionId}/reconnect")
    public SessionResponse reconnect(@PathVariable String sessionId) {
        return SessionResponse.from(sessionManager.reconnect(sessionId));
    }

    @PostMapping("/{sessionId}/prompt")
    public PromptResponse sendPrompt(@PathVariable String sessionId, @RequestBody PromptRequest request) {
        return new PromptResponse(sessionManager.sendPrompt(sessionId, request.prompt()));
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stopSession(@PathVariable String sessionId) {
        sessionManager.stopSession(sessionId);
    }
}
