const http = require("http");
const fs = require("fs");
const path = require("path");
const { exec } = require("child_process");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 8080;
const rooms = new Map(); // roomCode -> { creator: ws, viewer: ws, destroyTimer: timeout|null }

// Grace period (ms) before destroying a room when creator disconnects.
// Allows the iOS user to switch apps (e.g. copy room code, send via WhatsApp) and come back.
const ROOM_GRACE_PERIOD_MS = 60_000;

// TURN: ExpressTURN (1000 GB/month free, reliable)
// Ports 3478 (standard), 80, 443 (firewall bypass)
const EXPRESSTURN_SERVER = process.env.EXPRESSTURN_SERVER || "free.expressturn.com";
const EXPRESSTURN_USER = process.env.EXPRESSTURN_USER || "efPU52K4SLOQ34W2QY";
const EXPRESSTURN_PASS = process.env.EXPRESSTURN_PASS || "1TJPNFxHKXrZfelz";

// Optional Grok OAuth broker. This lets the mobile apps avoid storing an xAI API key.
// Configure VISIONCLAW_AUTH_TOKEN, then either XAI_OAUTH_REFRESH_TOKEN +
// XAI_OAUTH_CLIENT_ID, XAI_OAUTH_TOKEN_COMMAND, or XAI_OAUTH_ACCESS_TOKEN.
// When both refresh and access tokens are present, refresh is preferred and the
// access token is used only as a short-lived fallback.
const VISIONCLAW_AUTH_TOKEN =
  process.env.VISIONCLAW_AUTH_TOKEN ||
  process.env.GROK_AUTH_BROKER_TOKEN ||
  process.env.OPENCLAW_GATEWAY_TOKEN ||
  "";
const XAI_OAUTH_TOKEN_URL =
  process.env.XAI_OAUTH_TOKEN_URL || "https://auth.x.ai/oauth2/token";
const XAI_OAUTH_CLIENT_ID = process.env.XAI_OAUTH_CLIENT_ID || "";
const XAI_OAUTH_CLIENT_SECRET = process.env.XAI_OAUTH_CLIENT_SECRET || "";
let xaiOAuthRefreshToken = process.env.XAI_OAUTH_REFRESH_TOKEN || "";
const XAI_OAUTH_ACCESS_TOKEN = process.env.XAI_OAUTH_ACCESS_TOKEN || "";
const XAI_OAUTH_ACCESS_TOKEN_EXPIRES_AT =
  process.env.XAI_OAUTH_ACCESS_TOKEN_EXPIRES_AT || "";
const XAI_OAUTH_TOKEN_COMMAND = process.env.XAI_OAUTH_TOKEN_COMMAND || "";
const XAI_AUTH_STORE = process.env.XAI_AUTH_STORE || "";
const XAI_AUTH_PROFILE_ID = process.env.XAI_AUTH_PROFILE_ID || "";
let cachedGrokAuth = null; // { accessToken, expiresAt }

function getTurnCredentials() {
  return {
    iceServers: [
      {
        urls: [
          `turn:${EXPRESSTURN_SERVER}:3478`,
          `turn:${EXPRESSTURN_SERVER}:3478?transport=tcp`,
          `turn:${EXPRESSTURN_SERVER}:80`,
          `turn:${EXPRESSTURN_SERVER}:80?transport=tcp`,
          `turns:${EXPRESSTURN_SERVER}:443?transport=tcp`,
        ],
        username: EXPRESSTURN_USER,
        credential: EXPRESSTURN_PASS,
      },
    ],
  };
}

function sendJSON(res, statusCode, body) {
  res.writeHead(statusCode, {
    "Content-Type": "application/json",
    "Cache-Control": "no-store",
    "Access-Control-Allow-Origin": "*",
  });
  res.end(JSON.stringify(body));
}

function isAuthorized(req) {
  if (!VISIONCLAW_AUTH_TOKEN) {
    return false;
  }
  const auth = req.headers.authorization || "";
  const bearer = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  const headerToken = req.headers["x-visionclaw-token"] || "";
  return bearer === VISIONCLAW_AUTH_TOKEN || headerToken === VISIONCLAW_AUTH_TOKEN;
}

function normalizeExpiresAt(value, fallbackSeconds = 300) {
  if (typeof value === "number" && Number.isFinite(value)) {
    const millis = value < 4_000_000_000 ? value * 1000 : value;
    return new Date(millis).toISOString();
  }
  if (typeof value === "string" && value.trim()) {
    const millis = Date.parse(value);
    if (!Number.isNaN(millis)) {
      return new Date(millis).toISOString();
    }
  }
  return new Date(Date.now() + fallbackSeconds * 1000).toISOString();
}

