package com.teggr.codeagent.agent.copilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.agent.AgentSession;

class CopilotAgentHarness implements AgentHarness {

    private final CopilotClient client;

    CopilotAgentHarness(CopilotClient client) {
        this.client = client;
    }

    @Override
    public AgentSession createSession() throws Exception {
        var session = client.createSession(
            new SessionConfig().setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
        ).get();
        return new CopilotAgentSession(session);
    }

    @Override
    public void close() throws Exception {
        client.close();
    }

}
