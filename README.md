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
`-Dcopilot-cli.version=v0.0.369`.

Run it, supplying a token with the Copilot Requests permission:

```text
docker run --rm -p 4321:4321 -e GH_TOKEN=<token> -v ${PWD}:/workspace teggr/code-agent-runner
```

Set `GIT_REPO_URL` to clone a repository into `/workspace` on startup; the CLI server then runs
with the clone as its working directory. `GIT_REF` optionally selects a branch or tag. Clones are
shallow (`--depth 1`), and an existing clone in a mounted `/workspace` volume is updated with
`git pull --ff-only` instead of being re-cloned. `GH_TOKEN` also authenticates the clone for
private `https://github.com/` URLs.

```text
docker run --rm -p 4321:4321 -e GH_TOKEN=<token> -e GIT_REPO_URL=https://github.com/teggr/j2html-toolkit teggr/code-agent-runner
```

The container starts `copilot --server --port 4321`, which `CopilotClientOptions.setCliUrl` in
`code-agent-app` connects to.
