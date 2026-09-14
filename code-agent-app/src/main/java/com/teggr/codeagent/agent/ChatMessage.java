package com.teggr.codeagent.agent;

import java.time.Instant;

public record ChatMessage(String id, String role, String content, Instant timestamp) {
}