package io.cloudagent.gateway.docker;

/**
 * Raised when a Docker CLI invocation performed by {@link ContainerManager} fails or times out.
 */
public class DockerException extends RuntimeException {

    public DockerException(String message) {
        super(message);
    }

    public DockerException(String message, Throwable cause) {
        super(message, cause);
    }
}
