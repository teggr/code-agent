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

The container starts `copilot --server --port 4321`, which `CopilotClientOptions.setCliUrl` in
`code-agent-app` connects to.
