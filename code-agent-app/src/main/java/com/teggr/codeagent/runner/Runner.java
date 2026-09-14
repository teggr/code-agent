package com.teggr.codeagent.runner;

import com.teggr.codeagent.harness.AgentHarness;
import com.teggr.codeagent.agent.runtime.AgentRuntimeInstance;

/** A single active runner: a repo-scoped container paired with its connected agent harness. */
public record Runner(String id, String repoUrl, AgentRuntimeInstance runtimeInstance, AgentHarness harness) {
}
