#!/usr/bin/env bash
set -euo pipefail

SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Defaults to the OpenClaw profile that has xAI OAuth configured.
OPENCLAW_PROFILE_DIR="${OPENCLAW_PROFILE_DIR:-$HOME/.openclaw-winston}"
AUTH_STORE="${XAI_AUTH_STORE:-$OPENCLAW_PROFILE_DIR/agents/main/agent/auth-profiles.json}"
CONFIG_PATH="${OPENCLAW_CONFIG_PATH:-$OPENCLAW_PROFILE_DIR/openclaw.json}"
XAI_AUTH_PROFILE_ID="${XAI_AUTH_PROFILE_ID:-}"

NODE_BIN_DIR="${NODE_BIN_DIR:-$HOME/.local/share/fnm/node-versions/v24.11.1/installation/bin}"
PORT="${PORT:-8080}"

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required to read OpenClaw auth files" >&2
  exit 1
fi

if [[ ! -f "$AUTH_STORE" ]]; then
  echo "OpenClaw auth store not found: $AUTH_STORE" >&2
  exit 1
fi

if [[ ! -f "$CONFIG_PATH" ]]; then
  echo "OpenClaw config not found: $CONFIG_PATH" >&2
  exit 1
fi

profile_filter='
  .profiles as $profiles
  | if ($id | length) > 0 then
      $profiles[$id]
    else
      ($profiles | to_entries | map(select(.key | startswith("xai:"))) | first | .value)
    end
'

export XAI_OAUTH_REFRESH_TOKEN="${XAI_OAUTH_REFRESH_TOKEN:-$(jq -er --arg id "$XAI_AUTH_PROFILE_ID" "$profile_filter | .refresh" "$AUTH_STORE")}"
export XAI_OAUTH_TOKEN_URL="${XAI_OAUTH_TOKEN_URL:-$(jq -er --arg id "$XAI_AUTH_PROFILE_ID" "$profile_filter | .tokenEndpoint // \"https://auth.x.ai/oauth2/token\"" "$AUTH_STORE")}"
export XAI_OAUTH_CLIENT_ID="${XAI_OAUTH_CLIENT_ID:-b1a00492-073a-47ea-816f-4c329264a828}"
export VISIONCLAW_AUTH_TOKEN="${VISIONCLAW_AUTH_TOKEN:-$(jq -er '.gateway.auth.password // .gateway.auth.token // .hooks.token // empty' "$CONFIG_PATH")}"
export XAI_AUTH_STORE="$AUTH_STORE"
export XAI_AUTH_PROFILE_ID
export PORT

openclaw_access_token="$(jq -r --arg id "$XAI_AUTH_PROFILE_ID" "$profile_filter | .access // empty" "$AUTH_STORE")"
openclaw_access_expires="$(jq -r --arg id "$XAI_AUTH_PROFILE_ID" "$profile_filter | .expires // empty" "$AUTH_STORE")"
if [[ -n "$openclaw_access_token" && -z "${XAI_OAUTH_ACCESS_TOKEN:-}" ]]; then
  export XAI_OAUTH_ACCESS_TOKEN="$openclaw_access_token"
fi
if [[ -n "$openclaw_access_expires" && -z "${XAI_OAUTH_ACCESS_TOKEN_EXPIRES_AT:-}" ]]; then
  export XAI_OAUTH_ACCESS_TOKEN_EXPIRES_AT="$openclaw_access_expires"
fi

echo "Starting VisionClaw Grok auth broker on 0.0.0.0:$PORT"
echo "Using OpenClaw profile dir: $OPENCLAW_PROFILE_DIR"

cd "$SERVER_DIR"
exec env PATH="$NODE_BIN_DIR:$PATH" npm start
