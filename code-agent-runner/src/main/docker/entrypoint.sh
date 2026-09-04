#!/usr/bin/env bash
set -euo pipefail

WORKSPACE_DIR="${WORKSPACE_DIR:-/workspace}"
TARGET_DIR="${WORKSPACE_DIR}"

if [ -n "${GIT_REPO_URL:-}" ]; then
    # Authenticate https://github.com/ URLs with the injected token (private repos).
    if [ -n "${GH_TOKEN:-}" ]; then
        git config --global url."https://x-access-token:${GH_TOKEN}@github.com/".insteadOf "https://github.com/"
    fi

    REPO_NAME=$(basename "${GIT_REPO_URL%%.git}")
    TARGET_DIR="${WORKSPACE_DIR}/${REPO_NAME}"

    if [ -d "${TARGET_DIR}/.git" ]; then
        echo "Updating existing clone in ${TARGET_DIR}"
        git -C "${TARGET_DIR}" pull --ff-only || true
    else
        echo "Cloning ${GIT_REPO_URL} into ${TARGET_DIR}"
        clone_args=(--depth 1)
        if [ -n "${GIT_REF:-}" ]; then
            clone_args+=(--branch "${GIT_REF}")
        fi
        git clone "${clone_args[@]}" "${GIT_REPO_URL}" "${TARGET_DIR}"
    fi
fi

# The Copilot CLI server treats its working directory as the project root.
cd "${TARGET_DIR}"
exec copilot "$@"
