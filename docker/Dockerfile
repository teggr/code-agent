# Cloud Agent Gateway — generic agent runtime image.
#
# This image is reusable across every agent/session: it bundles the GitHub Copilot CLI, git,
# the GitHub CLI, and a non-root "agent" user. Project-specific source code is never baked in —
# it is bind-mounted at container-creation time into /workspace (see ContainerManager).
#
# The container's only job is to run the Copilot CLI in headless/server mode so the gateway can
# attach to it remotely via the Copilot SDK. It does NOT run an interactive terminal session, and
# the Copilot port must never be published to the host/internet — only reachable on the
# cloud-agent-net Docker network (see scripts/build-agent-image.sh and the gateway's
# ContainerManager).
FROM debian:bookworm-slim

# Pin the Copilot CLI version explicitly; bump this when a newer CLI release is validated against
# the copilot-sdk-java version pinned in pom.xml.
ARG COPILOT_VERSION=1.0.7

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        wget \
        git \
        curl \
        gnupg \
    && ARCH=$(dpkg --print-architecture) \
    && case "${ARCH}" in \
        amd64) COPILOT_ARCH="x64" ;; \
        arm64) COPILOT_ARCH="arm64" ;; \
        *) echo "Unsupported architecture: ${ARCH}" && exit 1 ;; \
    esac \
    && wget -q "https://github.com/github/copilot-cli/releases/download/v${COPILOT_VERSION}/copilot-linux-${COPILOT_ARCH}.tar.gz" \
    && tar -xzf "copilot-linux-${COPILOT_ARCH}.tar.gz" \
    && mv copilot /usr/local/bin/copilot \
    && chmod +x /usr/local/bin/copilot \
    && rm "copilot-linux-${COPILOT_ARCH}.tar.gz" \
    && rm -rf /var/lib/apt/lists/*

# GitHub CLI is best-effort: useful for git-forge operations from inside the agent workspace, but
# not required for the gateway<->Copilot CLI connection this prototype demonstrates. Its install
# is isolated in its own layer so a transient failure to reach cli.github.com never blocks the
# (required) Copilot CLI install above.
RUN ( mkdir -p -m 755 /etc/apt/keyrings \
    && wget -qO- https://cli.github.com/packages/githubcli-archive-keyring.gpg > /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /etc/apt/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends gh \
    && rm -rf /var/lib/apt/lists/* ) \
    || echo "WARNING: gh CLI install skipped (registry unreachable); continuing without it"

RUN apt-get purge -y wget gnupg 2>/dev/null; apt-get autoremove -y; rm -rf /var/lib/apt/lists/*

# Non-root agent user, per spec.
RUN useradd --create-home --shell /bin/bash agent

RUN mkdir -p /agent/skills /workspace \
    && chown -R agent:agent /agent /workspace

COPY AGENTS.md /agent/AGENTS.md
COPY skills/ /agent/skills/
COPY start-agent.sh /usr/local/bin/start-agent.sh
RUN chmod +x /usr/local/bin/start-agent.sh && chown agent:agent /agent/AGENTS.md

USER agent
WORKDIR /workspace

# Informational only: the actual port is read from $COPILOT_PORT at runtime by start-agent.sh.
# ContainerManager publishes it on a unique 127.0.0.1:<host-port> mapping so the gateway JVM on
# the host can reach it; it is never exposed on 0.0.0.0 or any public interface.
EXPOSE 4321

ENTRYPOINT ["/usr/local/bin/start-agent.sh"]
