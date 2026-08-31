#!/usr/bin/env bash
# Builds the generic Cloud Agent Docker image used by every gateway session.
#
# Usage:
#   ./scripts/build-agent-image.sh [image-tag] [copilot-version]
#
# Examples:
#   ./scripts/build-agent-image.sh
#   ./scripts/build-agent-image.sh cloud-agent:latest 1.0.7
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

IMAGE_TAG="${1:-cloud-agent:latest}"
COPILOT_VERSION="${2:-1.0.7}"

echo "Building agent image ${IMAGE_TAG} (Copilot CLI v${COPILOT_VERSION})"
docker build \
  --build-arg "COPILOT_VERSION=${COPILOT_VERSION}" \
  -t "${IMAGE_TAG}" \
  -f "${REPO_ROOT}/docker/Dockerfile" \
  "${REPO_ROOT}/docker"

echo "Built ${IMAGE_TAG}"
