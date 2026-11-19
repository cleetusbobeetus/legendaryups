#!/usr/bin/env node
'use strict';

// TikFinity <-> Minecraft RCON bridge for the ChunkChange server.
//
//   node bridge.js                  run the bridge (127.0.0.1:4567 -> RCON 127.0.0.1:25577)
//   node bridge.js --check          test config and the RCON login, then exit
//   node bridge.js --show-connection print the IP, port and key to type into TikFinity

const fs = require('node:fs');
const path = require('node:path');
const { loadConfig } = require('./lib/config');
const { RconClient } = require('./lib/rcon');
const { createBridge } = require('./lib/bridge');

const BASE = __dirname;
const LOG_DIR = path.join(BASE, 'logs');
const LOG_FILE = path.join(LOG_DIR, 'bridge.log');

function makeLogger() {
  fs.mkdirSync(LOG_DIR, { recursive: true });
  return (level, msg) => {
    const line = `${new Date().toISOString()} [${level.toUpperCase()}] ${msg}`;
    // Never log the RCON password or the bridge key. Only commands and status are logged.
    console.log(line);
    try { fs.appendFileSync(LOG_FILE, line + '\n'); } catch { /* log dir not writable: console only */ }
  };
}

function fail(msg) {
  console.error(`\nERROR: ${msg}\n`);
  process.exitCode = 1;
}

async function main() {
  const args = new Set(process.argv.slice(2));
  const log = makeLogger();

  let config;
  try {
    config = loadConfig(BASE);
  } catch (err) {
    return fail(err.message);
  }

  if (args.has('--show-connection')) {
    console.log('TikFinity -> Minecraft Connection values:');
    console.log(`  IP:        ${config.listenHost}`);
    console.log(`  Port:      ${config.listenPort}`);
    console.log(`  Key:       ${config.apiKey}`);
    console.log('  (Key is stored in tools\\tikfinity-bridge\\bridge.local.json. Do not share it.)');
    return;
  }

  if (config.serverPropsSeen && !config.serverPropsSeen.rconEnabled) {
    log('warn', 'enable-rcon is not "true" in server.properties; RCON will probably refuse connections.');
  }

  const rcon = new RconClient({
    host: config.rcon.host,
    port: config.rcon.port,
    password: config.rcon.password,
    connectTimeoutMs: config.rcon.connectTimeoutMs,
    commandTimeoutMs: config.rcon.commandTimeoutMs,
    maxBackoffMs: config.rcon.maxBackoffMs,
    log,
  });

  if (args.has('--check')) {
    log('info', `checking RCON at ${config.rcon.host}:${config.rcon.port} ...`);
    try {
      await rcon.connect();
      const out = await rcon.send('list');
      log('info', `RCON login OK. list -> ${out}`);
    } catch (err) {
      return fail(`RCON check failed: ${err.message}`);
    } finally {
      rcon.close();
    }
    return;
  }

  const { server } = createBridge({ config, rcon, logger: log });

  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      fail(`port ${config.listenPort} is already in use. Another program (or a second bridge) owns it. Find it with: netstat -ano | findstr :${config.listenPort}`);
    } else {
      fail(`HTTP server error: ${err.message}`);
    }
    process.exit(1);
  });

  server.listen(config.listenPort, config.listenHost, () => {
    log('info', `TikFinity bridge listening on http://${config.listenHost}:${config.listenPort}`);
    log('info', `forwarding commands to RCON ${config.rcon.host}:${config.rcon.port}; rate limit ${config.rateLimit.perSecond}/s burst ${config.rateLimit.burst}`);
    // Warm the RCON connection so the first real gift does not pay the login delay.
    rcon.connect().then(
      () => log('info', 'RCON ready'),
      (err) => log('warn', `RCON not ready yet (${err.message}). Commands will retry automatically.`),
    );
  });

  const shutdown = () => {
    log('info', 'shutting down');
    server.close();
    rcon.close();
    process.exit(0);
  };
  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((err) => fail(err.stack || err.message));
