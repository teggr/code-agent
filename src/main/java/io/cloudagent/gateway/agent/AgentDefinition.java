package io.cloudagent.gateway.agent;

import java.util.Map;

/**
 * Configuration for a single agent "type" as declared under {@code cloud-agent.agents.*} in
 * {@code application.yml}. This is intentionally simple: no plugin system, just a named image
 * plus optional defaults that are applied whenever a session for this agent type is created.
 *
 * @param image           Docker image reference used to create the agent container.
 * @param copilotPort     Port the headless Copilot CLI listens on inside the container. Mapped
 *                        to the {@code COPILOT_PORT} environment variable passed to the container.
 * @param workspaceMount  Host path template mounted as {@code /workspace} in the container. May
 *                        contain the {@code {sessionId}} placeholder.
 * @param environment     Extra environment variables merged into every container of this type
 *                        (secrets such as tokens should be supplied at session-creation time, not
 *                        baked in here).
 * @param persistent      Whether containers of this agent type default to persistent lifecycle
 *                        (survive after the client disconnects) instead of ephemeral (removed once
 *                        the session ends).
 */
public record AgentDefinition(
        String image,
        int copilotPort,
        String workspaceMount,
        Map<String, String> environment,
        boolean persistent) {

    public AgentDefinition {
        environment = environment == null ? Map.of() : Map.copyOf(environment);
    }
}
