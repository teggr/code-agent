package io.cloudagent.gateway.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code cloud-agent.docker} section of {@code application.yml}.
 *
 * @param network        name of the Docker network the gateway and agent containers share
 * @param workspaceRoot  host directory under which per-session workspace directories are created
 *                       and bind-mounted into containers as {@code /workspace}
 * @param containerPrefix prefix applied to generated container names
 * @param commandTimeoutSeconds max time to wait for a single {@code docker} CLI invocation
 * @param hostPortRangeStart first host port (on 127.0.0.1) that may be used to publish a
 *                       container's Copilot port
 * @param hostPortRangeEnd  last host port (inclusive) usable for publishing Copilot ports
 */
@ConfigurationProperties(prefix = "cloud-agent.docker")
public record DockerProperties(
        String network,
        String workspaceRoot,
        String containerPrefix,
        long commandTimeoutSeconds,
        int hostPortRangeStart,
        int hostPortRangeEnd) {

    public DockerProperties {
        network = network == null || network.isBlank() ? "cloud-agent-net" : network;
        workspaceRoot = workspaceRoot == null || workspaceRoot.isBlank() ? "/var/lib/cloud-agent/workspaces" : workspaceRoot;
        containerPrefix = containerPrefix == null || containerPrefix.isBlank() ? "cloud-agent" : containerPrefix;
        commandTimeoutSeconds = commandTimeoutSeconds <= 0 ? 30 : commandTimeoutSeconds;
        hostPortRangeStart = hostPortRangeStart <= 0 ? 45000 : hostPortRangeStart;
        hostPortRangeEnd = hostPortRangeEnd <= 0 ? 45999 : hostPortRangeEnd;
        if (hostPortRangeEnd < hostPortRangeStart) {
            throw new IllegalArgumentException("hostPortRangeEnd must be >= hostPortRangeStart");
        }
    }
}
