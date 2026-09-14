package com.teggr.codeagent.agent.runtime;

import java.util.List;

/** Provisions and controls the infrastructure hosting agents. */
public interface AgentRuntime {

    AgentRuntimeInstance provision(AgentRuntimeRequest request) throws Exception;

    List<DiscoveredAgent> discover();

    AgentRuntimeInstance start(AgentRuntimeInstance instance);

    void stop(String runtimeId);

    void delete(String runtimeId);

    void verifyRunning(String runtimeId);
}