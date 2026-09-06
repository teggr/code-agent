package com.teggr.codeagent.runner;

import java.time.Instant;
import java.util.Objects;

public record ChatMessage(String id, String role, String content, Instant createdAt) {
    public ChatMessage {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(role, "role must not be null");
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}
