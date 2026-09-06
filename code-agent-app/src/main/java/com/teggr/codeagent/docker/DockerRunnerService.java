package com.teggr.codeagent.docker;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;

/** Launches and tears down the code-agent-runner container that serves the Copilot CLI. */
@Service
public class DockerRunnerService {

    private final DockerClient dockerClient;
    private final DockerRunnerProperties properties;

    public DockerRunnerService(DockerClient dockerClient, DockerRunnerProperties properties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
    }

    public ContainerLaunch launch(String gitRepoUrl) throws Exception {
        String image = properties.getImage();
        String ghToken = properties.getGhToken();

        if (ghToken == null || ghToken.isEmpty()) {
            throw new IllegalStateException("GH_TOKEN environment variable is not set");
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
                .withEnv("GH_TOKEN=" + ghToken,
                    "GIT_REPO_URL=" + gitRepoUrl)
                .exec();

            String containerId = container.getId();

            dockerClient.startContainerCmd(containerId).exec();

            ContainerLaunch launch = new ContainerLaunch(containerId, getAllocatedHostPort(containerId, exposedPort));
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
     * Resolves the bind mount that gives the runner container access to the host's Docker daemon
     * (Docker-outside-of-Docker), so projects in the runner can build images and run services.
     * Detection is based on the daemon's own report rather than the launcher OS: Docker Desktop
     * (Windows/macOS) serves a Linux VM whose socket it accepts in bind mounts from either host,
     * while a native Linux host (e.g. the VPS) exposes the socket directly.
     */
    private Bind resolveDockerSocketBind() {
        var info = dockerClient.infoCmd().exec();
        String osType = info.getOsType();
        String operatingSystem = info.getOperatingSystem();
        String dockerRootDir = info.getDockerRootDir();

        if ("windows".equalsIgnoreCase(osType)) {
            throw new IllegalStateException(
                    "The Docker daemon is running Windows containers; the runner requires Linux-container mode. "
                    + "Switch Docker Desktop to Linux containers and try again.");
        }

        boolean dockerDesktop = operatingSystem != null && operatingSystem.contains("Docker Desktop");
        if (dockerDesktop) {
            // Docker Desktop's Linux VM socket; it accepts this path in bind mounts from Windows and macOS hosts.
            return new Bind("/var/run/docker.sock", new Volume("/var/run/docker.sock"));
        }

        var socketPath = Path.of("/var/run/docker.sock");
        if (Files.exists(socketPath)) {
            return new Bind(socketPath.toString(), new Volume("/var/run/docker.sock"));
        }

        throw new IllegalStateException(
                "No usable Docker socket found: the runner requires /var/run/docker.sock to be mountable. "
                + "On Windows/macOS use Docker Desktop in Linux-container mode; on Linux run a standard Docker daemon. "
                + "(daemon reported osType=" + osType + ", operatingSystem=" + operatingSystem
                + ", dockerRootDir=" + dockerRootDir + ")");
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
