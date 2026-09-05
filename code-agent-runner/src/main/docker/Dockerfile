# syntax=docker/dockerfile:1
FROM ubuntu:24.04

# "latest" or a release tag such as v0.0.369.
ARG COPILOT_CLI_VERSION=latest
ARG MISE_VERSION

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        gosu \
        jq \
        openssh-client \
        unzip \
    && rm -rf /var/lib/apt/lists/*

# Installs the standalone copilot binary to /usr/local/bin (PREFIX defaults to /usr/local for root).
RUN if [ "$COPILOT_CLI_VERSION" = "latest" ]; then \
        curl -fsSL https://gh.io/copilot-install | bash; \
    else \
        curl -fsSL https://gh.io/copilot-install | VERSION="$COPILOT_CLI_VERSION" bash; \
    fi \
    && copilot --version

RUN useradd --create-home --uid 1001 agent \
    && mkdir -p /workspace \
    && mkdir -p /mise/config /mise/data /mise/cache /mise/state \
    && chown agent:agent /workspace /mise

ENV XDG_STATE_HOME=/mise/state \
    MISE_CONFIG_DIR=/mise/config \
    MISE_DATA_DIR=/mise/data \
    MISE_CACHE_DIR=/mise/cache

RUN curl -fsSL "https://github.com/jdx/mise/releases/download/v${MISE_VERSION}/mise-v${MISE_VERSION}-linux-x64.tar.gz" \
    | tar -xz --strip-components=2 -C /usr/local/bin mise/bin/mise \
    && mise --version

# Clones GIT_REPO_URL (if set) into /workspace and cd's into it before starting the CLI,
# so the server's project root is the cloned repository.
COPY src/main/docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

WORKDIR /workspace
VOLUME ["/workspace"]
VOLUME ["/mise"]

# Authenticate at run time with COPILOT_GITHUB_TOKEN, GH_TOKEN or GITHUB_TOKEN.
# --host 0.0.0.0 is required: the CLI server binds to 127.0.0.1 by default,
# which is unreachable via Docker's published port (host traffic is delivered
# to the container's network interface, not its loopback).
EXPOSE 4321
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["--headless", "--host", "0.0.0.0", "--port", "4321"]
