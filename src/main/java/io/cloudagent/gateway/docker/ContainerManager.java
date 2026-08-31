package io.cloudagent.gateway.docker;

import io.cloudagent.gateway.agent.AgentDefinition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Thin wrapper around the {@code docker} CLI that manages the lifecycle of agent containers.
 *
 * <p>The Docker CLI (rather than the Docker Engine HTTP API or a client library) was chosen
 * because it is already present on any host that can run containers, needs no extra dependency,
 * and is simple to reason about and to test. Every container created by the gateway is labelled
 * with {@link ContainerLabels#SESSION} so it can be found again later (see
 * {@link #findBySession(String)}), which is what makes reconnecting to a persistent agent
 * possible.
 */
@Component
public class ContainerManager {

    private static final Logger log = LoggerFactory.getLogger(ContainerManager.class);

    private final DockerProperties properties;

    public ContainerManager(DockerProperties properties) {
        this.properties = properties;
        ensureNetwork();
    }

    /** Creates the shared Docker network used by the gateway and its agent containers, if absent. */
    public void ensureNetwork() {
        List<String> existing = run("docker", "network", "ls", "--filter", "name=^" + properties.network() + "$", "--format", "{{.Name}}");
        if (existing.stream().noneMatch(n -> n.equals(properties.network()))) {
            log.info("Creating Docker network {}", properties.network());
            run("docker", "network", "create", properties.network());
        }
    }

    /**
     * Creates (but does not start) a container for the given session.
     *
     * @return the container name, which is also used as the DNS-resolvable hostname on the
     *         gateway's Docker network (see {@link #hostname(String)})
     */
    public String createContainer(String sessionId, AgentDefinition agent, boolean persistent) {
        String containerName = containerName(sessionId);
        Path workspace = ensureWorkspaceDir(sessionId);

        List<String> command = new ArrayList<>(List.of(
                "docker", "create",
                "--name", containerName,
                "--network", properties.network(),
                "--label", ContainerLabels.MANAGED_BY + "=true",
                "--label", ContainerLabels.SESSION + "=" + sessionId,
                "--label", ContainerLabels.LIFECYCLE + "=" + (persistent ? "persistent" : "ephemeral"),
                "-v", workspace.toAbsolutePath() + ":/workspace",
                "-e", "COPILOT_PORT=" + agent.copilotPort()));

        agent.environment().forEach((key, value) -> {
            command.add("-e");
            command.add(key + "=" + value);
        });

        // Pass through auth env vars from the gateway's own environment if present, so operators
        // can `docker run`/systemd-set them once on the host without touching agent config.
        for (String passthrough : List.of("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN")) {
            String value = System.getenv(passthrough);
            if (value != null && !value.isBlank()) {
                command.add("-e");
                command.add(passthrough + "=" + value);
            }
        }

        command.add(agent.image());

        run(command.toArray(String[]::new));
        return containerName;
    }

    public void startContainer(String containerName) {
        run("docker", "start", containerName);
    }

    public void stopContainer(String containerName) {
        run("docker", "stop", containerName);
    }

    public void removeContainer(String containerName) {
        run("docker", "rm", "-f", containerName);
    }

    /** Returns the DNS name other containers on {@code cloud-agent-net} can use to reach this one. */
    public String hostname(String containerName) {
        return containerName;
    }

    public Optional<ContainerInfo> inspectContainer(String containerName) {
        List<String> lines;
        try {
            lines = run("docker", "inspect",
                    "--format", "{{.Id}}|{{.Name}}|{{.State.Running}}|{{.State.Status}}",
                    containerName);
        } catch (DockerException e) {
            return Optional.empty();
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = lines.get(0).split("\\|", -1);
        String name = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
        return Optional.of(new ContainerInfo(parts[0], name, Boolean.parseBoolean(parts[2]), parts[3], null));
    }

    /** Finds an existing (possibly stopped) container belonging to the given session, if any. */
    public Optional<ContainerInfo> findBySession(String sessionId) {
        List<String> ids = run("docker", "ps", "-a",
                "--filter", "label=" + ContainerLabels.SESSION + "=" + sessionId,
                "--format", "{{.ID}}");
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return inspectContainer(ids.get(0));
    }

    private Path ensureWorkspaceDir(String sessionId) {
        try {
            Path dir = Path.of(properties.workspaceRoot(), sessionId);
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new DockerException("Unable to create workspace directory for session " + sessionId, e);
        }
    }

    private String containerName(String sessionId) {
        return properties.containerPrefix() + "-" + sessionId;
    }

    private List<String> run(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        try {
            Process process = builder.start();
            List<String> output = new ArrayList<>();
            try (var reader = process.inputReader()) {
                reader.lines().forEach(output::add);
            }
            boolean finished = process.waitFor(properties.commandTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new DockerException("Command timed out after " + Duration.ofSeconds(properties.commandTimeoutSeconds())
                        + ": " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new DockerException("Command failed (" + process.exitValue() + "): " + String.join(" ", command)
                        + "\n" + String.join("\n", output));
            }
            return output;
        } catch (IOException e) {
            throw new DockerException("Unable to run command: " + String.join(" ", command), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerException("Interrupted while running command: " + String.join(" ", command), e);
        }
    }
}
