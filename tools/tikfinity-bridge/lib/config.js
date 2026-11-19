'use strict';

// Loads bridge settings. Secrets are never copied into this folder:
//  - the RCON password is read at runtime from server.properties (the server's own config)
//  - the bridge API key lives in bridge.local.json, which is generated on first run and git-ignored

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const DEFAULTS = {
  listenHost: '127.0.0.1',
  listenPort: 4567,
  serverPropertiesPath: '../../server/server.properties',
  rcon: {
    host: '127.0.0.1',
    fallbackPort: 25577,
    connectTimeoutMs: 3000,
    commandTimeoutMs: 5000,
    maxBackoffMs: 30000,
  },
  rateLimit: { perSecond: 4, burst: 8 },
  maxCommandLength: 300,
  maxBodyBytes: 4096,
  blockedRootCommands: ['stop', 'op', 'deop', 'reload', 'whitelist', 'ban-ip', 'pardon-ip'],
  targetPlayer: 'CantSleepZane',
};

function deepMerge(base, over) {
  const out = { ...base };
  for (const [k, v] of Object.entries(over || {})) {
    out[k] = v && typeof v === 'object' && !Array.isArray(v) && typeof base[k] === 'object'
      ? deepMerge(base[k], v)
      : v;
  }
  return out;
}

/** Parses a Java .properties file. Handles \: \= \\ escapes and skips comments. */
function parseProperties(text) {
  const props = {};
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#') || line.startsWith('!')) continue;
    const m = line.match(/^((?:\\.|[^=:\s\\])+)\s*[=:]?\s*(.*)$/);
    if (!m) continue;
    const unescape = (s) => s.replace(/\\(.)/g, '$1');
    props[unescape(m[1])] = unescape(m[2]);
  }
  return props;
}

function readServerProperties(propsPath) {
  if (!fs.existsSync(propsPath)) {
    throw new Error(`server.properties not found at ${propsPath}`);
  }
  return parseProperties(fs.readFileSync(propsPath, 'utf8'));
}

/** Returns the bridge API key, creating bridge.local.json with a fresh random key if missing. */
function loadOrCreateApiKey(localPath) {
  if (fs.existsSync(localPath)) {
    const data = JSON.parse(fs.readFileSync(localPath, 'utf8'));
    if (typeof data.apiKey === 'string' && data.apiKey.length >= 16) return data.apiKey;
    throw new Error(`${localPath} has no valid apiKey; delete the file to regenerate it`);
  }
  const apiKey = crypto.randomBytes(24).toString('base64url');
  fs.writeFileSync(localPath, JSON.stringify({ apiKey, createdAt: new Date().toISOString() }, null, 2) + '\n', { flag: 'wx' });
  return apiKey;
}

/**
 * @param {string} baseDir folder that contains bridge.config.json (the bridge's own folder)
 * @param {object} [overrides] optional overrides, used by tests
 */
function loadConfig(baseDir, overrides = {}) {
  let fileCfg = {};
  const cfgPath = path.join(baseDir, 'bridge.config.json');
  if (fs.existsSync(cfgPath)) fileCfg = JSON.parse(fs.readFileSync(cfgPath, 'utf8'));
  const cfg = deepMerge(deepMerge(DEFAULTS, fileCfg), overrides);

  const propsPath = path.resolve(baseDir, cfg.serverPropertiesPath);
  const props = readServerProperties(propsPath);

  const password = props['rcon.password'] || '';
  if (!password) {
    throw new Error('rcon.password is empty in server.properties. Set it there (enable-rcon=true), then restart the server.');
  }
  const rconPort = Number(props['rcon.port'] || cfg.rcon.fallbackPort);
  if (!Number.isInteger(rconPort) || rconPort < 1 || rconPort > 65535) {
    throw new Error(`invalid rcon.port in server.properties: ${props['rcon.port']}`);
  }

  const apiKey = overrides.apiKey || loadOrCreateApiKey(path.join(baseDir, 'bridge.local.json'));

  return {
    ...cfg,
    rcon: { ...cfg.rcon, port: rconPort, password },
    apiKey,
    serverPropertiesPath: propsPath,
    serverPort: Number(props['server-port'] || 25567),
    serverPropsSeen: { rconEnabled: props['enable-rcon'] === 'true' },
  };
}

module.exports = { loadConfig, parseProperties, DEFAULTS };
