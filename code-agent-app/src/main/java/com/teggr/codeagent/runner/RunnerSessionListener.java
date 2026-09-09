package com.teggr.codeagent.runner;

import com.teggr.codeagent.agent.Question;

/** Receives lifecycle events from a {@link RunnerSession}. */
public interface RunnerSessionListener {

    void onMessage(RunnerSession session, ChatMessage message);

    void onStatusChange(RunnerSession session, RunnerStatus status);

    /** Fired when the agent asks a question, and again with {@code null} once it has been answered. */
    void onQuestionChange(RunnerSession session, Question question);

}