function isFresh(auth) {
  if (!auth || !auth.accessToken) {
    return false;
  }
  const expiresAt = Date.parse(auth.expiresAt || "");
  if (Number.isNaN(expiresAt)) {
    return true;
  }
  return expiresAt - Date.now() > 60_000;
}

function expiresAtFromJwt(token) {
  const payload = token.split(".")[1];
  if (!payload) {
    return null;
  }
  try {
    const json = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    if (typeof json.exp === "number" && Number.isFinite(json.exp)) {
      return json.exp * 1000;
    }
  } catch {
    return null;
  }
  return null;
}

function staticAccessTokenAuth() {
  if (!XAI_OAUTH_ACCESS_TOKEN) {
    return null;
  }
  const expiresAt =
    expiresAtFromJwt(XAI_OAUTH_ACCESS_TOKEN) ||
    XAI_OAUTH_ACCESS_TOKEN_EXPIRES_AT;
  return {
    accessToken: XAI_OAUTH_ACCESS_TOKEN,
    expiresAt: normalizeExpiresAt(expiresAt, 300),
  };
}

function resolveXaiAuthProfileId(store) {
  if (XAI_AUTH_PROFILE_ID) {
    return XAI_AUTH_PROFILE_ID;
  }
  return Object.keys(store.profiles || {}).find((key) => key.startsWith("xai:"));
}

function persistXaiOAuthCredential(auth) {
  if (!XAI_AUTH_STORE) {
    return;
  }
  try {
    const store = JSON.parse(fs.readFileSync(XAI_AUTH_STORE, "utf8"));
    const profileId = resolveXaiAuthProfileId(store);
    if (!profileId || !store.profiles?.[profileId]) {
      return;
    }

    const profile = store.profiles[profileId];
    profile.type = "oauth";
    profile.provider = "xai";
    profile.access = auth.accessToken;
    if (auth.refreshToken) {
      profile.refresh = auth.refreshToken;
    }
    if (auth.idToken) {
      profile.idToken = auth.idToken;
    }
    const expires = Date.parse(auth.expiresAt || "");
    if (!Number.isNaN(expires)) {
      profile.expires = expires;
    }
    profile.tokenEndpoint = XAI_OAUTH_TOKEN_URL;
    profile.issuer = profile.issuer || "https://auth.x.ai";

    const stat = fs.statSync(XAI_AUTH_STORE);
    const tmpPath = `${XAI_AUTH_STORE}.visionclaw.tmp`;
    fs.writeFileSync(tmpPath, `${JSON.stringify(store, null, 2)}\n`, {
      mode: stat.mode & 0o777,
    });
    fs.renameSync(tmpPath, XAI_AUTH_STORE);
  } catch (error) {
    console.warn(`[GrokAuth] failed to persist refreshed xAI OAuth profile: ${error.message}`);
  }
}

function parseCommandToken(stdout) {
  const output = String(stdout || "").trim();
  if (!output) {
    throw new Error("token command produced no output");
  }
  try {
    const json = JSON.parse(output);
    const accessToken = json.accessToken || json.access_token || json.token;
    if (!accessToken) {
      throw new Error("token command JSON did not include accessToken");
    }
    return {
      accessToken,
      expiresAt: normalizeExpiresAt(
        json.expiresAt || json.expires_at,
        Number(json.expiresIn || json.expires_in || 300)
      ),
    };
  } catch (error) {
    if (output.startsWith("{")) {
      throw error;
    }
    return {
      accessToken: output,
      expiresAt: normalizeExpiresAt(null, 300),
    };
  }
}

function runTokenCommand() {
  return new Promise((resolve, reject) => {
    exec(
      XAI_OAUTH_TOKEN_COMMAND,
      { timeout: 10_000, maxBuffer: 1024 * 1024 },
      (error, stdout, stderr) => {
        if (error) {
          reject(new Error(stderr.trim() || error.message));
          return;
        }
        try {
          resolve(parseCommandToken(stdout));
        } catch (parseError) {
          reject(parseError);
        }
      }
    );
  });
}

