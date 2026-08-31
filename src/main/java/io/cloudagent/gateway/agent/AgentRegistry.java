package io.cloudagent.gateway.agent;

import io.cloudagent.gateway.session.NotFoundException;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Read-only registry of configured agent types (see {@link AgentRegistryProperties}). Deliberately
 * simple: agent types are declared in configuration, not through a dynamic plugin mechanism.
 */
@Component
public class AgentRegistry {

    private final Map<String, AgentDefinition> agents;

    public AgentRegistry(AgentRegistryProperties properties) {
        this.agents = properties.agents();
    }

    public Optional<AgentDefinition> find(String agentType) {
        return Optional.ofNullable(agents.get(agentType));
    }

    public AgentDefinition require(String agentType) {
        return find(agentType)
                .orElseThrow(() -> new NotFoundException("Unknown agent type: " + agentType));
    }

    public Map<String, AgentDefinition> all() {
        return agents;
    }
}
