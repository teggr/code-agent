package com.teggr.codeagent.agent.runtime.docker;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.teggr.codeagent.agent.GitRepositoryWorkspace;
import com.teggr.codeagent.agent.GitRepositoryWorkspaceProperties;
import com.teggr.codeagent.agent.runtime.AgentRuntime;
import com.teggr.codeagent.agent.runtime.AgentRuntimeInstance;
import com.teggr.codeagent.agent.runtime.AgentRuntimeRequest;
import com.teggr.codeagent.agent.runtime.DiscoveredAgent;
import com.teggr.codeagent.agent.runtime.WorkspaceAccess;
import com.teggr.codeagent.harness.copilot.CopilotHarnessProperties;

/** Launches and tears down the Docker runtime that serves the Copilot CLI harness. */
@Service
public class DockerAgentRuntime implements AgentRuntime {

    /** Label applied to every managed runtime so a restarted application can discover it. */
    static final String MANAGED_LABEL = "codeagent.managed";

    static final String AGENT_ID_LABEL = "codeagent.agent.id";
    static final String REPO_URL_LABEL = "codeagent.repo.url";
    static final String WORKSPACE_PATH_LABEL = "codeagent.workspace.path";
    static final String CREATED_AT_LABEL = "codeagent.created-at";

    private static final int AGENT_PORT = 4321;
    private static final String DEFAULT_DOCKER_SOCKET_PATH = "/var/run/docker.sock";
    private static final int LOG_TAIL_LINES = 25;
    private static final int LOG_TAIL_TIMEOUT_SECONDS = 5;

    /** Mirrors entrypoint.sh's GIT_REPO_URL validation so the derived clone directory name matches. */
    private static final Pattern GIT_REPO_URL_PATTERN =
            Pattern.compile("^https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]*/([A-Za-z0-9._-]+?)(?:\\.git)?$");

    private final DockerClient dockerClient;
    private final DockerAgentRuntimeProperties properties;
    private final GitRepositoryWorkspaceProperties gitProperties;
    private final CopilotHarnessProperties copilotProperties;

    public DockerAgentRuntime(DockerClient dockerClient, DockerAgentRuntimeProperties properties,
            GitRepositoryWorkspaceProperties gitProperties, CopilotHarnessProperties copilotProperties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
        this.gitProperties = gitProperties;
        this.copilotProperties = copilotProperties;
    }

