'use strict';

// HTTP front end that mimics the ServerTap 0.5.4 routes TikFinity uses, and forwards
// commands verbatim to Minecraft RCON.
//
// ServerTap facts this follows (read from the bundled ServerTap-0.5.4-SNAPSHOT.jar):
//   - auth header:   x-servertap-key: <key>   (also accepted: Authorization: Bearer <key>)
//   - exec route:    POST /v1/server/exec     (form field `command`; JSON also accepted here)
//   - ping route:    GET  /v1/ping            (no auth)
// The response bodies here are this bridge's own JSON, not a copy of ServerTap's.

const http = require('node:http');
const crypto = require('node:crypto');
const { RconError } = require('./rcon');

class TokenBucket {
  constructor({ perSecond, burst }, now = () => Date.now()) {
    this.rate = perSecond / 1000; // tokens per ms
    this.capacity = burst;
    this.tokens = burst;
    this.now = now;
    this.last = now();
  }

  /** Returns { ok: true } or { ok: false, retryAfterMs }. */
  take() {
    const t = this.now();
    this.tokens = Math.min(this.capacity, this.tokens + (t - this.last) * this.rate);
    this.last = t;
    if (this.tokens >= 1) {
      this.tokens -= 1;
      return { ok: true };
    }
    return { ok: false, retryAfterMs: Math.ceil((1 - this.tokens) / this.rate) };
  }
}

function safeEqual(a, b) {
  const ab = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (ab.length !== bb.length) {
    crypto.timingSafeEqual(ab, ab); // keep timing roughly constant
    return false;
  }
  return crypto.timingSafeEqual(ab, bb);
}

/** Normalizes a command from TikFinity. Keeps ChunkChange arguments byte-for-byte. */
function normalizeCommand(raw, maxLen) {
  if (typeof raw !== 'string') return { error: 'command must be a string' };
  let cmd = raw.trim();
  let slashStripped = false;
  if (cmd.startsWith('/')) {
    cmd = cmd.slice(1).trimStart();
    slashStripped = true;
  }
  if (!cmd) return { error: 'command is empty' };
  if (cmd.length > maxLen) return { error: `command longer than ${maxLen} characters` };
  if (/[\r\n\0]/.test(cmd)) return { error: 'command must be a single line' };
  return { command: cmd, slashStripped };
}

function rootOf(command) {
  return command.split(/\s+/)[0].toLowerCase();
}

