#!/usr/bin/env bash
set -euo pipefail

if [ "$(id -u)" = "0" ]; then
    chown -R agent:agent "${XDG_STATE_HOME:-/mise/state}" "${MISE_CONFIG_DIR:-/mise/config}" "${MISE_DATA_DIR:-/mise/data}" "${MISE_CACHE_DIR:-/mise/cache}"

    # Grant the agent user access to a bind-mounted host Docker socket (DooD). The socket's
    # group id varies per host (the host's docker group on Linux, root on Docker Desktop), so
    # map whatever GID the socket carries onto a group the agent user belongs to.
    if [ -S /var/run/docker.sock ]; then
        DOCKER_SOCK_GID=$(stat -c %g /var/run/docker.sock)
        if getent group "${DOCKER_SOCK_GID}" > /dev/null 2>&1; then
            DOCKER_SOCK_GROUP=$(getent group "${DOCKER_SOCK_GID}" | cut -d: -f1)
        else
            DOCKER_SOCK_GROUP=docker-host
            groupadd --gid "${DOCKER_SOCK_GID}" "${DOCKER_SOCK_GROUP}"
        fi
        usermod --append --groups "${DOCKER_SOCK_GROUP}" agent
    fi

    exec gosu agent "$0" "$@"
fi

WORKSPACE_DIR="${WORKSPACE_DIR:-/workspace}"
TARGET_DIR="${WORKSPACE_DIR}"

fail() {
    echo "ERROR: $*" >&2
    exit 1
}

github_api() {
    curl --fail --silent --show-error \
        --header "Accept: application/vnd.github+json" \
        --header "Authorization: Bearer ${GH_TOKEN}" \
        "${GITHUB_API_URL:-https://api.github.com}/$1"
}

if [ -n "${GIT_REPO_URL:-}" ]; then
    if [ -z "${GH_TOKEN:-}" ]; then
        fail "GH_TOKEN is required when GIT_REPO_URL is set"
    fi

    if [[ ! "${GIT_REPO_URL}" =~ ^https://github\.com/([A-Za-z0-9][A-Za-z0-9-]*)/([A-Za-z0-9._-]+)(\.git)?$ ]]; then
        fail "GIT_REPO_URL must be https://github.com/<owner>/<repository>[.git]"
    fi

    REPO_OWNER="${BASH_REMATCH[1]}"
    REPO_NAME="${BASH_REMATCH[2]}"
    REPO_NAME="${REPO_NAME%.git}"
    TARGET_DIR="${WORKSPACE_DIR}/${REPO_NAME}"

    # This lookup is the authorisation check: GitHub only returns the repository when GH_TOKEN is
    # entitled to it, for every token type. Owner and org-membership checks add nothing on top and
    # are impossible for fine-grained tokens, which cannot read /user/memberships/orgs.
    REPOSITORY=$(github_api "repos/${REPO_OWNER}/${REPO_NAME}") \
        || fail "GH_TOKEN cannot access repository ${REPO_OWNER}/${REPO_NAME}"
    echo "Authorised for $(jq --raw-output '.full_name // empty' <<<"${REPOSITORY}")"

    # Authenticate https://github.com/ URLs with the injected token (private repos).
    git config --global url."https://x-access-token:${GH_TOKEN}@github.com/".insteadOf "https://github.com/"

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

if [ -f mise.toml ] || [ -f .mise/config.toml ] || [ -f .tool-versions ]; then
    echo "Installing tools declared by the repository's Mise configuration"
    mise trust
    mise install
    exec mise exec -- copilot "$@"
fi

exec copilot "$@"
