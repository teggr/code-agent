# cloud-agent-gateway

## Copilot CLI

To run the Copilot CLI as a server, use:

```text
copilot --server --port 4321
```

## Runner image

`code-agent-runner` builds a Docker image used to build code repositories. It is standalone from
`code-agent-app` — it contains only the Copilot CLI, not the application jar.

```text
mvnw.cmd verify -pl code-agent-runner
```

The Maven build uses Fabric8's Docker API integration instead of shelling out to `docker build`,
so the first visible step is creating `target/docker/.../docker-build.tar` before Docker receives
the build context.

Skip the image build for a fast Maven validation with `mvnw.cmd -pl code-agent-runner
"-Ddocker.skip=true" verify`. Pin the CLI with `-Dcopilot-cli.version=v0.0.369` and Mise with
`-Dmise.version=2025.1.3`. Pin Playwright with `-Dplaywright.version=1.62.1` and Playwright MCP
with `-Dplaywright-mcp.version=0.0.80`. Pin the VS Code standalone CLI with
`-Dvscode-cli.version=<version>` (defaults to `latest`). To speed up first attach from desktop
VS Code, the runner preinstalls the server for the latest VS Code CLI by default. Use
`"-Dvscode-server.commit=<desktop-commit>"` in PowerShell to target a specific desktop build, or
`"-Dvscode-server.commit=none"` to skip the server preinstall.

Run it, supplying a token with the Copilot Requests permission:

```text
docker run --rm -p 4321:4321 -e GH_TOKEN=<token> -v ${PWD}:/workspace teggr/code-agent-runner
```

Set `GIT_REPO_URL` to clone a repository into `/workspace` on startup; the CLI server then runs
with the clone as its working directory. `GIT_REF` optionally selects a branch or tag. Clones are
shallow (`--depth 1`), and an existing clone in a mounted `/workspace` volume is updated with
`git pull --ff-only` instead of being re-cloned. `GH_TOKEN` also authenticates the clone for
private `https://github.com/` URLs. The repository URL must be in the form
`https://github.com/<owner>/<repository>[.git]`; the runner verifies that the authenticated user
owns the repository or has active membership in its owning organization before cloning.

The runner includes [Mise](https://mise.jdx.dev/) for repository-pinned developer tools. When the
repository root contains `mise.toml`, `.mise/config.toml`, or `.tool-versions`, it runs
`mise install` before starting Copilot, then starts Copilot through `mise exec`. Provisioning
failures prevent the server from starting. Repositories without one of those files start with only
the runner's base tools.

Persist the Mise cache separately from the source checkout by supplying a named volume or bind
mount for `/mise`:

```text
docker run --rm -p 4321:4321 -e GH_TOKEN=<token> -e GIT_REPO_URL=https://github.com/teggr/j2html-toolkit -v code-agent-mise:/mise teggr/code-agent-runner
```

For example, `j2html-toolkit` declares Java 21 and Maven 3.9:

```toml
[tools]
java = "21"
maven = "3.9"
```

After the container starts, commands run by Copilot resolve those versions. Its Maven build can
therefore use either `mvn -version` or the repository wrapper:

```text
./mvnw --no-transfer-progress test
```

## Browser automation

The image bakes in Node.js, system Google Chrome, and Playwright, matching the browser automation
setup on the [cloudagent VPS](https://github.com/teggr/cloudagent/blob/main/environment.md#browser-automation).
A `playwright` MCP server is registered on startup via Copilot's `--additional-mcp-config` flag,
pointing at `/etc/code-agent/mcp-config.json`, which launches `@playwright/mcp` against the system
Chrome executable (`/opt/google/chrome/chrome`) instead of a Playwright-managed browser download.

The container starts `copilot --server --port 4321`, which `CopilotClientOptions.setCliUrl` in
`code-agent-app` connects to.

## Docker inside the runner

The runner image includes the Docker CLI, buildx, and the compose plugin (no daemon), so projects
can build images, run local services, or use Testcontainers. The daemon is the host's own, reached
through a bind-mounted `/var/run/docker.sock` (Docker-outside-of-Docker).

When `code-agent-app` launches the runner it mounts the socket automatically. Detection is based
on the daemon's own report: Docker Desktop (Windows/macOS) mounts its Linux VM socket, and a
native Linux host (e.g. the VPS) mounts the real socket. If no usable socket is found — for
example Docker Desktop in Windows-container mode — the launch fails with a clear error. Docker
Desktop must be in Linux-container mode.

Running the image directly, mount the socket yourself:

```text
docker run --rm -p 4321:4321 -e GH_TOKEN=<token> -v /var/run/docker.sock:/var/run/docker.sock teggr/code-agent-runner
```

At startup the entrypoint maps the socket's group onto the non-root `agent` user, so `docker`
commands work without root inside the container. The socket's group id varies per host: the
`docker` group on Linux, root on Docker Desktop.

Pin the CLI with `-Ddocker-cli.version=5:29.7.2-1~ubuntu.24.04~noble` (the default `latest`
tracks Docker's apt repository).

Two caveats:

- The mounted socket grants root-equivalent control of the host Docker daemon. Only run
  repositories you trust, exactly as if you gave them Docker access on the host directly.
- Containers started from inside the runner are siblings on the host daemon, so relative bind
  mounts in a project's compose file resolve against host paths, not the runner's `/workspace`.
  Named volumes, networks, image builds, and published ports work as usual.

## Opening the workspace in VS Code

You can attach to the container's `/workspace` in VS Code either locally or remotely:

### Local container attach (Dev Containers)

When `code-agent-app` launches the runner container, it logs the direct VS Code attach link and CLI command:

```text
Open workspace in VS Code (attached container):
  CLI: code --new-window --folder-uri vscode-remote://attached-container+<hex-encoded-short-id>/workspace
  URL: vscode://vscode-remote/attached-container+<hex-encoded-short-id>/workspace?windowId=_blank
```

Opening the URL or running the CLI command attaches VS Code directly to the container using the
**Dev Containers** (`ms-vscode-remote.remote-containers`) extension.

### Remote container access (VS Code Tunnels)

The runner image bakes in the standalone VS Code CLI (`code`). When running on a remote cloud VPS,
you can start a secure tunnel from inside the container:

```bash
docker exec -it <container-id> code tunnel --accept-server-license-terms
```

You can then open the workspace from your desktop VS Code via the **Remote - Tunnels** extension,
or directly in your browser at `https://vscode.dev/tunnel/<tunnel-name>/workspace`.
