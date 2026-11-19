'use strict';

// Integration tests. A fake Minecraft RCON server and an ephemeral HTTP port are used,
// so no real game server or secret is touched.
//   npm test   (node --test test/)

const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { encodePacket, PacketParser, RconClient, RconError, TYPE_RESPONSE, TYPE_COMMAND, TYPE_LOGIN } = require('../lib/rcon');
const { loadConfig, parseProperties } = require('../lib/config');
const { createBridge, normalizeCommand, rootOf, TokenBucket } = require('../lib/bridge');

const PASSWORD = 'test-rcon-pw-123';
const API_KEY = 'test-bridge-key-0123456789abcdef';

// ---------- fake RCON server ----------
function startFakeRcon({ password = PASSWORD, port = 0 } = {}) {
  const received = [];
  const conns = new Set();
  const server = net.createServer((sock) => {
    conns.add(sock);
    sock.on('close', () => conns.delete(sock));
    sock.on('error', () => {});
    let authed = false;
    const parser = new PacketParser((pkt) => {
      if (pkt.type === TYPE_LOGIN) {
        if (pkt.body === password) {
          authed = true;
          sock.write(encodePacket(pkt.id, TYPE_COMMAND, ''));
        } else {
          sock.write(encodePacket(-1, TYPE_COMMAND, ''));
        }
        return;
      }
      if (!authed || pkt.type !== TYPE_COMMAND) return;
      received.push(pkt.body);
      let reply;
      if (pkt.body === 'list') reply = 'There are 2 of a max of 10 players online: CantSleepZane, Bob';
      else if (pkt.body === 'slow') return; // never answer, to exercise the timeout
      else reply = `ran: ${pkt.body}`;
      const bytes = encodePacket(pkt.id, TYPE_RESPONSE, reply);
      // Split the reply in two writes to prove the parser reassembles fragments.
      const mid = Math.floor(bytes.length / 2);
      sock.write(bytes.subarray(0, mid));
      setTimeout(() => sock.write(bytes.subarray(mid)), 5);
    });
    sock.on('data', (c) => parser.push(c));
  });
  return new Promise((resolve) => {
    server.listen(port, '127.0.0.1', () => {
      resolve({
        port: server.address().port,
        received,
        close: () => new Promise((r) => {
          for (const s of conns) s.destroy();
          server.close(() => r());
        }),
      });
    });
  });
}

