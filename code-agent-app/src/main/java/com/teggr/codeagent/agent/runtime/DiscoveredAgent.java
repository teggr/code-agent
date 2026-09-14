package com.teggr.codeagent.agent.runtime;

import com.teggr.codeagent.agent.WorkspaceSpec;

/** An agent runtime rediscovered after the application restarts. */
public record DiscoveredAgent(
        String agentId,
        WorkspaceSpec workspace,
        AgentRuntimeInstance instance,
        boolean running) {
}