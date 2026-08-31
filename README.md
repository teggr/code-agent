# Cloud Agent Gateway

A minimal prototype proving that a Java gateway (the control plane) can attach to a **headless
GitHub Copilot CLI** already running inside a Docker container (the agent runtime), send it
prompts, and stream back agent events — without the gateway ever spawning the CLI itself.

```
                         Client
                           |
                     HTTP / WebSocket
                           |
                           v
                  +-------------------+
                  |   Cloud Gateway   |
                  |                   |
                  | Java 21           |
                  | Spring Boot       |
                  | REST API          |
                  | WebSocket         |
                  | Session Manager   |
                  | Docker Manager    |
                  +---------+---------+
                            |
                    Copilot SDK connection (TCP, RuntimeConnection.forUri)
                            |
                            v
                  +-------------------+
                  | Docker Container  |
                  |                   |
                  | copilot --headless|
                  |                   |
                  | /workspace        |
                  | /agent/skills     |
                  | /agent/AGENTS.md  |
                  | git, gh           |
                  +-------------------+
```

The gateway runs as a plain JVM process on the VPS host, so it cannot resolve Docker DNS names.
Each agent container therefore publishes its Copilot port on a unique host port bound **only** to
`127.0.0.1`:

```
Gateway JVM -> 127.0.0.1:<unique-host-port> -> agent container:<copilot-port>
```

The port range is configurable (`cloud-agent.docker.host-port-range-start` / `-end`, default
45000-45999). Because the binding is loopback-only, the Copilot port is never reachable from
0.0.0.0, the VPS LAN, Tailscale or the internet. The shared Docker network (`cloud-agent-net` by
default) is kept for future container-to-container communication. The only externally reachable
service is the gateway itself.

## What this prototype proves

1. Docker container lifecycle (create/start/stop/remove) via `ContainerManager`.
2. Copilot CLI running headlessly inside the container (`docker/start-agent.sh`).
3. The Java gateway attaching to that already-running CLI instance via the official
   [GitHub Copilot SDK for Java](https://github.com/github/copilot-sdk) (`CopilotClientFactory`,
   using `RuntimeConnection.forUri(...)` — never spawning a CLI process itself).
