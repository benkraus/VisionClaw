# VisionClaw Tailnet Grok Broker

This host is configured to run the sample VisionClaw signaling server as a Grok
auth broker on the tailnet.

## Current Endpoint

Use this URL in the VisionClaw app settings:

```text
https://winstons-mac-mini.tail5311f9.ts.net:8444/api/grok/token
```

The broker listens locally on port `8080`. Tailscale Serve publishes it over
HTTPS on tailnet port `8444`.

Set the app's **Auth Broker Token** to the OpenClaw gateway password from:

```text
~/.openclaw-winston/openclaw.json
```

The runner script reads xAI OAuth refresh credentials from:

```text
~/.openclaw-winston/agents/main/agent/auth-profiles.json
```

It also exports the current OpenClaw xAI access token as
`XAI_OAUTH_ACCESS_TOKEN` and its expiry as `XAI_OAUTH_ACCESS_TOKEN_EXPIRES_AT`.
The broker still prefers refresh-token auth, but this gives production a
short-lived access-token fallback if the refresh endpoint returns a transient
400 or 5xx. When refresh succeeds, the broker writes the rotated xAI access and
refresh tokens back to the same OpenClaw auth profile so restarts keep a fresh
fallback.

No bearer, refresh, or gateway tokens are stored in this repository.

## Useful Commands

Start the broker in a detached tmux session:

```bash
tmux new-session -d -s visionclaw-grok-broker 'cd "/Volumes/OWC Envoy Ultra/Documents/Code/visionclaw/samples/CameraAccess/server" && ./run-tailnet-broker.sh >> /tmp/visionclaw-grok-broker.log 2>&1'
```

Attach to the running broker:

```bash
tmux attach -t visionclaw-grok-broker
```

Stop the broker:

```bash
tmux kill-session -t visionclaw-grok-broker
```

Publish the local broker on the tailnet:

```bash
tailscale serve --bg --yes --https=8444 8080
```

Inspect Tailscale Serve routes:

```bash
tailscale serve status --json
```

Disable the tailnet proxy:

```bash
tailscale serve --https=8444 off
```

Smoke-test the broker without printing secrets:

```bash
TOKEN="$(jq -er '.gateway.auth.password // .gateway.auth.token // .hooks.token // empty' ~/.openclaw-winston/openclaw.json)"
curl -sS -H "Authorization: Bearer $TOKEN" \
  https://winstons-mac-mini.tail5311f9.ts.net:8444/api/grok/token \
  | jq '. as $o | {hasAccessToken:($o.accessToken | type=="string" and length>0), tokenType:$o.tokenType, expiresAt:$o.expiresAt, error:$o.error}'
```
