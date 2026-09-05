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

Skip the image build (e.g. on a machine without Docker) with `-Ddocker.skip=true`. Pin the CLI with
`-Dcopilot-cli.version=v0.0.369` and Mise with `-Dmise.version=2025.1.3`.

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

The container starts `copilot --server --port 4321`, which `CopilotClientOptions.setCliUrl` in
`code-agent-app` connects to.