function createBridge({ config, rcon, logger, now }) {
  const log = logger || (() => {});
  const bucket = new TokenBucket(config.rateLimit, now);
  const blocked = new Set(config.blockedRootCommands.map((c) => c.toLowerCase()));
  const counters = { requests: 0, commands: 0, rejected: { auth: 0, rateLimit: 0, blocked: 0, invalid: 0 }, rconErrors: 0 };
  const started = new Date().toISOString();

  // TikFinity runs in a browser-style UI, so its requests are cross-origin. ServerTap defaults to
  // corsOrigins "*" too. Access still needs the key, so CORS only lets the UI read the answer.
  // Browsers refuse a wildcard origin when the request carries credentials (cookies), so the
  // caller's Origin is reflected back and credentials are allowed. Auth still needs the key.
  function corsHeaders(req) {
    const origin = req.headers.origin;
    const h = {
      'Access-Control-Allow-Origin': origin || '*',
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': req.headers['access-control-request-headers'] || 'Content-Type, Authorization, x-servertap-key',
      'Access-Control-Max-Age': '600',
      Vary: 'Origin, Access-Control-Request-Headers, Access-Control-Request-Private-Network',
    };
    if (origin) h['Access-Control-Allow-Credentials'] = 'true';
    if (req.headers['access-control-request-private-network'] === 'true') {
      h['Access-Control-Allow-Private-Network'] = 'true';
    }
    return h;
  }

  function send(req, res, status, obj, headers = {}) {
    const body = JSON.stringify(obj);
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Content-Length': Buffer.byteLength(body), ...corsHeaders(req), ...headers });
    res.end(body);
  }

  function authorized(req, bodyObj) {
    const h = req.headers;
    // TikFinity sends the password in a header named "key" (seen in the bridge log); ServerTap's own name is x-servertap-key.
    const candidates = [h['x-servertap-key'], h['key']];
    if (typeof h.authorization === 'string' && h.authorization.startsWith('Bearer ')) {
      candidates.push(h.authorization.slice(7));
    }
    if (bodyObj && typeof bodyObj.key === 'string') candidates.push(bodyObj.key);
    return candidates.some((c) => typeof c === 'string' && c.length > 0 && safeEqual(c, config.apiKey));
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let size = 0;
      req.on('data', (c) => {
        size += c.length;
        if (size > config.maxBodyBytes) {
          reject(Object.assign(new Error('request body too large'), { status: 413 }));
          req.destroy();
          return;
        }
        chunks.push(c);
      });
      req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
      req.on('error', reject);
    });
  }

  function parseBody(req, raw) {
    if (!raw) return {};
    const type = (req.headers['content-type'] || '').split(';')[0].trim();
    if (type === 'application/json') {
      try {
        const v = JSON.parse(raw);
        return v && typeof v === 'object' ? v : {};
      } catch {
        throw Object.assign(new Error('invalid JSON body'), { status: 400 });
      }
    }
    // ServerTap uses application/x-www-form-urlencoded; also the default when no type is sent.
    return Object.fromEntries(new URLSearchParams(raw));
  }

  function rconFailure(req, res, err) {
    counters.rconErrors++;
    const status = err instanceof RconError && err.code === 'AUTH' ? 502 : err instanceof RconError && err.code === 'TIMEOUT' ? 504 : 503;
    send(req, res, status, { ok: false, error: 'rcon_unavailable', code: err.code || 'ERROR', detail: err.message, rcon: rcon.state });
  }

  async function runCommand(req, res, rawCommand) {
    const norm = normalizeCommand(rawCommand, config.maxCommandLength);
    if (norm.error) {
      counters.rejected.invalid++;
      return send(req, res, 400, { ok: false, error: 'invalid_command', detail: norm.error });
    }
    const { command } = norm;
    if (blocked.has(rootOf(command))) {
      counters.rejected.blocked++;
      log('warn', `blocked command root "${rootOf(command)}" (not forwarded)`);
      return send(req, res, 403, { ok: false, error: 'blocked_command', detail: `"${rootOf(command)}" is blocked by bridge.config.json` });
    }
    const take = bucket.take();
    if (!take.ok) {
      counters.rejected.rateLimit++;
      const retry = Math.ceil(take.retryAfterMs / 1000) || 1;
      log('warn', `rate limit hit; command not forwarded: ${command}`);
      return send(req, res, 429, { ok: false, error: 'rate_limited', retryAfterMs: take.retryAfterMs }, { 'Retry-After': String(retry) });
    }
    if (/\{[a-z_]+\}/i.test(command)) {
      // TikFinity should replace {username} before sending. A literal placeholder means the
      // action was set up wrong. Forward it anyway so nothing is silently rewritten.
      log('warn', `command still contains an unreplaced TikFinity placeholder: ${command}`);
    }
    if (norm.slashStripped) log('info', 'stripped a leading "/" from the command');
    counters.commands++;
    log('info', `forwarding to RCON: ${command}`);
    try {
      const output = await rcon.send(command);
      log('info', `RCON ok (${output.length} chars of output)`);
      return send(req, res, 200, { ok: true, command, output });
    } catch (err) {
      log('error', `RCON failed for "${command}": ${err.message}`);
      return rconFailure(req, res, err);
    }
  }

  async function handle(req, res) {
    counters.requests++;
    const url = new URL(req.url, 'http://localhost');
    const path = url.pathname.replace(/\/+$/, '') || '/';
    // Diagnostics: method, path and header NAMES only. Header values (keys, passwords) are never logged.
    const headerNames = Object.keys(req.headers).filter((h) => !['host', 'user-agent', 'connection', 'accept-encoding', 'content-length'].includes(h));
    log('info', `request ${req.method} ${path} from ${req.socket.remoteAddress} headers=[${headerNames.join(',')}]`);

    if (req.method === 'OPTIONS') {
      // CORS preflight: answer for any path, with no auth, and no command is run.
      res.writeHead(204, { ...corsHeaders(req), 'Content-Length': '0' });
      return res.end();
    }

    if (req.method === 'GET' && path === '/v1/ping') {
      return send(req, res, 200, { ok: true, message: 'pong!', bridge: 'tikfinity-rcon', rcon: rcon.state });
    }
    if (req.method === 'GET' && path === '/bridge/status') {
      return send(req, res, 200, {
        ok: true,
        startedAt: started,
        listen: `${config.listenHost}:${config.listenPort}`,
        rcon: { target: `${config.rcon.host}:${config.rcon.port}`, state: rcon.state, lastError: rcon.lastError, stats: rcon.stats },
        counters,
      });
    }

    const isExec = req.method === 'POST' && path === '/v1/server/exec';
    const isPlayers = req.method === 'GET' && (path === '/v1/players' || path === '/v1/server');
    if (!isExec && !isPlayers) {
      return send(req, res, 404, { ok: false, error: 'not_found', detail: `${req.method} ${path}` });
    }

    let body = {};
    if (isExec) {
      const raw = await readBody(req);
      body = parseBody(req, raw);
    }
    if (!authorized(req, body)) {
      counters.rejected.auth++;
      log('warn', `rejected request without a valid key: ${req.method} ${path}`);
      return send(req, res, 401, { ok: false, error: 'unauthorized', detail: 'send the bridge key in the x-servertap-key header' });
    }

    if (isExec) return runCommand(req, res, body.command);

    // GET /v1/players and /v1/server: read-only, uses `list`.
    try {
      counters.commands++;
      const out = await rcon.send('list');
      const m = out.match(/There are (\d+) of a max of (\d+) players online:?\s*(.*)$/s);
      const names = m && m[3].trim() ? m[3].split(',').map((s) => s.trim()).filter(Boolean) : [];
      const max = m ? Number(m[2]) : null;
      return send(req, res, 200, {
        ok: true,
        onlinePlayers: m ? Number(m[1]) : null,
        maxPlayers: max,
        players: names,
        targetPlayer: config.targetPlayer,
        targetOnline: names.includes(config.targetPlayer),
        raw: out,
      });
    } catch (err) {
      return rconFailure(req, res, err);
    }
  }

  const server = http.createServer((req, res) => {
    handle(req, res).catch((err) => {
      const status = err.status || 500;
      if (!res.headersSent) send(req, res, status, { ok: false, error: status === 500 ? 'internal_error' : 'bad_request', detail: err.message });
      else res.end();
    });
  });
  server.keepAliveTimeout = 5000;
  // Websocket upgrades (ServerTap's /v1/ws/console style) are not implemented. Log and refuse them
  // explicitly so the client gets a clear answer instead of a silent hang.
  server.on('upgrade', (req, socket) => {
    log('warn', `websocket upgrade attempt ${req.method} ${req.url} (not supported by this bridge)`);
    socket.end('HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n');
  });
  return { server, counters, bucket };
}

module.exports = { createBridge, TokenBucket, normalizeCommand, rootOf, safeEqual };
