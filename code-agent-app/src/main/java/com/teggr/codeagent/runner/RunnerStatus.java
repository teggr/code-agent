package com.teggr.codeagent.runner;

public enum RunnerStatus {
    STARTING,
    /** The container is being started and/or an agent session re-established. */
    RECONNECTING,
    IDLE,
    BUSY,
    FAILED,
    /** The container still exists and can be started again. */
    STOPPED,
    REMOVED
}
