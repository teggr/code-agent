package com.teggr.codeagent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import com.teggr.codeagent.agent.AgentHarness;
import com.teggr.codeagent.agent.AgentHarnessFactory;
import com.teggr.codeagent.docker.ContainerLaunch;
import com.teggr.codeagent.docker.DockerRunnerService;

@SpringBootApplication
@ConfigurationPropertiesScan
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
    ApplicationRunner applicationRunner(ApplicationContext context, DockerRunnerService dockerRunnerService,
            AgentHarnessFactory agentHarnessFactory) {
        return args -> {
            System.out.println("Starting application with Docker container...");

            ContainerLaunch containerLaunch = null;
            var containerCleanedUp = new AtomicBoolean();
            int exitCode = 0;

            try {
                containerLaunch = dockerRunnerService.launch();
                System.out.println("Container started with ID: " + containerLaunch.containerId()
                        + " on host port " + containerLaunch.hostPort());

                final String finalContainerId = containerLaunch.containerId();
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    if (containerCleanedUp.compareAndSet(false, true)) {
                        dockerRunnerService.stop(finalContainerId);
                    }
                }));

                runAgentPrompt(agentHarnessFactory, containerLaunch.hostPort());

            } catch (Exception e) {
                System.err.println("Error during startup: " + e.getMessage());
                e.printStackTrace();
                exitCode = 1;
            } finally {
                if (containerLaunch != null && containerCleanedUp.compareAndSet(false, true)) {
                    dockerRunnerService.stop(containerLaunch.containerId());
                }
            }

            System.out.println("Run finished; shutting down with exit code " + exitCode);
            int finalExitCode = exitCode;
            System.exit(SpringApplication.exit(context, () -> finalExitCode));
        };
    }

    private void runAgentPrompt(AgentHarnessFactory agentHarnessFactory, int hostPort) throws Exception {
        try (AgentHarness harness = agentHarnessFactory.connect(hostPort)) {
            var session = harness.createSession();

            System.out.println("Sending message");
            var done = new CompletableFuture<Void>();
            session.onMessage(content -> System.out.println("Response: " + content));
            session.onIdle(() -> done.complete(null));

            session.sendPrompt(EXAMPLE_PROMPT);
            done.get();

            System.out.println("Example message completed successfully");
        }
    }

}
