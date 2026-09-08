package com.teggr.codeagent.agent;

import java.util.function.Consumer;

/**
 * A single conversation with a code agent.
 *
 * <p>Register {@link #onMessage} and {@link #onIdle} listeners before calling
 * {@link #sendPrompt}, since a prompt's events may otherwise be missed.
 */
public interface AgentSession {

    void sendPrompt(String prompt) throws Exception;

    void onMessage(Consumer<String> listener);

    void onIdle(Runnable listener);

    void onError(Consumer<String> listener);

}
