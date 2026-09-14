package com.teggr.codeagent.agent.runtime;

/** Runtime identity and connection details for a provisioned agent. */
public record AgentRuntimeInstance(
        String runtimeId,
        int harnessPort,
        String workingDirectory,
        WorkspaceAccess workspaceAccess) {
}