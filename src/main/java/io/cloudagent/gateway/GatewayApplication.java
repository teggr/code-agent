package io.cloudagent.gateway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the Cloud Agent Gateway prototype.
 *
 * <p>The gateway is the control plane: it manages Docker containers running a headless
 * GitHub Copilot CLI, attaches to that CLI over the network using the Copilot SDK for Java,
 * and exposes a REST/WebSocket API so clients can create sessions, send prompts, and stream
 * agent events. The gateway never spawns Copilot CLI itself.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        ensureParentDirectoryExists();
        SpringApplication.run(GatewayApplication.class, args);
    }

    /**
     * The SQLite JDBC driver refuses to create missing parent directories for its database file,
     * so ensure the default (or overridden) path's parent exists before Spring attempts to open a
     * connection during startup.
     */
    private static void ensureParentDirectoryExists() {
        String dbPath = System.getenv().getOrDefault("CLOUD_AGENT_DB_PATH", "./data/cloud-agent-gateway.db");
        Path parent = Path.of(dbPath).toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to create database directory: " + parent, e);
            }
        }
    }
}
