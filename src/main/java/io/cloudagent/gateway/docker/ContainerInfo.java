package io.cloudagent.gateway.docker;

/**
 * Snapshot of a Docker container's state as reported by {@code docker inspect}.
 *
 * @param containerId  full container ID
 * @param name         container name
 * @param running      whether the container is currently running
 * @param status       raw Docker status string (e.g. {@code running}, {@code exited})
 * @param hostPort     the 127.0.0.1 host port the container's Copilot port is published on, or
 *                     {@code null} if the container has no loopback port binding
 */
public record ContainerInfo(String containerId, String name, boolean running, String status, Integer hostPort) {
}
