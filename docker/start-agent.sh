#!/usr/bin/env bash
# Starts the GitHub Copilot CLI in headless/server mode inside the agent container.
#
# The gateway (running as a JVM on the VPS host, outside this container) connects to this process
# remotely using the Copilot SDK for Java's RuntimeConnection.forUri("127.0.0.1:<host-port>")
# mechanism, where <host-port> is the unique loopback-only port this container's COPILOT_PORT is
# published on by ContainerManager. This script never spawns an interactive terminal session and
# this container never runs Copilot CLI on the gateway's behalf in-process — the CLI here is the
# one and only running instance that the gateway attaches to.
#
# Configuration (environment variables):
#   COPILOT_PORT           TCP port the headless CLI listens on inside the container
#                          (default: 4321). Docker publishes it on 127.0.0.1:<host-port>.
#   COPILOT_GITHUB_TOKEN   GitHub token used by the CLI for Copilot auth (see docker/README in the
#                          repository root "Authentication" section). Do not bake this into the
#                          image; it must be supplied at container-creation time.
set -euo pipefail

COPILOT_PORT="${COPILOT_PORT:-4321}"

echo "Starting Copilot CLI (headless) on port ${COPILOT_PORT}"

exec copilot --headless --port "${COPILOT_PORT}"
