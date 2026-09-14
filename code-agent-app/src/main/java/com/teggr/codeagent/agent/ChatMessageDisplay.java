package com.teggr.codeagent.agent;

import java.util.Locale;

public enum ChatMessageDisplay {
    FULL_MESSAGE,
    COMPACT_ACTIVITY,
    TURN_SUMMARY,
    QUESTION;

    public static ChatMessageDisplay fromRole(String role) {
        return fromRoleAndEventType(role, null);
    }

    public static ChatMessageDisplay fromRoleAndEventType(String role, String eventType) {
        return switch (role) {
            case "user", "assistant" -> FULL_MESSAGE;
            case "tool" -> COMPACT_ACTIVITY;
            case "question" -> QUESTION;
            case "system" -> isUsageEvent(eventType) ? TURN_SUMMARY : COMPACT_ACTIVITY;
            default -> COMPACT_ACTIVITY;
        };
    }

    private static boolean isUsageEvent(String eventType) {
        return eventType != null && eventType.toLowerCase(Locale.ROOT).contains("usage");
    }
}