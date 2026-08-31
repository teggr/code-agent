package io.cloudagent.gateway.docker;

/**
 * Well-known labels applied to every container created by the gateway, used to identify and
 * filter "our" containers with plain {@code docker ps --filter} / {@code docker inspect} calls.
 */
public final class ContainerLabels {

    public static final String MANAGED_BY = "cloud-agent";
    public static final String SESSION = "cloud-agent-session";
    public static final String AGENT_TYPE = "cloud-agent-type";
    public static final String LIFECYCLE = "cloud-agent-lifecycle";

    private ContainerLabels() {
    }
}
