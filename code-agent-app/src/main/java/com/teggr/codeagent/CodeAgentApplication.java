package com.teggr.codeagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import com.github.copilot.CopilotClient;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.SessionConfig;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

@SpringBootApplication
public class CodeAgentApplication {

    private static final String EXAMPLE_PROMPT = """
        Demonstrate GitHub resource access and PR capabilities in your environment:
        1. Run `gh auth status` to confirm GitHub CLI authentication.
        2. List open and recent GitHub issues for this repository using `gh issue list`.
        3. List open and recent Pull Requests for this repository using `gh pr list`.
        4. Check git remote configuration and verify you can create branches and PRs (`gh pr create --help`).
        """;

    private static final String EXAMPLE_PR_PROMPT = """
          Demonstrate a complete repository change and pull request workflow.

          Work in the checked-out J2HTML repository and complete every applicable step:
          1. Confirm the current repository and inspect `README.md` before selecting the change.
          2. Create a uniquely named, descriptive branch from the current branch.
          3. Make exactly one small, accurate, user-facing improvement to `README.md`, grounded in
              the repository's existing content. Do not make unrelated formatting changes or refactors.
          4. Identify and run the repository's documented or standard build/test command. Resolve only
              issues introduced by your README change.
          5. Inspect the final diff and `git status`.
          6. Commit only `README.md` using a conventional, documentation-focused commit message.
          7. Push the branch and create a non-draft GitHub pull request with a concise title and body
              that describe the documentation update and its successful verification.
          8. Output a final plain-text summary with the repository, branch, changed file and substantive
              change, exact verification command and result, commit SHA and message, and pull request
              number and URL. If a required step cannot complete, state the blocker clearly.
        """;

    public static void main(String[] args) {
        SpringApplication.run(CodeAgentApplication.class, args);
    }

    @Bean
    ApplicationRunner applicationRunner(ApplicationContext context) {
        return args -> {
            System.out.println("Starting application with Docker container...");
            
            // Initialize Docker client
            var dockerConfig = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
            var dockerHttpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(dockerConfig.getDockerHost())
                .sslConfig(dockerConfig.getSSLConfig())
                .build();
            var dockerClient = DockerClientImpl.getInstance(dockerConfig, dockerHttpClient);
            
            ContainerLaunch containerLaunch = null;
            var containerCleanedUp = new AtomicBoolean();
            int exitCode = 0;
            
            try {
                containerLaunch = launchContainer(dockerClient);
                System.out.println("Container started with ID: " + containerLaunch.containerId()
                        + " on host port " + containerLaunch.hostPort());
                
                // Register shutdown hook for cleanup
                final String finalContainerId = containerLaunch.containerId();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (containerCleanedUp.compareAndSet(false, true)) {
                        try {
                            stopContainer(dockerClient, finalContainerId);
                        } catch (Exception e) {
                            System.err.println("Error stopping container: " + e.getMessage());
                        }
                    }
                }));
                
