package io.cloudagent.gateway.docker;

/**
 * Result of creating an agent container.
 *
 * @param containerName the created container's name (also its DNS name on the Docker network)
 * @param hostPort      the 127.0.0.1 host port the container's Copilot port is published on; this
 *                      is the address the gateway JVM must connect to
 */
public record CreatedContainer(String containerName, int hostPort) {
}
