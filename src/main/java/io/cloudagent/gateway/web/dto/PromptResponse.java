package io.cloudagent.gateway.web.dto;

/** Response body containing the assistant's final message for a prompt. */
public record PromptResponse(String content) {
}
