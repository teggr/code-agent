# Copilot instructions for code-agent

Service that lets developers run coding and AI agent code-generation, both on a local laptop and on a cloud VPS, from a single deployable artifact. See [README.md](../README.md).

## Module map (Maven multi-module, root [pom.xml](../pom.xml))
- Root `pom.xml` is a `pom`-packaged parent/aggregator (shared Spring Boot BOM, Java version, plugin management).
- `code-agent-app` — Spring Boot entrypoint (`CodeAgentApplication`, package `com.teggr.codeagent`). Package layout:
  - `agent` — hosted product domain: durable `Agent`, transient `AgentConnection`, persistent `AgentConversation`, independent lifecycle statuses, `WorkspaceSpec`/`GitRepositoryWorkspace`, and `AgentManager`. Lifecycle policy uses `codeagent.agent.*`; Git workspace credentials use `codeagent.agent.workspace.git.*`.
  - `agent.runtime` — infrastructure port and runtime-neutral provisioning/discovery values.
  - `agent.runtime.docker` — sole runtime adapter. `DockerAgentRuntime` owns container lifecycle, labels, ports, socket binding, and workspace access; `DockerAgentRuntimeProperties` uses `codeagent.agent.runtime.docker.*`.
  - `harness` — SDK-neutral `AgentHarness`, `HarnessSession`, protocol values, and `AgentHarnessFactory`.
  - `harness.copilot` — sole harness adapter, wrapping the [Copilot Java SDK](https://github.github.com/copilot-sdk-java/1.0.13-preview.1/). Credentials and connection settings use `codeagent.harness.copilot.*`.
  - `web` — repository picker and canonical `/agents/{agentId}/conversations/{conversationId}` server-rendered/SSE routes.
- `code-agent-runner` — retained infrastructure artifact name. This standalone `pom` module builds the Docker runtime image (Ubuntu + Copilot CLI on port 4321) via `io.fabric8:docker-maven-plugin`; it is not the hosted Agent domain model.

If a second provider is added, implement `AgentHarnessFactory` without exposing SDK-specific types to the Agent domain. Repository-backed `GitRepositoryWorkspace` is the only supported creation mode today.

## Build & test
- `./mvnw -q verify` — builds all modules and runs tests (Windows: `mvnw.cmd`).
- Run locally: `java -jar code-agent-app/target/code-agent-app.jar` (requires `GH_TOKEN` in env and Docker running).

## Conventions
- Java 21 language level (`maven.compiler.release` in root pom), even though the local JDK may be newer.
- Spring Boot version is managed once in the root pom's `dependencyManagement` (BOM import) — module poms don't redeclare it.
