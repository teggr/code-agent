package com.teggr.codeagent.agent;

import com.teggr.codeagent.agent.runtime.AgentRuntimeInstance;

/** A durable hosted Agent with runtime and connection state independent from its conversations. */
public record Agent(
        String id,
        WorkspaceSpec workspace,
        AgentRuntimeInstance runtimeInstance,
        AgentConnection connection,
        AgentStatus status) {

    public Agent withConnection(AgentRuntimeInstance instance, AgentConnection newConnection, AgentStatus newStatus) {
        return new Agent(id, workspace, instance, newConnection, newStatus);
    }

    public Agent withStatus(AgentStatus newStatus) {
        return new Agent(id, workspace, runtimeInstance, connection, newStatus);
    }
}