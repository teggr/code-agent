package com.teggr.codeagent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;

@SpringBootApplication
public class CodeAgentApplication {

    private static final String EXAMPLE_PROMPT = """
        Summarize the project in the current working directory in one paragraph.
        Also, what version of Java and Maven is installed in your environment?
        """;

    public static void main(String[] args) {
        SpringApplication.run(CodeAgentApplication.class, args);
    }

    @Bean
    ApplicationRunner applicationRunner() {
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
            
            try {
                containerLaunch = launchContainer(dockerClient);
                System.out.println("Container started with ID: " + containerLaunch.containerId()
                        + " on host port " + containerLaunch.hostPort());
                
                // Register shutdown hook for cleanup
                final String finalContainerId = containerLaunch.containerId();
                var containerCleanedUp = new AtomicBoolean();
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
                if (containerLaunch != null) {
                    try {
                        stopContainer(dockerClient, containerLaunch.containerId());
                    } catch (Exception stopError) {
                        System.err.println("Error stopping container after failure: " + stopError.getMessage());
                    }
                }
                throw new RuntimeException(e);
            }
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
            
            CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withExposedPorts(exposedPort)
                .withHostConfig(HostConfig.newHostConfig()
                    .withPublishAllPorts(true)
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
