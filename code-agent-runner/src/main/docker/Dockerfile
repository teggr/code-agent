# syntax=docker/dockerfile:1
FROM ubuntu:24.04

# "latest" or a release tag such as v0.0.369.
ARG COPILOT_CLI_VERSION=latest
ARG MISE_VERSION
ARG PLAYWRIGHT_VERSION
ARG PLAYWRIGHT_MCP_VERSION
# Docker client tools: "latest" or a version string such as 5:29.7.2-1~ubuntu.24.04~noble.
ARG DOCKER_CLI_VERSION=latest
# GitHub CLI: "latest" or a version string.
ARG GH_CLI_VERSION=latest
# VS Code standalone CLI: "latest" or a version string.
ARG VSCODE_CLI_VERSION=latest
# VS Code Server commit to preinstall for faster Dev Containers attach. Use "latest" to match the CLI.
ARG VSCODE_SERVER_COMMIT=latest

ENV DEBIAN_FRONTEND=noninteractive \
    TZ=Etc/UTC

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        gnupg \
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

# Node.js is a prerequisite for the Copilot CLI's own npm install path and for Playwright/npx.
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && node --version

# Docker client tools (CLI + buildx + compose, no dockerd) so projects in the runner can build
# images and run services against the host daemon, which is reached via a bind-mounted
# /var/run/docker.sock supplied at container launch.
RUN curl -fsSL https://download.docker.com/linux/ubuntu/gpg | gpg --dearmor -o /usr/share/keyrings/docker.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu noble stable" \
        > /etc/apt/sources.list.d/docker.list \
    && apt-get update \
    && if [ "$DOCKER_CLI_VERSION" = "latest" ]; then \
        apt-get install -y --no-install-recommends docker-ce-cli; \
    else \
        apt-get install -y --no-install-recommends "docker-ce-cli=${DOCKER_CLI_VERSION}"; \
    fi \
    && apt-get install -y --no-install-recommends docker-buildx-plugin docker-compose-plugin \
    && rm -rf /var/lib/apt/lists/* \
    && docker --version \
    && docker buildx version \
    && docker compose version

# GitHub CLI (gh) so the agent can inspect issues/PRs and perform GitHub operations natively.
RUN curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg \
    && chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg \
    && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
        > /etc/apt/sources.list.d/github-cli.list \
    && apt-get update \
    && if [ "$GH_CLI_VERSION" = "latest" ]; then \
        apt-get install -y --no-install-recommends gh; \
    else \
        apt-get install -y --no-install-recommends "gh=${GH_CLI_VERSION}"; \
    fi \
    && rm -rf /var/lib/apt/lists/* \
    && gh --version

# VS Code standalone CLI so developers can attach via VS Code Tunnels or manage the container.
RUN case "$(dpkg --print-architecture)" in \
        amd64) vscode_cli_arch=cli-alpine-x64 ;; \
        arm64) vscode_cli_arch=cli-alpine-arm64 ;; \
        *) echo "Unsupported architecture: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac \
    && if [ "$VSCODE_CLI_VERSION" = "latest" ]; then \
        curl -fsSL "https://code.visualstudio.com/sha/download?build=stable&os=${vscode_cli_arch}" \
            | tar -xz -C /usr/local/bin; \
    else \
        curl -fsSL "https://update.code.visualstudio.com/commit:${VSCODE_CLI_VERSION}/${vscode_cli_arch}/stable" \
            | tar -xz -C /usr/local/bin; \
    fi \
    && code --version

RUN case "$(dpkg --print-architecture)" in \
        amd64) vscode_server_arch=server-linux-x64 ;; \
        arm64) vscode_server_arch=server-linux-arm64 ;; \
        *) echo "Unsupported architecture: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac \
    && if [ "$VSCODE_SERVER_COMMIT" = "latest" ]; then \
        server_dir="$(mktemp -d)" \
    && curl -fsSL "https://update.code.visualstudio.com/latest/${vscode_server_arch}/stable" \
            | tar -xz --strip-components=1 -C "$server_dir" \
        && resolved_commit="$(jq -r .commit "$server_dir/product.json")" \
        && mkdir -p "/root/.vscode-server/bin/${resolved_commit}" \
        && mv "$server_dir"/* "/root/.vscode-server/bin/${resolved_commit}" \
        && rmdir "$server_dir"; \
    elif [ "$VSCODE_SERVER_COMMIT" != "none" ]; then \
        mkdir -p "/root/.vscode-server/bin/${VSCODE_SERVER_COMMIT}" \
        && curl -fsSL "https://update.code.visualstudio.com/commit:${VSCODE_SERVER_COMMIT}/${vscode_server_arch}/stable" \
            | tar -xz --strip-components=1 -C "/root/.vscode-server/bin/${VSCODE_SERVER_COMMIT}"; \
    fi

# Pre-installed globally so `npx` resolves them without a network call at container start.
# Browsers land in a shared location so the non-root `agent` user (below) can use them.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
RUN npm install -g "playwright@${PLAYWRIGHT_VERSION}" "@playwright/mcp@${PLAYWRIGHT_MCP_VERSION}" \
    && npx playwright --version \
    && npx playwright install --with-deps chromium \
    && chromium_bin="$(find "$PLAYWRIGHT_BROWSERS_PATH" -maxdepth 3 -type f -name chrome -path '*chrome-linux*')" \
    && "$chromium_bin" --version

RUN useradd --create-home --uid 1001 agent \
    && mkdir -p /workspace \
    && mkdir -p /mise/config /mise/data /mise/cache /mise/state \
    && chown -R agent:agent /workspace /mise /opt/playwright-browsers

ENV XDG_STATE_HOME=/mise/state \
    MISE_CONFIG_DIR=/mise/config \
    MISE_DATA_DIR=/mise/data \
    MISE_CACHE_DIR=/mise/cache

RUN case "$(dpkg --print-architecture)" in \
        amd64) mise_arch=linux-x64 ;; \
        arm64) mise_arch=linux-arm64 ;; \
        *) echo "Unsupported architecture: $(dpkg --print-architecture)" >&2; exit 1 ;; \
    esac \
    && curl -fsSL "https://github.com/jdx/mise/releases/download/v${MISE_VERSION}/mise-v${MISE_VERSION}-${mise_arch}.tar.gz" \
        | tar -xz --strip-components=2 -C /usr/local/bin mise/bin/mise \
    && mise --version

# Clones GIT_REPO_URL (if set) into /workspace and cd's into it before starting the CLI,
# so the server's project root is the cloned repository.
COPY src/main/docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

# MCP server registration baked into the image and passed to Copilot via --additional-mcp-config.
COPY src/main/docker/mcp-config.json /etc/code-agent/mcp-config.json

WORKDIR /workspace
VOLUME ["/workspace"]
VOLUME ["/mise"]

# Authenticate at run time with COPILOT_GITHUB_TOKEN, GH_TOKEN or GITHUB_TOKEN.
# --host 0.0.0.0 is required: the CLI server binds to 127.0.0.1 by default,
# which is unreachable via Docker's published port (host traffic is delivered
# to the container's network interface, not its loopback).
EXPOSE 4321
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["--headless", "--host", "0.0.0.0", "--port", "4321", "--additional-mcp-config", "@/etc/code-agent/mcp-config.json"]
