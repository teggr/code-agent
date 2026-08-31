package io.cloudagent.gateway.session;

/** Lifecycle status of a gateway session. */
public enum SessionStatus {
    /** Container is being created/started and the Copilot CLI is not yet reachable. */
    STARTING,
    /** Gateway is attached to the Copilot CLI and the session can accept prompts. */
    RUNNING,
    /** Container has been stopped but (for persistent sessions) can still be reconnected to. */
    STOPPED,
    /** Container and session metadata have been removed. */
    REMOVED,
    /** Something went wrong creating or attaching to the session. */
    ERROR
}
