# Copilot instructions for code-agent

Service that lets developers run coding and AI agent code-generation, both on a local laptop and on a cloud VPS, from a single deployable artifact. See [README.md](../README.md).

## Module map (Maven multi-module, root [pom.xml](../pom.xml))
- Root `pom.xml` is a `pom`-packaged parent/aggregator (shared Spring Boot BOM, Java version, plugin management).
- `code-agent-app` — Spring Boot entrypoint (`CodeAgentApplication`, package `com.teggr.codeagent`). Requires the Copilot CLI on `PATH` at runtime via the `code-agent-runner` container. Package layout:
  - `agent` — SDK-agnostic abstraction: `AgentHarness` (a connected runtime, `AutoCloseable`, creates `AgentSession`s), `AgentSession` (register `onMessage`/`onIdle` callbacks before calling `sendPrompt`), `AgentHarnessFactory` (connects with retry), `AgentHarnessProperties` (`codeagent.agent.*`).
  - `agent.copilot` — the only `AgentHarness`/`AgentSession` implementation today, wrapping the [Copilot Java SDK](https://github.github.com/copilot-sdk-java/1.0.13-preview.1/) (`CopilotClient`/`CopilotSession`). Still a preview dependency — check for newer versions before upgrading.
  - `docker` — `DockerRunnerService` launches/stops the `code-agent-runner` container (Docker socket resolution, port allocation), `DockerRunnerProperties` (`codeagent.runner.*`), `DockerClientConfig` provides the `DockerClient` bean.
- `code-agent-runner` — `pom`-packaged, no Java sources. Builds the Docker image used to build code repositories (Ubuntu + Copilot CLI, served on port 4321) via `io.fabric8:docker-maven-plugin`. Deliberately standalone: it never packages the app jar and does not depend on `code-agent-app`; the only coupling is the port 4321 network contract.

If/when a second agent SDK is added, extract `agent`/`agent.copilot` into their own module first so other code never depends on SDK-specific types directly.

## Build & test
- `./mvnw -q verify` — builds all modules and runs tests (Windows: `mvnw.cmd`).
- Run locally: `java -jar code-agent-app/target/code-agent-app.jar` (requires `GH_TOKEN` in env and Docker running).

## Conventions
- Java 21 language level (`maven.compiler.release` in root pom), even though the local JDK may be newer.
- Spring Boot version is managed once in the root pom's `dependencyManagement` (BOM import) — module poms don't redeclare it.
