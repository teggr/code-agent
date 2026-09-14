package com.teggr.codeagent.agent;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A single conversation with a code agent.
 *
 * <p>Register listeners before calling {@link #sendPrompt}, since a prompt's events may
 * otherwise be missed.
 */
public interface AgentSession {

    List<AgentHistoryEntry> history() throws Exception;

    void sendPrompt(String prompt) throws Exception;

    void onMessage(Consumer<String> listener);

    void onIdle(Runnable listener);

    void onError(Consumer<String> listener);

    /** Reports tool calls starting/completing, for surfacing agent activity in the UI. */
    void onToolActivity(Consumer<ToolActivity> listener);

    /** Catch-all for lifecycle/usage/subagent/hook/skill/command events not otherwise modeled. */
    void onEvent(Consumer<AgentEvent> listener);

    /** Registers the handler used to answer agent-initiated questions (ask_user, elicitation). */
    void onQuestion(Function<Question, CompletableFuture<String>> handler);

    /** Cancels the message currently being processed by the agent. */
    void abort() throws Exception;

}