4. Sending prompts to a Copilot session (`POST /api/sessions/{id}/prompt`).
5. Receiving/streaming agent events over WebSocket (`/ws/sessions/{id}`).
6. Multiple independent sessions, each with its own container, workspace, and Copilot conversation.
7. Persistent vs. ephemeral container lifecycle (`persistent` flag on session creation).
8. Reconnecting to an existing persistent agent (`POST /api/sessions/{id}/reconnect`, using the
   SDK's `resumeSession`).

## Repository structure

```
cloud-agent-gateway/
    README.md
    pom.xml
    src/main/java/io/cloudagent/gateway/
        agent/     AgentRegistry + agent-type configuration
        docker/    ContainerManager + Docker CLI wrapper
        copilot/   Copilot SDK connection factory
        session/   SessionManager, SQLite-backed metadata, event fan-out
        web/       REST controller, WebSocket handler, DTOs
    src/main/resources/
        application.yml
        schema.sql
        static/test.html   (manual test page — see below)
    docker/
        Dockerfile         generic, reusable agent runtime image
        start-agent.sh     launches `copilot --headless` inside the container
        AGENTS.md          baseline instructions for every agent
        skills/            reusable, project-agnostic agent skills
    scripts/
        build-agent-image.sh
        run-gateway.sh
        test-session.sh
```

## Technology

Java 21 · Spring Boot (Web, WebSocket, JDBC) · Maven · [`com.github:copilot-sdk-java`](https://central.sonatype.com/artifact/com.github/copilot-sdk-java)
· Docker CLI (via `ProcessBuilder`, no Docker Engine API client library needed) · SQLite
(`org.xerial:sqlite-jdbc`) for gateway session metadata.

No Kubernetes, Redis, PostgreSQL, Kafka, RabbitMQ, service mesh, or cloud databases — the whole
prototype runs on a single VPS with just the JVM and the Docker daemon.

## Agent container

`docker/Dockerfile` builds a **generic, reusable** agent runtime image (no project-specific source
is baked in): Debian base, the Copilot CLI (pinned via `COPILOT_VERSION` build arg), `git`, `gh`
(GitHub CLI), a non-root `agent` user, and `/agent/skills` + `/agent/AGENTS.md`. `/workspace` is
supplied at container-creation time as a bind mount managed by `ContainerManager` — it is **not**
baked into the image.

Build it with:

```bash
./scripts/build-agent-image.sh cloud-agent:latest 1.0.7
```

## Copilot CLI invocation

Inside the container, `docker/start-agent.sh` runs:

```bash
copilot --headless --port "${COPILOT_PORT:-4321}"
```

`COPILOT_PORT` is configurable per agent type in `application.yml`
(`cloud-agent.agents.<type>.copilot-port`) and passed into the container as an environment
variable by `ContainerManager`. That container port is published by `docker create --publish
127.0.0.1:<host-port>:<copilot-port>`, so it is reachable from the gateway process on the host
but from nowhere else.

The gateway connects using the SDK's external-server / backend-services pattern
(`RuntimeConnection.forUri("127.0.0.1:<host-port>")`, `mode(EMPTY)`), which is the mechanism the
SDK provides specifically for attaching to a CLI process someone else started and manages — see
`CopilotClientFactory`. It never sets `cliPath`/`cliArgs`/`autoStart(true)`, so the SDK cannot spawn
its own CLI process.

## Authentication

No credentials are baked into the image or committed to the repository. Pass Copilot/GitHub
authentication into agent containers via environment variables on the **gateway host** — they are
forwarded automatically by `ContainerManager` into every container it creates:

| Variable               | Purpose                                                          |
|-------------------------|-------------------------------------------------------------------|
| `COPILOT_GITHUB_TOKEN`  | GitHub token used by the Copilot CLI inside the container.       |
| `GH_TOKEN` / `GITHUB_TOKEN` | Fallbacks also forwarded, for `gh`/tooling compatibility.    |

Set these on the gateway process (e.g. via your shell, a systemd unit's `Environment=`, or Docker
secrets if you containerize the gateway itself) before starting it with `scripts/run-gateway.sh`.
Per-user/per-session tokens can also be layered on top by extending `SessionManager` to pass a
`gitHubToken` on `SessionConfig`, following the SDK's multi-tenant pattern.

## Running it

```bash
# 1. Build the reusable agent image
./scripts/build-agent-image.sh

# 2. Export the auth token the CLI needs
export COPILOT_GITHUB_TOKEN=gho_xxx

# 3. Build and run the gateway
./scripts/run-gateway.sh

# 4. Exercise the API end-to-end
./scripts/test-session.sh
```

Or open `src/main/resources/static/test.html` (served at `http://localhost:8080/test.html` once
the gateway is running) for a minimal manual test page covering session creation, prompting, and
live WebSocket event streaming.

## REST API

| Method | Path                              | Description                                   |
|--------|-----------------------------------|------------------------------------------------|
| POST   | `/api/sessions`                   | Create a session (`{ "agentType": "default", "persistent": false }`) |
| GET    | `/api/sessions`                   | List all known sessions                        |
| GET    | `/api/sessions/{id}`              | Get one session's metadata                      |
| POST   | `/api/sessions/{id}/prompt`       | Send a prompt, wait for the final assistant reply (`{ "prompt": "..." }`) |
| POST   | `/api/sessions/{id}/reconnect`    | Reattach to a persistent session's container/CLI |
| DELETE | `/api/sessions/{id}`              | Stop the session (removes the container unless persistent) |

WebSocket: connect to `/ws/sessions/{id}` to receive one JSON-encoded Copilot agent event per
message, streamed as they occur during prompt handling.

## Agent registry configuration

Agent types are declared in `application.yml`, no plugin system required:

```yaml
cloud-agent:
  agents:
    default:
      image: cloud-agent:latest
      copilot-port: 4321
      persistent: false
```

## Notes and limitations (prototype scope)

* `docker/Dockerfile` builds the Copilot CLI from a GitHub release tarball, since there is no
  official pre-built Copilot CLI image.
* Container/session state used for reconnect decisions (`ContainerManager.findBySession`) relies
  on Docker labels (`cloud-agent=true`, `cloud-agent-session=<id>`), per the spec.
* This is intentionally not a polished UI — see `scripts/test-session.sh` (curl) and
  `src/main/resources/static/test.html` (browser) for manual testing.