function httpRequest(port, method, p, { headers = {}, body } = {}) {
  return new Promise((resolve, reject) => {
    const req = require('node:http').request({ host: '127.0.0.1', port, method, path: p, headers }, (res) => {
      let data = '';
      res.setEncoding('utf8');
      res.on('data', (c) => (data += c));
      res.on('end', () => {
        let json = null;
        try { json = JSON.parse(data); } catch { /* non-JSON */ }
        resolve({ status: res.statusCode, headers: res.headers, json, text: data });
      });
    });
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

// ---------- shared setup ----------
let tmpDir;
let fake;

function writeProps(port, extra = '') {
  const file = path.join(tmpDir, 'server.properties');
  fs.writeFileSync(file, `#test\nenable-rcon=true\nrcon.port=${port}\nrcon.password=${PASSWORD}\nserver-port=25567\n${extra}`);
  return file;
}

before(async () => {
  tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tikbridge-test-'));
  fake = await startFakeRcon();
});
after(async () => {
  await fake.close();
  fs.rmSync(tmpDir, { recursive: true, force: true });
});

function makeConfig(overrides = {}, port = fake.port) {
  const props = writeProps(port);
  return loadConfig(tmpDir, { serverPropertiesPath: props, apiKey: API_KEY, listenPort: 0, ...overrides });
}

async function startBridge(configOverrides = {}, port) {
  const config = makeConfig(configOverrides, port);
  const logs = [];
  const rcon = new RconClient({
    host: config.rcon.host,
    port: config.rcon.port,
    password: config.rcon.password,
    connectTimeoutMs: 1000,
    commandTimeoutMs: 800,
    maxBackoffMs: 2000,
    log: (lvl, m) => logs.push(`${lvl}:${m}`),
  });
  const { server } = createBridge({ config, rcon, logger: (lvl, m) => logs.push(`${lvl}:${m}`) });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  return { port: server.address().port, server, rcon, config, logs, close: () => new Promise((r) => { rcon.close(); server.close(r); }) };
}

// ---------- RCON protocol ----------
test('packet encode/parse round-trips, including split and merged packets', () => {
  const got = [];
  const p = new PacketParser((pkt) => got.push(pkt));
  const a = encodePacket(7, TYPE_COMMAND, 'chunkchange run nuke CantSleepZane');
  const b = encodePacket(8, TYPE_RESPONSE, 'héllo ✓');
  const both = Buffer.concat([a, b]);
  p.push(both.subarray(0, 5));
  p.push(both.subarray(5, 30));
  p.push(both.subarray(30));
  assert.deepEqual(got, [
    { id: 7, type: TYPE_COMMAND, body: 'chunkchange run nuke CantSleepZane' },
    { id: 8, type: TYPE_RESPONSE, body: 'héllo ✓' },
  ]);
});

test('parser rejects an impossible packet length instead of hanging', () => {
  const p = new PacketParser(() => {});
  const bad = Buffer.alloc(4);
  bad.writeInt32LE(3, 0);
  assert.throws(() => p.push(bad), RconError);
});

test('RCON login succeeds and a command body comes back verbatim', async () => {
  const c = new RconClient({ host: '127.0.0.1', port: fake.port, password: PASSWORD, connectTimeoutMs: 1000, commandTimeoutMs: 1000 });
  try {
    const out = await c.send('chunkchange run god_kit CantSleepZane');
    assert.equal(out, 'ran: chunkchange run god_kit CantSleepZane');
  } finally {
    c.close();
  }
});

test('wrong RCON password is reported as AUTH and never retried in a tight loop', async () => {
  const c = new RconClient({ host: '127.0.0.1', port: fake.port, password: 'wrong', connectTimeoutMs: 1000, maxBackoffMs: 2000 });
  await assert.rejects(c.send('list'), (err) => err instanceof RconError && err.code === 'AUTH');
  assert.equal(c.state, 'auth-failed');
  await assert.rejects(c.send('list'), (err) => err.code === 'UNAVAILABLE');
  c.close();
});

test('nothing listening on the RCON port gives a clear NETWORK/UNAVAILABLE error', async () => {
  const probe = net.createServer();
  await new Promise((r) => probe.listen(0, '127.0.0.1', r));
  const deadPort = probe.address().port;
  await new Promise((r) => probe.close(r));
  const c = new RconClient({ host: '127.0.0.1', port: deadPort, password: PASSWORD, connectTimeoutMs: 500 });
  await assert.rejects(c.send('list'), (err) => err instanceof RconError && (err.code === 'NETWORK' || err.code === 'UNAVAILABLE'));
  assert.equal(c.state, 'unreachable');
  c.close();
});

// ---------- command handling ----------
test('normalizeCommand keeps ChunkChange arguments byte-for-byte', () => {
  assert.deepEqual(normalizeCommand('chunkchange run nuke CantSleepZane', 300), { command: 'chunkchange run nuke CantSleepZane', slashStripped: false });
  assert.deepEqual(normalizeCommand('  chunkchange run lava_chunk CantSleepZane below=10  ', 300), { command: 'chunkchange run lava_chunk CantSleepZane below=10', slashStripped: false });
  assert.deepEqual(normalizeCommand('/chunkchange vote CantSleepZane 🔥', 300), { command: 'chunkchange vote CantSleepZane 🔥', slashStripped: true });
  assert.equal(normalizeCommand('', 300).error, 'command is empty');
  assert.equal(normalizeCommand('list\nstop', 300).error, 'command must be a single line');
  assert.equal(normalizeCommand('x'.repeat(301), 300).error, 'command longer than 300 characters');
  assert.equal(rootOf('ChunkChange run nuke'), 'chunkchange');
});

test('ping needs no key and reports RCON state', async () => {
  const b = await startBridge();
  try {
    const r = await httpRequest(b.port, 'GET', '/v1/ping');
    assert.equal(r.status, 200);
    assert.equal(r.json.ok, true);
    assert.equal(r.json.message, 'pong!');
  } finally {
    await b.close();
  }
});

test('browser CORS preflight is answered without auth, and cross-origin responses are readable', async () => {
  const b = await startBridge();
  try {
    const pre = await httpRequest(b.port, 'OPTIONS', '/v1/server', {
      headers: {
        origin: 'http://localhost:3000',
        'access-control-request-method': 'GET',
        'access-control-request-headers': 'x-servertap-key',
        'access-control-request-private-network': 'true',
      },
    });
    assert.equal(pre.status, 204);
    assert.equal(pre.headers['access-control-allow-origin'], 'http://localhost:3000');
    assert.equal(pre.headers['access-control-allow-credentials'], 'true');
    assert.match(pre.headers['access-control-allow-headers'], /x-servertap-key/i);
    assert.equal(pre.headers['access-control-allow-private-network'], 'true');

    const ping = await httpRequest(b.port, 'GET', '/v1/ping', { headers: { origin: 'http://localhost:3000' } });
    assert.equal(ping.status, 200);
    assert.equal(ping.headers['access-control-allow-origin'], 'http://localhost:3000');
  } finally {
    await b.close();
  }
});

test('exec without a key or with a wrong key is 401 and nothing is forwarded', async () => {
  const b = await startBridge();
  try {
    const before = fake.received.length;
    const body = 'command=' + encodeURIComponent('chunkchange run nuke CantSleepZane');
    const h = { 'content-type': 'application/x-www-form-urlencoded' };
    const none = await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: h, body });
    const wrong = await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: { ...h, 'x-servertap-key': 'nope' }, body });
    assert.equal(none.status, 401);
    assert.equal(wrong.status, 401);
    assert.equal(fake.received.length, before);
  } finally {
    await b.close();
  }
});

