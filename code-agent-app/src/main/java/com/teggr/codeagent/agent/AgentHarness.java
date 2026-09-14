package com.teggr.codeagent.agent;

/** A connected code agent runtime capable of creating conversation sessions. */
import java.util.List;

public interface AgentHarness extends AutoCloseable {
    AgentSession createSession(String sessionId) throws Exception;

    AgentSession resumeSession(String sessionId) throws Exception;

    List<String> listSessionIds() throws Exception;

    @Override
    void close() throws Exception;

}
