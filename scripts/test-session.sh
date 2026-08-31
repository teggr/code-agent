#!/usr/bin/env bash
# End-to-end smoke test for the Cloud Agent Gateway prototype, using only curl.
#
# Demonstrates: creating a session (which creates+starts a container and attaches to its headless
# Copilot CLI), sending a prompt, listing sessions, and stopping the session.
#
# Usage:
#   ./scripts/test-session.sh [gateway-base-url] [agent-type]
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
AGENT_TYPE="${2:-default}"

echo "== Creating session (agentType=${AGENT_TYPE}) =="
CREATE_RESPONSE="$(curl -sS -X POST "${BASE_URL}/api/sessions" \
  -H 'Content-Type: application/json' \
  -d "{\"agentType\": \"${AGENT_TYPE}\"}")"
echo "${CREATE_RESPONSE}"

SESSION_ID="$(echo "${CREATE_RESPONSE}" | sed -n 's/.*"sessionId":"\([^"]*\)".*/\1/p')"
if [[ -z "${SESSION_ID}" ]]; then
  echo "Failed to extract sessionId from response" >&2
  exit 1
fi
echo "Session ID: ${SESSION_ID}"

echo
echo "== Sending a prompt =="
curl -sS -X POST "${BASE_URL}/api/sessions/${SESSION_ID}/prompt" \
  -H 'Content-Type: application/json' \
  -d '{"prompt": "Say hello in one short sentence."}'
echo

echo
echo "== Listing sessions =="
curl -sS "${BASE_URL}/api/sessions"
echo

echo
echo "== Fetching session detail =="
curl -sS "${BASE_URL}/api/sessions/${SESSION_ID}"
echo

echo
echo "(Optional) stream agent events with: websocat ${BASE_URL/http/ws}/ws/sessions/${SESSION_ID}"

echo
echo "== Stopping session =="
curl -sS -X DELETE "${BASE_URL}/api/sessions/${SESSION_ID}" -w '\nHTTP %{http_code}\n'