test('exec with the key forwards the exact ChunkChange command to RCON', async () => {
  const b = await startBridge();
  try {
    const cmd = 'chunkchange run nuke CantSleepZane gift=Galaxy count=5 value=1000';
    const r = await httpRequest(b.port, 'POST', '/v1/server/exec', {
      headers: { 'content-type': 'application/x-www-form-urlencoded', 'x-servertap-key': API_KEY },
      body: 'command=' + encodeURIComponent(cmd) + '&time=0',
    });
    assert.equal(r.status, 200, r.text);
    assert.equal(r.json.command, cmd);
    assert.equal(r.json.output, `ran: ${cmd}`);
    assert.equal(fake.received[fake.received.length - 1], cmd);
  } finally {
    await b.close();
  }
});

test('Bearer header, JSON body and a leading slash are all accepted', async () => {
  const b = await startBridge();
  try {
    const bearer = await httpRequest(b.port, 'POST', '/v1/server/exec', {
      headers: { 'content-type': 'application/x-www-form-urlencoded', authorization: `Bearer ${API_KEY}` },
      body: 'command=' + encodeURIComponent('/chunkchange run god_kit CantSleepZane'),
    });
    assert.equal(bearer.status, 200);
    assert.equal(bearer.json.command, 'chunkchange run god_kit CantSleepZane');

    const json = await httpRequest(b.port, 'POST', '/v1/server/exec', {
      headers: { 'content-type': 'application/json', 'x-servertap-key': API_KEY },
      body: JSON.stringify({ command: 'chunkchange run jail CantSleepZane' }),
    });
    assert.equal(json.status, 200);
    assert.equal(fake.received[fake.received.length - 1], 'chunkchange run jail CantSleepZane');
  } finally {
    await b.close();
  }
});

test('blocked root commands are refused and never reach the server', async () => {
  const b = await startBridge();
  try {
    const before = fake.received.length;
    const r = await httpRequest(b.port, 'POST', '/v1/server/exec', {
      headers: { 'content-type': 'application/x-www-form-urlencoded', 'x-servertap-key': API_KEY },
      body: 'command=' + encodeURIComponent('stop'),
    });
    assert.equal(r.status, 403);
    assert.equal(fake.received.length, before);
  } finally {
    await b.close();
  }
});

test('invalid commands return 400', async () => {
  const b = await startBridge();
  try {
    const send = (command) => httpRequest(b.port, 'POST', '/v1/server/exec', {
      headers: { 'content-type': 'application/json', 'x-servertap-key': API_KEY },
      body: JSON.stringify({ command }),
    });
    assert.equal((await send('   ')).status, 400);
    assert.equal((await send('list\nstop')).status, 400);
    assert.equal((await send(42)).status, 400);
  } finally {
    await b.close();
  }
});

test('rate limit returns 429 with Retry-After, then recovers', async () => {
  const b = await startBridge({ rateLimit: { perSecond: 2, burst: 2 } });
  try {
    const hdr = { 'content-type': 'application/json', 'x-servertap-key': API_KEY };
    const body = JSON.stringify({ command: 'chunkchange run heal CantSleepZane' });
    assert.equal((await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: hdr, body })).status, 200);
    assert.equal((await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: hdr, body })).status, 200);
    const limited = await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: hdr, body });
    assert.equal(limited.status, 429);
    assert.ok(Number(limited.headers['retry-after']) >= 1);
    await new Promise((r) => setTimeout(r, 600));
    assert.equal((await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: hdr, body })).status, 200);
  } finally {
    await b.close();
  }
});

