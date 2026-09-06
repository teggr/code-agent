package com.teggr.codeagent.runner;

import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.docker.ContainerLaunch;

/** A single active runner: a repo-scoped container paired with its connected agent harness. */
public record Runner(String id, String repoUrl, ContainerLaunch containerLaunch, AgentHarness harness) {
}
