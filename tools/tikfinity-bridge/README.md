# TikFinity → Minecraft RCON bridge (ChunkChange server)

A small local Node.js service. It listens on `127.0.0.1:4567`, speaks ServerTap-style routes, and forwards each command
verbatim to the Fabric server's RCON on `127.0.0.1:25577`. It has no npm dependencies, so there is nothing to install beyond Node.js 18+.

## Start it

1. Start the Minecraft server (RCON must be on, which it is in `server\server.properties`).
2. Double-click **`START-BRIDGE.bat`** in the project root. It runs an RCON check, then starts the bridge and keeps the window open.
3. Leave that window open during the stream. Ctrl+C stops it.

## TikFinity values

Setup → **Minecraft Connection**:

| Field | Value |
|---|---|
| IP | `127.0.0.1` |
| Port | `4567` |
| Password / key | the value printed by `node bridge.js --show-connection` (run in `tools\tikfinity-bridge`) |

The key is generated on first run and saved to `tools\tikfinity-bridge\bridge.local.json`. That file is git-ignored.
Don't paste it anywhere public. The RCON password is never copied. The bridge reads it from `server\server.properties` at startup.

## Action commands (unchanged from `docs\TIKFINITY_ACTIONS.md`)

Type the command without `/`. TikFinity must replace `{username}`. The bridge sends the text as written.

```
chunkchange run nuke {username}
chunkchange run lava_chunk {username} below=10
rng spin {username} gift=Galaxy value=1000
```

For `CantSleepZane`, the target is whatever TikFinity's `{username}` resolves to. Test with a real viewer name or type the name directly.
The bridge never rewrites arguments. A leftover literal `{username}` is logged as a warning and forwarded unchanged.

## Diagnostics

| Check | How |
|---|---|
| Bridge up? | `GET http://127.0.0.1:4567/v1/ping` returns `{"ok":true,"message":"pong!",...}` |
| RCON reachable? | `node bridge.js --check` (or `npm run check`) |
| Who's online? | `GET /v1/players` with the key header. Returns `targetOnline` for `CantSleepZane`. |
| Health and counters | `GET /bridge/status` (no key; no secrets in the output) |
| What happened | `logs\bridge.log` (commands and results; never the key or password) |

Auth: send the key in the header `x-servertap-key: <key>`. `Authorization: Bearer <key>` and a `key` form field also work.

Common errors:

| HTTP | Meaning |
|---|---|
| 401 | Missing or wrong key |
| 403 | Command is on the block list (`stop`, `op`, `deop`, `reload`, `whitelist`, `ban-ip`, `pardon-ip`) |
| 429 | Over the rate limit (4 per second, burst 8). `Retry-After` says when to retry. |
| 400 | Empty, multi-line, or too-long command |
| 503 | RCON unreachable. The server is off, or RCON is disabled. The bridge retries with backoff (1 s → 30 s). |
| 504 | RCON did not answer within 5 s |

Port 4567 already in use? The bridge says so and exits. Find the owner with `netstat -ano | findstr :4567`.

## Settings

`bridge.config.json` holds non-secret settings: listen address and port, rate limit, command length limit, the block list, and `targetPlayer`.
Restart the bridge after editing it.

## Tests

```
npm test        (or: node --test test/bridge.test.js)
```

The 20 integration tests use a fake RCON server and an ephemeral port. They never touch the real game. They cover RCON framing, auth failure, verbatim forwarding of ChunkChange commands, auth/block/rate-limit rules, 503 handling, and reconnect after a server restart.

## Known limits

- **TikFinity's handshake is not verified.** The bridge implements the ServerTap routes and header auth that the ServerTap jar defines. I could not confirm which request TikFinity's *Connection test* sends, or whether TikFinity expects ServerTap's exact response bodies. Use the test in TikFinity and check `logs\bridge.log` to see what arrived.
- **ServerTap itself is not in use.** `ServerTap-0.5.4-SNAPSHOT.jar` is a Paper/Bukkit plugin. It cannot load on the Fabric server, and nothing else was changed to make it work.
- Only `list` is needed for the read routes. It is the only command the bridge sends on its own.
