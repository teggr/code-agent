package com.teggr.codeagent.agent.copilot;

import java.util.function.Consumer;

import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.MessageOptions;
import com.teggr.codeagent.agent.AgentSession;

class CopilotAgentSession implements AgentSession {

    private final CopilotSession session;

    CopilotAgentSession(CopilotSession session) {
        this.session = session;
    }

    @Override
    public void sendPrompt(String prompt) throws Exception {
        session.send(new MessageOptions().setPrompt(prompt)).get();
    }

    @Override
    public void onMessage(Consumer<String> listener) {
        session.on(AssistantMessageEvent.class, event -> listener.accept(event.getData().content()));
    }

    @Override
    public void onIdle(Runnable listener) {
        session.on(SessionIdleEvent.class, event -> listener.run());
    }

    @Override
    public void onError(Consumer<String> listener) {
        session.on(SessionErrorEvent.class, event -> listener.accept(event.getData().message()));
    }

}
