package com.teggr.codeagent.agent;

/** Connects to a running code agent server, retrying until it becomes available. */
public interface AgentHarnessFactory {

    AgentHarness connect(int hostPort) throws Exception;

}
