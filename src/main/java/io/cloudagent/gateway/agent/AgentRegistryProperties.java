package io.cloudagent.gateway.agent;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code cloud-agent.agents} section of {@code application.yml}, e.g.:
 *
 * <pre>{@code
 * cloud-agent:
 *   agents:
 *     default:
 *       image: cloud-agent:latest
 *       copilot-port: 4321
 *       persistent: false
 * }</pre>
 */
@ConfigurationProperties(prefix = "cloud-agent")
public record AgentRegistryProperties(Map<String, AgentDefinition> agents) {

    public AgentRegistryProperties {
        agents = agents == null ? Map.of() : Map.copyOf(agents);
    }
}
