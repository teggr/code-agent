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
import java.util.regex.Pattern;
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

    // Session IDs are always gateway-generated UUIDs (see SessionManager#createSession), but
    // client-supplied path variables also flow into findBySession/stopContainer/etc.; validating
    // the shape here defends every docker CLI invocation against unexpected argument content.
    private static final Pattern SESSION_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$");
    private static final int PORT_BIND_ATTEMPTS = 5;

    private static final Pattern CONTAINER_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,150}$");

    private final DockerProperties properties;
    private final HostPortAllocator portAllocator;

    public ContainerManager(DockerProperties properties, HostPortAllocator portAllocator) {
        this.properties = properties;
        this.portAllocator = portAllocator;
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
     * Creates (but does not start) a container for the given session, publishing the container's
     * Copilot port on a unique host port bound to 127.0.0.1 only.
     *
     * <p>The gateway runs as a plain JVM process on the host and therefore cannot resolve Docker
     * DNS names; the published loopback port is what makes the headless Copilot CLI reachable.
     * Binding explicitly to {@code 127.0.0.1} keeps the port off 0.0.0.0, the LAN, Tailscale and
     * any public interface.
     *
     * <p>Port allocation is racy by nature, so Docker is treated as the authority: if
     * {@code docker create} rejects the mapping (e.g. the port was taken between probe and
     * create), another port is allocated and the create retried.
     *
     * @return the container name plus the host port to connect to
     */
    public CreatedContainer createContainer(String sessionId, AgentDefinition agent, boolean persistent) {
        validateSessionId(sessionId);
        String containerName = containerName(sessionId);
        Path workspace = ensureWorkspaceDir(sessionId);
        Path envFile = writeEnvFile(sessionId, agent);

        try {
            DockerException lastFailure = null;
            for (int attempt = 0; attempt < PORT_BIND_ATTEMPTS; attempt++) {
                int hostPort = portAllocator.allocate();
                List<String> command = new ArrayList<>(List.of(
                        "docker", "create",
                        "--name", containerName,
                        "--network", properties.network(),
                        "--publish", HostPortAllocator.LOOPBACK + ":" + hostPort + ":" + agent.copilotPort(),
                        "--label", ContainerLabels.MANAGED_BY + "=true",
                        "--label", ContainerLabels.SESSION + "=" + sessionId,
                        "--label", ContainerLabels.LIFECYCLE + "=" + (persistent ? "persistent" : "ephemeral"),
                        "-v", workspace.toAbsolutePath() + ":/workspace"));
                command.add("--env-file");
                command.add(envFile.toAbsolutePath().toString());
                command.add(agent.image());

                try {
                    run(command.toArray(String[]::new));
                    return new CreatedContainer(containerName, hostPort);
                } catch (DockerException e) {
                    lastFailure = e;
                    removeQuietly(containerName);
                    if (!isPortConflict(e)) {
                        // Nothing to do with the port (bad image, bad mount, ...): give the port
                        // back and fail fast rather than burning the whole retry budget.
                        portAllocator.release(hostPort);
                        throw e;
                    }
                    // Docker is the authority: it says this mapping is unusable, so the port stays
                    // reserved (something outside the gateway owns it) and we try the next one.
                    log.warn("docker create failed for session {}: host port {} unavailable, retrying with another port",
                            sessionId, hostPort);
                }
            }
            throw new DockerException("Unable to create container for session " + sessionId
                    + " after " + PORT_BIND_ATTEMPTS + " host port attempts", lastFailure);
        } finally {
            // Secrets (e.g. COPILOT_GITHUB_TOKEN) must never linger in argv, where any host user
            // able to read /proc could see them; --env-file keeps them out of the process list,
            // and the file itself is deleted immediately after `docker create` has read it.
            deleteQuietly(envFile);
        }
    }

    private static boolean isPortConflict(DockerException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("port is already allocated")
                || message.contains("address already in use")
                || message.contains("bind for")
                || message.contains("failed to bind");
    }

    private void removeQuietly(String containerName) {
        try {
            run("docker", "rm", "-f", containerName);
        } catch (DockerException ignored) {
            // The container most likely was never created; nothing to clean up.
        }
    }

    /**
     * Writes container environment variables (including secrets) to a private, 0600 temp file
     * consumed via {@code docker create --env-file}, so secrets never appear in process argument
     * lists (e.g. {@code ps aux}) as plain {@code -e KEY=VALUE} pairs would.
     */
    private Path writeEnvFile(String sessionId, AgentDefinition agent) {
        try {
            Path envFile = Files.createTempFile("cloud-agent-" + sessionId + "-", ".env");
            try {
                Files.setPosixFilePermissions(envFile, java.util.EnumSet.of(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows); best-effort only.
            }

            StringBuilder content = new StringBuilder();
            content.append("COPILOT_PORT=").append(agent.copilotPort()).append('\n');
            agent.environment().forEach((key, value) -> content.append(key).append('=').append(value).append('\n'));

            // Pass through auth env vars from the gateway's own environment if present, so operators
            // can set them once on the host without touching agent config.
            for (String passthrough : List.of("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN")) {
                String value = System.getenv(passthrough);
                if (value != null && !value.isBlank()) {
                    content.append(passthrough).append('=').append(value).append('\n');
                }
            }

            Files.writeString(envFile, content.toString());
            return envFile;
        } catch (IOException e) {
            throw new DockerException("Unable to write container env file for session " + sessionId, e);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temporary env file {}", path, e);
        }
    }

    public void startContainer(String containerName) {
        validateContainerName(containerName);
        run("docker", "start", containerName);
    }

    public void stopContainer(String containerName) {
        validateContainerName(containerName);
        run("docker", "stop", containerName);
    }

    public void removeContainer(String containerName) {
        validateContainerName(containerName);
        run("docker", "rm", "-f", containerName);
    }

    /**
     * Returns the DNS name other containers on {@code cloud-agent-net} can use to reach this one.
     *
     * <p>Not usable by the gateway itself, which runs outside Docker and must go through the
     * published loopback port instead (see {@link #createContainer}).
     */
    public String hostname(String containerName) {
        validateContainerName(containerName);
        return containerName;
    }

    /**
     * Returns the 127.0.0.1 host port the given container's Copilot port is published on, as
     * reported by Docker, so a restarted gateway can rediscover an existing mapping.
     */
    public Optional<Integer> findHostPort(String containerName, int copilotPort) {
        validateContainerName(containerName);
        List<String> lines;
        try {
            lines = run("docker", "inspect", "--format",
                    "{{range $p := index .NetworkSettings.Ports \"" + copilotPort + "/tcp\"}}{{$p.HostIp}}:{{$p.HostPort}}\n{{end}}",
                    containerName);
        } catch (DockerException e) {
            return Optional.empty();
        }
        return lines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith(HostPortAllocator.LOOPBACK + ":"))
                .map(line -> line.substring(line.lastIndexOf(':') + 1))
                .filter(port -> !port.isBlank())
                .map(Integer::parseInt)
                .findFirst();
    }

    public Optional<ContainerInfo> inspectContainer(String containerName) {
        validateContainerName(containerName);
        List<String> lines;
        try {
            lines = run("docker", "inspect",
                    "--format", "{{.Id}}|{{.Name}}|{{.State.Running}}|{{.State.Status}}|"
                            + "{{range $port, $bindings := .NetworkSettings.Ports}}"
                            + "{{range $b := $bindings}}{{$b.HostIp}}:{{$b.HostPort}},{{end}}{{end}}",
                    containerName);
        } catch (DockerException e) {
            return Optional.empty();
        }
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        String[] parts = lines.get(0).split("\\|", -1);
        String name = parts[1].startsWith("/") ? parts[1].substring(1) : parts[1];
        Integer hostPort = parts.length > 4 ? parseLoopbackBinding(parts[4]) : null;
        return Optional.of(new ContainerInfo(parts[0], name, Boolean.parseBoolean(parts[2]), parts[3], hostPort));
    }

    private static Integer parseLoopbackBinding(String bindings) {
        for (String binding : bindings.split(",")) {
            String trimmed = binding.trim();
            if (trimmed.startsWith(HostPortAllocator.LOOPBACK + ":")) {
                String port = trimmed.substring(trimmed.lastIndexOf(':') + 1);
                if (!port.isBlank()) {
                    return Integer.valueOf(port);
                }
            }
        }
        return null;
    }

    /** Finds an existing (possibly stopped) container belonging to the given session, if any. */    public Optional<ContainerInfo> findBySession(String sessionId) {
        validateSessionId(sessionId);
        List<String> ids = run("docker", "ps", "-a",
                "--filter", "label=" + ContainerLabels.SESSION + "=" + sessionId,
                "--format", "{{.ID}}");
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return inspectContainer(ids.get(0));
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || !SESSION_ID_PATTERN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("Invalid session id: " + sessionId);
        }
    }

    private void validateContainerName(String containerName) {
        if (containerName == null || !CONTAINER_NAME_PATTERN.matcher(containerName).matches()) {
            throw new IllegalArgumentException("Invalid container name: " + containerName);
        }
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

    /** Runs a {@code docker} CLI command. Package-private (not private) so tests can intercept it. */
    List<String> run(String... command) {
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
