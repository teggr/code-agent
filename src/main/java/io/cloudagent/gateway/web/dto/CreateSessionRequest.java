package io.cloudagent.gateway.web.dto;

/**
 * Request body for {@code POST /api/sessions}.
 *
 * @param agentType   which configured agent type (see {@code cloud-agent.agents}) to launch
 * @param persistent  optional override of the agent type's default persistence; when {@code true}
 *                    the container survives after the client disconnects and can be reconnected to
 */
public record CreateSessionRequest(String agentType, Boolean persistent) {
}
