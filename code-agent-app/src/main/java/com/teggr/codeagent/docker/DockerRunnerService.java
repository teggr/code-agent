package com.teggr.codeagent.docker;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;

/** Launches and tears down the code-agent-runner container that serves the Copilot CLI. */
@Service
public class DockerRunnerService {

    /** Label applied to every runner container so orphans from a previous, uncleanly-stopped run can be found. */
    static final String MANAGED_LABEL = "codeagent.managed";

    private static final String DEFAULT_DOCKER_SOCKET_PATH = "/var/run/docker.sock";
    private static final int LOG_TAIL_LINES = 25;
    private static final int LOG_TAIL_TIMEOUT_SECONDS = 5;

    /** Mirrors entrypoint.sh's GIT_REPO_URL validation so the derived clone directory name matches. */
    private static final Pattern GIT_REPO_URL_PATTERN =
            Pattern.compile("^https://github\\.com/[A-Za-z0-9][A-Za-z0-9-]*/([A-Za-z0-9._-]+?)(?:\\.git)?$");

    private final DockerClient dockerClient;
    private final DockerRunnerProperties properties;

    public DockerRunnerService(DockerClient dockerClient, DockerRunnerProperties properties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
    }

    public ContainerLaunch launch(String gitRepoUrl) throws Exception {
        String image = properties.getImage();
        String gitToken = properties.getGitToken();
        String copilotToken = properties.getCopilotToken();

        if (gitToken == null || gitToken.isEmpty()) {
            throw new IllegalStateException("GH_TOKEN environment variable is not set");
        }
        if (copilotToken == null || copilotToken.isEmpty()) {
            throw new IllegalStateException("COPILOT_GITHUB_TOKEN environment variable is not set");
        }

        try {
            ExposedPort exposedPort = ExposedPort.tcp(4321);
            Bind dockerSocketBind = resolveDockerSocketBind();
            System.out.println("Mounting Docker socket into runner: " + dockerSocketBind);

            CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withExposedPorts(exposedPort)
                .withHostConfig(HostConfig.newHostConfig()
                    .withPublishAllPorts(true)
                    .withBinds(dockerSocketBind)
                )
                .withLabels(Map.of(MANAGED_LABEL, "true"))
                .withEnv("GH_TOKEN=" + gitToken,
                    "COPILOT_GITHUB_TOKEN=" + copilotToken,
                    "GIT_REPO_URL=" + gitRepoUrl)
                .exec();

            String containerId = container.getId();

            dockerClient.startContainerCmd(containerId).exec();

            ContainerLaunch launch = new ContainerLaunch(containerId,
                    getAllocatedHostPort(containerId, exposedPort), workspacePath(gitRepoUrl));
            System.out.println("Container started with ID: " + launch.containerId()
                    + " on host port " + launch.hostPort());
            System.out.println("Open workspace in VS Code (attached container):");
            System.out.println("  CLI: " + launch.devContainerCliCommand());
            System.out.println("  URL: " + launch.devContainerUri());

            return launch;
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

    public void stop(String containerId) {
        System.out.println("Stopping container: " + containerId);
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            dockerClient.removeContainerCmd(containerId).exec();
            System.out.println("Container stopped and removed");
        } catch (Exception e) {
            System.err.println("Error during container cleanup: " + e.getMessage());
        }
    }

    /**
     * Fails fast when the runner container has already exited, so an entrypoint failure (bad token,
     * unauthorised repository, clone failure) surfaces as its own error instead of an opaque
     * connection timeout after every retry has been exhausted.
     */
    public void verifyRunning(String containerId) {
        var state = dockerClient.inspectContainerCmd(containerId).exec().getState();
        if (Boolean.TRUE.equals(state.getRunning())) {
            return;
        }
        throw new IllegalStateException("Runner container " + containerId + " exited with code "
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
     * Stops and removes any labeled runner containers left behind by a previous, uncleanly-stopped
     * run (e.g. the JVM was force-killed and never reached the graceful shutdown path). Every
     * labeled container found here is by definition an orphan, since this runs before any runner
     * has been started in the current process.
     */
    public void pruneOrphans() {
        List<Container> orphans = dockerClient.listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(Map.of(MANAGED_LABEL, "true"))
            .exec();

        if (orphans.isEmpty()) {
            return;
        }

        System.out.println("Found " + orphans.size() + " orphaned runner container(s) from a previous run; cleaning up");
        for (Container orphan : orphans) {
            stop(orphan.getId());
        }
    }

    /**
     * Resolves the bind mount that gives the runner container access to the host's Docker daemon
     * (Docker-outside-of-Docker), so projects in the runner can build images and run services.
     * The source path is resolved by the daemon, not by this process, so it must be the socket path
     * as seen by the daemon's own host: /var/run/docker.sock under Docker Desktop, native Linux
     * Docker, and Podman machine alike (Podman ships a compatibility symlink there).
     */
    Bind resolveDockerSocketBind() {
        var info = dockerClient.infoCmd().exec();

        if ("windows".equalsIgnoreCase(info.getOsType())) {
            throw new IllegalStateException(
                    "The Docker daemon is running Windows containers; the runner requires Linux-container mode. "
                    + "Switch Docker Desktop to Linux containers and try again.");
        }

        String source = properties.getDockerSocketPath();
        if (source == null || source.isBlank()) {
            source = DEFAULT_DOCKER_SOCKET_PATH;
        }
        return new Bind(source, new Volume(DEFAULT_DOCKER_SOCKET_PATH));
    }

    private int getAllocatedHostPort(String containerId, ExposedPort exposedPort) {
        var containerInfo = dockerClient.inspectContainerCmd(containerId).exec();
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
