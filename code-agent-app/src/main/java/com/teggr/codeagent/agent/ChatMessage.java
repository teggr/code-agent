package com.teggr.codeagent.agent;

import java.time.Instant;

public record ChatMessage(String id, String role, String content, Instant timestamp, ChatMessageDisplay display) {

    public static ChatMessage create(String id, String role, String content, Instant timestamp) {
        return new ChatMessage(id, role, content, timestamp, ChatMessageDisplay.fromRole(role));
    }

    public static ChatMessage create(String id, String role, String content, Instant timestamp, String eventType) {
        return new ChatMessage(id, role, content, timestamp, ChatMessageDisplay.fromRoleAndEventType(role, eventType));
    }

    public boolean timelineVisible() {
        return display == ChatMessageDisplay.FULL_MESSAGE || display == ChatMessageDisplay.COMPACT_ACTIVITY;
    }

    public boolean fullMessage() {
        return display == ChatMessageDisplay.FULL_MESSAGE;
    }

    public boolean compactActivity() {
        return display == ChatMessageDisplay.COMPACT_ACTIVITY;
    }

    public boolean turnSummary() {
        return display == ChatMessageDisplay.TURN_SUMMARY;
    }
}