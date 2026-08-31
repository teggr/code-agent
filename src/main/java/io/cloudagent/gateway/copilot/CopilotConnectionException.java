package io.cloudagent.gateway.copilot;

/** Raised when the gateway cannot attach to the headless Copilot CLI in an agent container. */
public class CopilotConnectionException extends RuntimeException {

    public CopilotConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
