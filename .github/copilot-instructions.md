# Copilot instructions for code-agent (cloud-agent-gateway)

Service that lets developers run coding and AI agent code-generation, both on a local laptop and on a cloud VPS, from a single deployable artifact. See [README.md](../README.md).

## Module map (Maven multi-module, root [pom.xml](../pom.xml))
- Root `pom.xml` is a `pom`-packaged parent/aggregator (shared Spring Boot BOM, Java version, plugin management) with a single child module today.
- `gateway-app` — the only module: Spring Boot entrypoint (`GatewayApplication`) plus the `AgentHarness`/`AgentSession` abstraction and its [Copilot Java SDK](https://github.github.com/copilot-sdk-java/1.0.13-preview.1/) implementation (`com.teggr.codeagent.agent.copilot`), kept together deliberately — split the harness into its own module only once a real second SDK is added. Requires the Copilot CLI on `PATH` at runtime. Still a preview dependency — check for newer versions before upgrading.

If/when a second agent SDK is added, extract `AgentHarness`/`AgentSession` into their own module first so other modules never depend on SDK-specific types directly.

## Local vs cloud execution model
One artifact, selected via Spring profile:
- `application.yml` — shared defaults (e.g. `codeagent.agent.harness`).
- `application-local.yml` — laptop/dev profile.
- `application-cloud.yml` — VPS/deployment profile.

Which `AgentHarness` bean is active is controlled by `codeagent.agent.harness` (see [AgentHarnessConfig.java](../gateway-app/src/main/java/com/teggr/codeagent/gateway/config/AgentHarnessConfig.java)), not by profile — profile only affects things like logging/server config.

## Build & test
- `./mvnw -q verify` — builds all modules and runs tests (Windows: `mvnw.cmd`).
- Run locally: `java -jar gateway-app/target/gateway-app.jar --spring.profiles.active=local`
- Container build for VPS: see [Dockerfile](../Dockerfile) (uses `cloud` profile by default).

## Conventions
- Java 21 language level (`maven.compiler.release` in root pom), even though the local JDK may be newer.
- Spring Boot version is managed once in the root pom's `dependencyManagement` (BOM import) — module poms don't redeclare it.
