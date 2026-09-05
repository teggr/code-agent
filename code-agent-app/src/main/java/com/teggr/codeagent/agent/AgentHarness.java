package com.teggr.codeagent.agent;

/** A connected code agent runtime capable of creating conversation sessions. */
public interface AgentHarness extends AutoCloseable {

    AgentSession createSession() throws Exception;

    @Override
    void close() throws Exception;

}
