package com.teggr.codeagent.runner;

/** Receives lifecycle events from a {@link RunnerSession}. */
public interface RunnerSessionListener {

    void onMessage(RunnerSession session, ChatMessage message);

    void onStatusChange(RunnerSession session, RunnerStatus status);

}