                // Connect to CLI and run example
                runCopilotClient(containerLaunch.hostPort());
                
            } catch (Exception e) {
                System.err.println("Error during startup: " + e.getMessage());
                e.printStackTrace();
                exitCode = 1;
            } finally {
                if (containerLaunch != null && containerCleanedUp.compareAndSet(false, true)) {
                    try {
                        stopContainer(dockerClient, containerLaunch.containerId());
                    } catch (Exception stopError) {
                        System.err.println("Error stopping container in finally block: " + stopError.getMessage());
                    }
                }
            }

            System.out.println("Run finished; shutting down with exit code " + exitCode);
            int finalExitCode = exitCode;
            System.exit(SpringApplication.exit(context, () -> finalExitCode));
        };
    }
    
    private ContainerLaunch launchContainer(DockerClient dockerClient) throws Exception {
        String image = "teggr/code-agent-runner:0.1.0-SNAPSHOT";
        String ghToken = System.getenv("GH_TOKEN");
        
        if (ghToken == null || ghToken.isEmpty()) {
            throw new IllegalStateException("GH_TOKEN environment variable is not set");
        }
        
        try {
            ExposedPort exposedPort = ExposedPort.tcp(4321);
            Bind dockerSocketBind = resolveDockerSocketBind(dockerClient);
            System.out.println("Mounting Docker socket into runner: " + dockerSocketBind);

            CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withExposedPorts(exposedPort)
                .withHostConfig(HostConfig.newHostConfig()
                    .withPublishAllPorts(true)
                    .withBinds(dockerSocketBind)
                )
                .withEnv("GH_TOKEN=" + ghToken,
                    "GIT_REPO_URL=https://github.com/teggr/j2html-toolkit")
                .exec();
            
            String containerId = container.getId();
            
            // Start the container
            dockerClient.startContainerCmd(containerId).exec();
            
            return new ContainerLaunch(containerId, getAllocatedHostPort(dockerClient, containerId, exposedPort));
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
    
    /**
     * Resolves the bind mount that gives the runner container access to the host's Docker daemon
     * (Docker-outside-of-Docker), so projects in the runner can build images and run services.
     * Detection is based on the daemon's own report rather than the launcher OS: Docker Desktop
     * (Windows/macOS) serves a Linux VM whose socket it accepts in bind mounts from either host,
     * while a native Linux host (e.g. the VPS) exposes the socket directly.
     */
    private Bind resolveDockerSocketBind(DockerClient dockerClient) {
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

    private int getAllocatedHostPort(DockerClient dockerClient, String containerId, ExposedPort exposedPort) {
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

    private void runCopilotClient(int hostPort) throws Exception {
        int maxRetries = 30;
        int retryDelayMs = 500;
        int startTimeoutSeconds = 5;
        System.out.println("Connecting to Copilot CLI on host port " + hostPort + " (" + maxRetries + " attempts, "
                + retryDelayMs + "ms apart)");
        
        for (int i = 0; i < maxRetries; i++) {
            int attempt = i + 1;
            System.out.println("Starting Copilot client (attempt " + attempt + " of " + maxRetries + ")");
            try {
                var options = new CopilotClientOptions()
                    .setCliUrl("localhost:" + hostPort)
                    .setLogLevel("debug");
                try (var client = new CopilotClient(options)) {
                    client.start().get(startTimeoutSeconds, TimeUnit.SECONDS);
                    System.out.println("Copilot client connected on attempt " + attempt + " of " + maxRetries);
            
                    System.out.println("Creating session");
                    var session = client.createSession(
                        new SessionConfig()
                            .setOnPermissionRequest(PermissionHandler.APPROVE_ALL)
                    ).get();
            
                    System.out.println("Sending message");
                    var done = new CompletableFuture<Void>();
                    session.on(AssistantMessageEvent.class, msg -> {
                        System.out.println("Response: " + msg.getData().content());
                    });
                    session.on(SessionIdleEvent.class, idle -> done.complete(null));
            
                    session.send(new MessageOptions().setPrompt(EXAMPLE_PROMPT)).get();
                    done.get();
            
                    System.out.println("Example message completed successfully");
                    return;
                }
            } catch (Exception e) {
                if (i == maxRetries - 1) {
                    System.out.println("Copilot client failed after all " + maxRetries + " attempts");
                    throw e;
                }
                System.out.println("Copilot client failed on attempt " + attempt + " of " + maxRetries
                        + "; retrying in " + retryDelayMs + "ms: " + e.getClass().getSimpleName());
                Thread.sleep(retryDelayMs);
            }
        }
    }

    private void stopContainer(DockerClient dockerClient, String containerId) throws Exception {
        System.out.println("Stopping container: " + containerId);
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(10).exec();
            dockerClient.removeContainerCmd(containerId).exec();
            System.out.println("Container stopped and removed");
        } catch (Exception e) {
            System.err.println("Error during container cleanup: " + e.getMessage());
        }
    }

    private record ContainerLaunch(String containerId, int hostPort) {
    }

}
