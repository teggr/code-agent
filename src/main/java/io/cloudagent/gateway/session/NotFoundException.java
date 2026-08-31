package io.cloudagent.gateway.session;

/** Raised when a referenced session or agent type does not exist. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
