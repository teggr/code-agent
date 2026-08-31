#!/usr/bin/env bash
# Builds and runs the Cloud Agent Gateway on the local host/VPS.
#
# Required environment (see README.md "Authentication"):
#   COPILOT_GITHUB_TOKEN   GitHub token forwarded into agent containers for Copilot auth.
#
# Optional environment:
#   CLOUD_AGENT_DB_PATH          Path to the SQLite metadata database (default ./data/cloud-agent-gateway.db)
#   CLOUD_AGENT_WORKSPACE_ROOT   Host directory for per-session workspaces (default ./data/workspaces)
#   SERVER_PORT                  Gateway HTTP port (default 8080)
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

mkdir -p "${REPO_ROOT}/data/workspaces"

cd "${REPO_ROOT}"
echo "Building gateway jar..."
./mvnw -q -DskipTests package 2>/dev/null || mvn -q -DskipTests package

JAR="$(ls target/cloud-agent-gateway.jar 2>/dev/null || ls target/*.jar | head -n1)"

echo "Starting gateway (${JAR})..."
exec java -jar "${JAR}"
