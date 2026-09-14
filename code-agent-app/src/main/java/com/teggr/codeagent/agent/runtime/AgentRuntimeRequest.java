package com.teggr.codeagent.agent.runtime;

import java.util.Objects;

import com.teggr.codeagent.agent.WorkspaceSpec;

public record AgentRuntimeRequest(String agentId, WorkspaceSpec workspace) {

    public AgentRuntimeRequest {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId must not be blank");
        }
        Objects.requireNonNull(workspace, "workspace must not be null");
    }
}