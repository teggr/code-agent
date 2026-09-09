package com.teggr.codeagent.agent;

/** A catch-all, SDK-agnostic notification for agent lifecycle/telemetry events not otherwise modeled. */
public record AgentEvent(String type, String summary) {
}
