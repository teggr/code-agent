package com.teggr.codeagent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import com.teggr.codeagent.runner.Runner;
import com.teggr.codeagent.runner.RunnerManager;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CodeAgentApplication {

    private static final String DEMO_REPO_URL = "https://github.com/teggr/j2html-toolkit";

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
    ApplicationRunner applicationRunner(ApplicationContext context, RunnerManager runnerManager) {
        return args -> {
            System.out.println("Starting application with Docker containers...");

            var runnersStopped = new AtomicBoolean();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (runnersStopped.compareAndSet(false, true)) {
                    runnerManager.stopAll();
                }
            }));

            int exitCode = 0;
            try {
                // Launch two runners for the same repo to demonstrate multiple concurrent runners per repository.
                Runner first = runnerManager.start(DEMO_REPO_URL);
                logRunnerStarted(first);
                Runner second = runnerManager.start(DEMO_REPO_URL);
                logRunnerStarted(second);

                runAgentPrompt(first);
                runAgentPrompt(second);

            } catch (Exception e) {
                System.err.println("Error during startup: " + e.getMessage());
                e.printStackTrace();
                exitCode = 1;
            } finally {
                if (runnersStopped.compareAndSet(false, true)) {
                    runnerManager.stopAll();
                }
            }

            System.out.println("Run finished; shutting down with exit code " + exitCode);
            int finalExitCode = exitCode;
            System.exit(SpringApplication.exit(context, () -> finalExitCode));
        };
    }

    private void logRunnerStarted(Runner runner) {
        System.out.println("Runner [" + runner.id() + "] started: repo=" + runner.repoUrl()
                + ", container=" + runner.containerLaunch().containerId()
                + ", hostPort=" + runner.containerLaunch().hostPort());
    }

    private void runAgentPrompt(Runner runner) throws Exception {
        var session = runner.harness().createSession();

        System.out.println("Sending message to runner " + runner.id());
        var done = new CompletableFuture<Void>();
        session.onMessage(content -> System.out.println("Response [" + runner.id() + "]: " + content));
        session.onIdle(() -> done.complete(null));

        session.sendPrompt(EXAMPLE_PROMPT);
        done.get();

        System.out.println("Runner " + runner.id() + " completed successfully");
    }

}
