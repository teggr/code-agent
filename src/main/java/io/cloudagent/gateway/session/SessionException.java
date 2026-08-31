package io.cloudagent.gateway.session;

/** Raised for failures while creating, resuming, or interacting with a Copilot session. */
public class SessionException extends RuntimeException {

    public SessionException(String message, Throwable cause) {
        super(message, cause);
    }
}
