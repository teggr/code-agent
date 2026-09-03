# syntax=docker/dockerfile:1
FROM ubuntu:24.04

# "latest" or a release tag such as v0.0.369.
ARG COPILOT_CLI_VERSION=latest

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
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
    && chown agent:agent /workspace

USER agent
WORKDIR /workspace
VOLUME ["/workspace"]

# Authenticate at run time with COPILOT_GITHUB_TOKEN, GH_TOKEN or GITHUB_TOKEN.
# --host 0.0.0.0 is required: the CLI server binds to 127.0.0.1 by default,
# which is unreachable via Docker's published port (host traffic is delivered
# to the container's network interface, not its loopback).
EXPOSE 4321
ENTRYPOINT ["copilot"]
CMD ["--headless", "--host", "0.0.0.0", "--port", "4321"]
