package io.cloudagent.gateway.docker;

/**
 * Snapshot of a Docker container's state as reported by {@code docker inspect}.
 *
 * @param containerId  full container ID
 * @param name         container name
 * @param running      whether the container is currently running
 * @param status       raw Docker status string (e.g. {@code running}, {@code exited})
 * @param hostPort     the host-mapped Copilot port, if published (usually {@code null} since the
 *                     gateway prefers container-name addressing over publishing ports)
 */
public record ContainerInfo(String containerId, String name, boolean running, String status, Integer hostPort) {
}