    @Override
    public AgentRuntimeInstance provision(AgentRuntimeRequest request) throws Exception {
        if (!(request.workspace() instanceof GitRepositoryWorkspace workspace)) {
            throw new IllegalArgumentException("Docker runtime does not support workspace type "
                    + request.workspace().getClass().getName());
        }
        String gitRepoUrl = workspace.repositoryUrl();
        String agentId = request.agentId();
        String image = properties.getImage();
        String gitToken = gitProperties.getToken();
        String copilotToken = copilotProperties.getToken();

        if (gitToken == null || gitToken.isEmpty()) {
            throw new IllegalStateException("GH_TOKEN environment variable is not set");
        }
        // Copilot token is an override; fall back to the git token by default.
        if (copilotToken == null || copilotToken.isEmpty()) {
            copilotToken = gitToken;
        }

        try {
            ExposedPort exposedPort = ExposedPort.tcp(AGENT_PORT);
            Bind dockerSocketBind = resolveDockerSocketBind();
            System.out.println("Mounting Docker socket into agent runtime: " + dockerSocketBind);

            String workspacePath = workspacePath(gitRepoUrl);

            CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withExposedPorts(exposedPort)
                .withHostConfig(HostConfig.newHostConfig()
                    .withPublishAllPorts(true)
                    .withBinds(dockerSocketBind)
                )
                .withLabels(Map.of(MANAGED_LABEL, "true",
                    AGENT_ID_LABEL, agentId,
                    REPO_URL_LABEL, gitRepoUrl,
                    WORKSPACE_PATH_LABEL, workspacePath,
                    CREATED_AT_LABEL, Instant.now().toString()))
                .withEnv("GH_TOKEN=" + gitToken,
                    "COPILOT_GITHUB_TOKEN=" + copilotToken,
                    "GIT_REPO_URL=" + gitRepoUrl)
                .exec();

            String containerId = container.getId();

            dockerClient.startContainerCmd(containerId).exec();

            AgentRuntimeInstance instance = runtimeInstance(containerId,
                    getAllocatedHostPort(containerId, exposedPort), workspacePath);
            System.out.println("Container started with ID: " + instance.runtimeId()
                    + " on host port " + instance.harnessPort());
            System.out.println("Open workspace in VS Code (attached container):");
            System.out.println("  CLI: " + instance.workspaceAccess().cliCommand());
            System.out.println("  URL: " + instance.workspaceAccess().uri());

            return instance;
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && (message.contains("pull access denied") || message.contains("image not found"))) {
                throw new RuntimeException("Docker image not found: " + image + ". Please build it with: mvnw verify -pl code-agent-runner", e);
            } else if (message != null && message.contains("Cannot connect to Docker daemon")) {
                throw new RuntimeException("Docker daemon is not running. Please start Docker and try again.", e);
            }
            throw e;
        }
    }

    /** Derives the cloned repository's directory (see entrypoint.sh), falling back to /workspace if unparseable. */
    private static String workspacePath(String gitRepoUrl) {
        Matcher matcher = GIT_REPO_URL_PATTERN.matcher(gitRepoUrl);
        if (!matcher.matches()) {
            return "/workspace";
        }
        return "/workspace/" + matcher.group(1);
    }

    /** Stops the container without removing it, so it can be started and reconnected to later. */
    public void stop(String containerId) {
        System.out.println("Stopping container: " + containerId);
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            System.out.println("Container stopped");
        } catch (Exception e) {
            System.err.println("Error stopping container " + containerId + ": " + e.getMessage());
        }
    }

    /** Stops and deletes the container along with everything in its workspace. */
    @Override
    public void delete(String containerId) {
        stop(containerId);
        try {
            dockerClient.removeContainerCmd(containerId).exec();
            System.out.println("Container removed");
        } catch (Exception e) {
            System.err.println("Error removing container " + containerId + ": " + e.getMessage());
        }
    }

    /** Starts a stopped runtime again; Docker publishes a new host port, so it is re-read here. */
    @Override
    public AgentRuntimeInstance start(AgentRuntimeInstance instance) {
        String containerId = instance.runtimeId();
        dockerClient.startContainerCmd(containerId).exec();
        int hostPort = getAllocatedHostPort(containerId, ExposedPort.tcp(AGENT_PORT));
        System.out.println("Container " + containerId + " restarted on host port " + hostPort);
        return runtimeInstance(containerId, hostPort, instance.workingDirectory());
    }

    /**
    * Fails fast when the runtime container has already exited, so an entrypoint failure (bad token,
     * unauthorised repository, clone failure) surfaces as its own error instead of an opaque
     * connection timeout after every retry has been exhausted.
     */
    public void verifyRunning(String containerId) {
        var state = dockerClient.inspectContainerCmd(containerId).exec().getState();
        if (Boolean.TRUE.equals(state.getRunning())) {
            return;
        }
        throw new IllegalStateException("Agent runtime container " + containerId + " exited with code "
                + state.getExitCodeLong() + " before the agent server became available. Last output:\n"
                + tailLogs(containerId));
    }

    private String tailLogs(String containerId) {
        var logs = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId)
                .withStdOut(true)
                .withStdErr(true)
                .withTail(LOG_TAIL_LINES)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        logs.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                })
                .awaitCompletion(LOG_TAIL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(interrupted while reading container logs)";
        } catch (Exception e) {
            return "(container logs unavailable: " + e.getMessage() + ")";
        }
        return logs.isEmpty() ? "(no container output)" : logs.toString().strip();
    }

    /**
    * Finds managed runtime containers left behind by a previous application run. Resources without
    * canonical Agent identity are deleted rather than interpreted through compatibility behavior.
     */
    @Override
    public List<DiscoveredAgent> discover() {
        List<Container> containers = dockerClient.listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(Map.of(MANAGED_LABEL, "true"))
            .exec();

        List<DiscoveredAgent> managed = new ArrayList<>();
        for (Container container : containers) {
            Map<String, String> labels = container.getLabels() == null ? Map.of() : container.getLabels();
            String agentId = labels.get(AGENT_ID_LABEL);
            if (agentId == null || agentId.isBlank()) {
                System.out.println("Removing runtime container without canonical agent identity " + container.getId());
                delete(container.getId());
                continue;
            }

            try {
                managed.add(inspectManaged(container.getId(), agentId, labels));
            } catch (Exception e) {
                System.err.println("Unable to inspect agent runtime " + container.getId() + ": " + e.getMessage());
            }
        }
        return managed;
    }

        private DiscoveredAgent inspectManaged(String containerId, String agentId, Map<String, String> labels) {
        InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
        boolean running = Boolean.TRUE.equals(inspect.getState().getRunning());
        String workspacePath = labels.getOrDefault(WORKSPACE_PATH_LABEL, "/workspace");
        String repoUrl = labels.getOrDefault(REPO_URL_LABEL, "");
        // Docker assigns a new host port whenever it restarts the container, so it is never remembered.
        int hostPort = running ? getAllocatedHostPort(inspect, ExposedPort.tcp(AGENT_PORT)) : 0;
        return new DiscoveredAgent(agentId, new GitRepositoryWorkspace(repoUrl),
            runtimeInstance(containerId, hostPort, workspacePath), running);
    }

        private static AgentRuntimeInstance runtimeInstance(String containerId, int hostPort, String workspacePath) {
        String encodedContainerId = HexFormat.of().formatHex(shortContainerId(containerId)
            .getBytes(StandardCharsets.UTF_8));
        WorkspaceAccess access = new WorkspaceAccess(
            "vscode://vscode-remote/attached-container+" + encodedContainerId + workspacePath
                + "?windowId=_blank",
            "code --new-window --folder-uri vscode-remote://attached-container+" + encodedContainerId
                + workspacePath);
        return new AgentRuntimeInstance(containerId, hostPort, workspacePath, access);
        }

        private static String shortContainerId(String containerId) {
        return containerId.length() > 12 ? containerId.substring(0, 12) : containerId;
        }

    /**
    * Resolves the bind mount that gives the runtime access to the host's Docker daemon
    * (Docker-outside-of-Docker), so hosted workspaces can build images and run services.
     * The source path is resolved by the daemon, not by this process, so it must be the socket path
     * as seen by the daemon's own host: /var/run/docker.sock under Docker Desktop, native Linux
     * Docker, and Podman machine alike (Podman ships a compatibility symlink there).
     */
    Bind resolveDockerSocketBind() {
        var info = dockerClient.infoCmd().exec();

        if ("windows".equalsIgnoreCase(info.getOsType())) {
            throw new IllegalStateException(
                    "The Docker daemon is running Windows containers; the agent runtime requires Linux-container mode. "
                    + "Switch Docker Desktop to Linux containers and try again.");
        }

        String source = properties.getSocketPath();
        if (source == null || source.isBlank()) {
            source = DEFAULT_DOCKER_SOCKET_PATH;
        }
        return new Bind(source, new Volume(DEFAULT_DOCKER_SOCKET_PATH));
    }

    private int getAllocatedHostPort(String containerId, ExposedPort exposedPort) {
        return getAllocatedHostPort(dockerClient.inspectContainerCmd(containerId).exec(), exposedPort);
    }

    private int getAllocatedHostPort(InspectContainerResponse containerInfo, ExposedPort exposedPort) {
        var portBindings = containerInfo.getNetworkSettings().getPorts().getBindings().get(exposedPort);

        if (portBindings == null || portBindings.length == 0 || portBindings[0].getHostPortSpec() == null) {
            throw new IllegalStateException("Docker did not publish a host port for container port "
                    + exposedPort.getPort());
        }

        try {
            return Integer.parseInt(portBindings[0].getHostPortSpec());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Docker returned an invalid host port for container port "
                    + exposedPort.getPort() + ": " + portBindings[0].getHostPortSpec(), e);
        }
    }

}