test('token bucket refills at the configured rate', () => {
  let t = 0;
  const bucket = new TokenBucket({ perSecond: 1, burst: 1 }, () => t);
  assert.equal(bucket.take().ok, true);
  assert.equal(bucket.take().ok, false);
  t += 1000;
  assert.equal(bucket.take().ok, true);
});

test('players route reports whether CantSleepZane is online', async () => {
  const b = await startBridge();
  try {
    const r = await httpRequest(b.port, 'GET', '/v1/players', { headers: { 'x-servertap-key': API_KEY } });
    assert.equal(r.status, 200);
    assert.deepEqual(r.json.players, ['CantSleepZane', 'Bob']);
    assert.equal(r.json.targetOnline, true);
    assert.equal(r.json.onlinePlayers, 2);
  } finally {
    await b.close();
  }
});

test('when RCON is down the bridge answers 503 and does not crash', async () => {
  const probe = net.createServer();
  await new Promise((r) => probe.listen(0, '127.0.0.1', r));
  const deadPort = probe.address().port;
  await new Promise((r) => probe.close(r));
  const b = await startBridge({}, deadPort);
  try {
    const r = await httpRequest(b.port, 'POST', '/v1/server/exec', {
      headers: { 'content-type': 'application/json', 'x-servertap-key': API_KEY },
      body: JSON.stringify({ command: 'chunkchange run heal CantSleepZane' }),
    });
    assert.equal(r.status, 503);
    assert.equal(r.json.error, 'rcon_unavailable');
    assert.equal((await httpRequest(b.port, 'GET', '/v1/ping')).status, 200);
  } finally {
    await b.close();
  }
});

test('bridge reconnects to RCON after the server comes back', async () => {
  const first = await startFakeRcon();
  const port = first.port;
  const b = await startBridge({}, port);
  try {
    const hdr = { 'content-type': 'application/json', 'x-servertap-key': API_KEY };
    const body = JSON.stringify({ command: 'chunkchange run heal CantSleepZane' });
    assert.equal((await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: hdr, body })).status, 200);

    await first.close(); // simulate a server restart
    assert.equal((await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: hdr, body })).status, 503);

    const second = await startFakeRcon({ port });
    try {
      let ok = false;
      for (let i = 0; i < 20 && !ok; i++) {
        await new Promise((r) => setTimeout(r, 250));
        ok = (await httpRequest(b.port, 'POST', '/v1/server/exec', { headers: hdr, body })).status === 200;
      }
      assert.ok(ok, 'bridge should reconnect once RCON is back');
    } finally {
      await second.close();
    }
  } finally {
    await b.close();
  }
});

test('status route exposes RCON state and counters but never the key or password', async () => {
  const b = await startBridge();
  try {
    const r = await httpRequest(b.port, 'GET', '/bridge/status');
    assert.equal(r.status, 200);
    assert.ok(!r.text.includes(API_KEY));
    assert.ok(!r.text.includes(PASSWORD));
    for (const line of b.logs) {
      assert.ok(!line.includes(API_KEY) && !line.includes(PASSWORD), 'secrets must not be logged');
    }
  } finally {
    await b.close();
  }
});

test('config reads the RCON password from server.properties and errors clearly when it is missing', () => {
  assert.deepEqual(parseProperties('a=1\n# c\nrcon.password=p\\:w\\=d\n'), { a: '1', 'rcon.password': 'p:w=d' });
  const emptyDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tikbridge-empty-'));
  const f = path.join(emptyDir, 'server.properties');
  fs.writeFileSync(f, 'enable-rcon=true\nrcon.password=\n');
  assert.throws(() => loadConfig(emptyDir, { serverPropertiesPath: f, apiKey: API_KEY }), /rcon.password is empty/);
  fs.rmSync(emptyDir, { recursive: true, force: true });
});

test('bridge key is generated once into bridge.local.json and then reused', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tikbridge-key-'));
  const props = path.join(dir, 'server.properties');
  fs.writeFileSync(props, `rcon.password=${PASSWORD}\nrcon.port=${fake.port}\n`);
  const first = loadConfig(dir, { serverPropertiesPath: props });
  const second = loadConfig(dir, { serverPropertiesPath: props });
  assert.ok(first.apiKey.length >= 32);
  assert.equal(first.apiKey, second.apiKey);
  fs.rmSync(dir, { recursive: true, force: true });
});
