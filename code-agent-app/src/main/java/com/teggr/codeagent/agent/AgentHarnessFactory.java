package com.teggr.codeagent.agent;

/** Connects to a running code agent server, retrying until it becomes available. */
public interface AgentHarnessFactory {

    /**
     * @param stillStartable checked between attempts; throws to abort the retry loop early when the
     *                       server can no longer come up, e.g. its container has already exited
     */
    AgentHarness connect(int hostPort, String workspacePath, Runnable stillStartable) throws Exception;

}