async function refreshXaiOAuthToken() {
  if (!xaiOAuthRefreshToken) {
    throw new Error("XAI_OAUTH_REFRESH_TOKEN is not configured");
  }
  if (!XAI_OAUTH_CLIENT_ID) {
    throw new Error("XAI_OAUTH_CLIENT_ID is not configured");
  }

  const body = new URLSearchParams();
  body.set("grant_type", "refresh_token");
  body.set("refresh_token", xaiOAuthRefreshToken);
  body.set("client_id", XAI_OAUTH_CLIENT_ID);
  if (XAI_OAUTH_CLIENT_SECRET) {
    body.set("client_secret", XAI_OAUTH_CLIENT_SECRET);
  }

  const response = await fetch(XAI_OAUTH_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`xAI OAuth refresh failed: HTTP ${response.status} ${text.slice(0, 200)}`);
  }
  const json = JSON.parse(text);
  if (!json.access_token) {
    throw new Error("xAI OAuth refresh did not return access_token");
  }
  if (json.refresh_token) {
    xaiOAuthRefreshToken = json.refresh_token;
  }
  const auth = {
    accessToken: json.access_token,
    refreshToken: xaiOAuthRefreshToken,
    idToken: json.id_token,
    expiresAt: normalizeExpiresAt(null, Number(json.expires_in || 300)),
  };
  persistXaiOAuthCredential(auth);
  return auth;
}

async function getGrokAuthorization() {
  if (isFresh(cachedGrokAuth)) {
    return cachedGrokAuth;
  }

  if (XAI_OAUTH_TOKEN_COMMAND) {
    cachedGrokAuth = await runTokenCommand();
    return cachedGrokAuth;
  }

  if (xaiOAuthRefreshToken) {
    try {
      cachedGrokAuth = await refreshXaiOAuthToken();
      return cachedGrokAuth;
    } catch (error) {
      const fallback = staticAccessTokenAuth();
      if (isFresh(fallback)) {
        console.warn(
          `[GrokAuth] xAI OAuth refresh failed; using access-token fallback: ${error.message}`
        );
        cachedGrokAuth = fallback;
        return cachedGrokAuth;
      }
      throw error;
    }
  }

  const fallback = staticAccessTokenAuth();
  if (isFresh(fallback)) {
    cachedGrokAuth = fallback;
    return cachedGrokAuth;
  }
  if (fallback) {
    throw new Error("XAI_OAUTH_ACCESS_TOKEN is expired");
  }

  cachedGrokAuth = await refreshXaiOAuthToken();
  return cachedGrokAuth;
}

// HTTP server for serving the web viewer
const httpServer = http.createServer((req, res) => {
  if (req.method === "OPTIONS") {
    res.writeHead(204, {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Headers": "Authorization, Content-Type, X-VisionClaw-Token",
      "Access-Control-Allow-Methods": "GET, OPTIONS",
    });
    res.end();
    return;
  }

  // TURN credentials API endpoint
  if (req.url === "/api/turn") {
    const creds = getTurnCredentials();
    sendJSON(res, 200, creds);
    return;
  }

  if (req.url === "/api/grok/token") {
    if (!VISIONCLAW_AUTH_TOKEN) {
      sendJSON(res, 503, {
        error: "VISIONCLAW_AUTH_TOKEN or GROK_AUTH_BROKER_TOKEN is not configured",
      });
      return;
    }
    if (!isAuthorized(req)) {
      sendJSON(res, 401, { error: "Unauthorized" });
      return;
    }

    getGrokAuthorization()
      .then((auth) => {
        sendJSON(res, 200, {
          accessToken: auth.accessToken,
          tokenType: "Bearer",
          expiresAt: auth.expiresAt,
        });
      })
      .catch((error) => {
        console.error(`[GrokAuth] ${error.message}`);
        sendJSON(res, 500, { error: error.message });
      });
    return;
  }

  let filePath = path.join(
    __dirname,
    "public",
    req.url === "/" ? "index.html" : req.url
  );

  const ext = path.extname(filePath);
  const contentTypes = {
    ".html": "text/html",
    ".js": "application/javascript",
    ".css": "text/css",
  };

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end("Not found");
      return;
    }
    res.writeHead(200, {
      "Content-Type": contentTypes[ext] || "text/plain",
    });
    res.end(data);
  });
});

// WebSocket signaling server
const wss = new WebSocketServer({ server: httpServer });

function generateRoomCode() {
  // No ambiguous chars (0/O, 1/I/L)
  const chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
  let code = "";
  for (let i = 0; i < 6; i++) {
    code += chars[Math.floor(Math.random() * chars.length)];
  }
  return code;
}

