package com.teggr.codeagent.harness;

/** Reports a tool invocation starting or finishing, for surfacing agent activity in the UI. */
public record ToolActivity(String toolCallId, String toolName, Phase phase, String summary) {

    public enum Phase {
        STARTED,
        COMPLETED
    }
}
