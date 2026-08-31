package io.cloudagent.gateway.copilot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds the {@code cloud-agent.copilot} section of {@code application.yml}. */
@ConfigurationProperties(prefix = "cloud-agent.copilot")
public record CopilotConnectionProperties(
        long connectTimeoutSeconds,
        long pollIntervalMillis,
        String model) {

    public CopilotConnectionProperties {
        connectTimeoutSeconds = connectTimeoutSeconds <= 0 ? 30 : connectTimeoutSeconds;
        pollIntervalMillis = pollIntervalMillis <= 0 ? 500 : pollIntervalMillis;
        model = model == null || model.isBlank() ? "gpt-5.4" : model;
    }
}