wss.on("connection", (ws, req) => {
  let currentRoom = null;
  let role = null; // 'creator' or 'viewer'
  const clientIP = req.headers["x-forwarded-for"] || req.socket.remoteAddress;
  console.log(`[WS] New connection from ${clientIP}`);

  ws.on("message", (data) => {
    let msg;
    try {
      msg = JSON.parse(data);
    } catch {
      return;
    }

    switch (msg.type) {
      case "create": {
        const code = generateRoomCode();
        rooms.set(code, { creator: ws, viewer: null, destroyTimer: null });
        currentRoom = code;
        role = "creator";
        ws.send(JSON.stringify({ type: "room_created", room: code }));
        console.log(`[Room] Created: ${code}`);
        break;
      }

      case "rejoin": {
        // Creator reconnects to an existing room (after app backgrounding)
        const room = rooms.get(msg.room);
        if (!room) {
          ws.send(
            JSON.stringify({ type: "error", message: "Room not found" })
          );
          return;
        }
        // Cancel the destroy timer since creator is back
        if (room.destroyTimer) {
          clearTimeout(room.destroyTimer);
          room.destroyTimer = null;
          console.log(`[Room] Creator rejoined, cancelled destroy timer: ${msg.room}`);
        }
        room.creator = ws;
        currentRoom = msg.room;
        role = "creator";
        ws.send(JSON.stringify({ type: "room_rejoined", room: msg.room }));
        // If viewer is already waiting, trigger a new offer
        if (room.viewer && room.viewer.readyState === 1) {
          ws.send(JSON.stringify({ type: "peer_joined" }));
          console.log(`[Room] Viewer already present, notifying rejoined creator: ${msg.room}`);
        }
        console.log(`[Room] Creator rejoined: ${msg.room}`);
        break;
      }

      case "join": {
        const room = rooms.get(msg.room);
        if (!room) {
          ws.send(
            JSON.stringify({ type: "error", message: "Room not found" })
          );
          return;
        }
        if (room.viewer) {
          ws.send(JSON.stringify({ type: "error", message: "Room is full" }));
          return;
        }
        room.viewer = ws;
        currentRoom = msg.room;
        role = "viewer";
        ws.send(JSON.stringify({ type: "room_joined" }));
        // Notify creator that viewer joined (only if creator is connected)
        if (room.creator && room.creator.readyState === 1) {
          room.creator.send(JSON.stringify({ type: "peer_joined" }));
        }
        console.log(`[Room] Viewer joined: ${msg.room}`);
        break;
      }

      // Relay SDP and ICE candidates to the other peer
      case "offer":
      case "answer":
      case "candidate": {
        const room = rooms.get(currentRoom);
        if (!room) {
          console.log(`[Relay] ${msg.type} from ${role} but room ${currentRoom} not found`);
          return;
        }
        const target = role === "creator" ? room.viewer : room.creator;
        if (target && target.readyState === 1) {
          target.send(JSON.stringify(msg));
          console.log(`[Relay] ${msg.type} from ${role} -> ${role === "creator" ? "viewer" : "creator"} (room ${currentRoom})`);
        } else {
          console.log(`[Relay] ${msg.type} from ${role} but target not ready (room ${currentRoom})`);
        }
        break;
      }
    }
  });

  ws.on("error", (err) => {
    console.log(`[WS] Error for ${role} in room ${currentRoom}: ${err.message}`);
  });

  ws.on("close", (code, reason) => {
    console.log(`[WS] Closed: ${role} in room ${currentRoom} (code=${code}, reason=${reason || "none"})`);

    if (currentRoom && rooms.has(currentRoom)) {
      const room = rooms.get(currentRoom);
      const otherPeer = role === "creator" ? room.viewer : room.creator;
      if (otherPeer && otherPeer.readyState === 1) {
        otherPeer.send(JSON.stringify({ type: "peer_left" }));
      }
      if (role === "creator") {
        // Don't destroy immediately -- give the creator a grace period to reconnect
        // (e.g. switching to WhatsApp to share the room code)
        room.creator = null;
        room.destroyTimer = setTimeout(() => {
          if (rooms.has(currentRoom)) {
            const r = rooms.get(currentRoom);
            // Only destroy if creator never came back
            if (!r.creator || r.creator.readyState !== 1) {
              if (r.viewer && r.viewer.readyState === 1) {
                r.viewer.send(JSON.stringify({ type: "error", message: "Stream ended" }));
              }
              rooms.delete(currentRoom);
              console.log(`[Room] Destroyed after grace period: ${currentRoom}`);
            }
          }
        }, ROOM_GRACE_PERIOD_MS);
        console.log(`[Room] Creator disconnected, grace period started (${ROOM_GRACE_PERIOD_MS / 1000}s): ${currentRoom}`);
      } else {
        room.viewer = null;
      }
    }
  });
});

httpServer.listen(PORT, "0.0.0.0", () => {
  console.log(`Signaling server running on http://0.0.0.0:${PORT}`);
  console.log(`Web viewer available at http://localhost:${PORT}`);
});
