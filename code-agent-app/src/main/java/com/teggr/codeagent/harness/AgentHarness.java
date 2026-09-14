package com.teggr.codeagent.harness;

/** A connected code agent runtime capable of creating conversation sessions. */
import java.util.List;

public interface AgentHarness extends AutoCloseable {
    HarnessSession createSession(String sessionId) throws Exception;

    HarnessSession resumeSession(String sessionId) throws Exception;

    List<String> listSessionIds() throws Exception;

    @Override
    void close() throws Exception;

}
