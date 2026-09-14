package com.teggr.codeagent.agent;

import com.teggr.codeagent.harness.AgentHarness;

/** A transient connection to the provider-neutral harness serving an Agent. */
public final class AgentConnection implements AutoCloseable {

    private final AgentHarness harness;

    public AgentConnection(AgentHarness harness) {
        this.harness = harness;
    }

    public AgentHarness harness() {
        return harness;
    }

    @Override
    public void close() throws Exception {
        harness.close();
    }
}