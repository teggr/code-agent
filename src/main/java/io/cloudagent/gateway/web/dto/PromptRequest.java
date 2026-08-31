package io.cloudagent.gateway.web.dto;

/** Request body for {@code POST /api/sessions/{sessionId}/prompt}. */
public record PromptRequest(String prompt) {
}
