package com.teggr.codeagent.docker;

/**
 * A runner container discovered on the Docker host, rebuilt from the labels written at launch so a
 * restarted application can adopt it. {@code launch} carries the container's current host port,
 * which is 0 when the container is not running.
 */
public record ManagedContainer(String runnerId, String repoUrl, ContainerLaunch launch, boolean running) {
}
