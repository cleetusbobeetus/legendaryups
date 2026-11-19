'use strict';

// Minimal Source RCON client (the protocol Minecraft uses on rcon.port).
// Packet layout: int32LE length | int32LE id | int32LE type | body (UTF-8) | 0x00 | 0x00
// `length` counts everything after itself: id (4) + type (4) + body + 2 terminators.

const net = require('node:net');

const TYPE_RESPONSE = 0;
const TYPE_COMMAND = 2; // also the auth-response type
const TYPE_LOGIN = 3;
const LOGIN_ID = 1;
const MAX_PACKET = 64 * 1024;

class RconError extends Error {
  constructor(code, message) {
    super(message);
    this.name = 'RconError';
    this.code = code; // AUTH | NETWORK | TIMEOUT | UNAVAILABLE
  }
}

function encodePacket(id, type, body) {
  const payload = Buffer.from(body, 'utf8');
  const length = 4 + 4 + payload.length + 2;
  const buf = Buffer.alloc(4 + length); // trailing two NULs are already zero
  buf.writeInt32LE(length, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  payload.copy(buf, 12);
  return buf;
}

// Accumulates TCP chunks and emits complete packets (handles split and merged packets).
class PacketParser {
  constructor(onPacket) {
    this.buf = Buffer.alloc(0);
    this.onPacket = onPacket;
  }

  push(chunk) {
    this.buf = this.buf.length ? Buffer.concat([this.buf, chunk]) : chunk;
    while (this.buf.length >= 4) {
      const length = this.buf.readInt32LE(0);
      if (length < 10 || length > MAX_PACKET) {
        throw new RconError('NETWORK', `invalid RCON packet length ${length}`);
      }
      if (this.buf.length < 4 + length) break;
      const id = this.buf.readInt32LE(4);
      const type = this.buf.readInt32LE(8);
      const body = this.buf.toString('utf8', 12, 4 + length - 2);
      this.buf = this.buf.subarray(4 + length);
      this.onPacket({ id, type, body });
    }
  }
}

class RconClient {
  /**
   * @param {object} opts
   * @param {string} opts.host
   * @param {number} opts.port
   * @param {string} opts.password   never logged
   * @param {number} [opts.connectTimeoutMs=3000]
   * @param {number} [opts.commandTimeoutMs=5000]
   * @param {number} [opts.maxBackoffMs=30000]
   * @param {(level: string, msg: string) => void} [opts.log]
   */
  constructor(opts) {
    this.host = opts.host;
    this.port = opts.port;
    this.password = opts.password;
    this.connectTimeoutMs = opts.connectTimeoutMs ?? 3000;
    this.commandTimeoutMs = opts.commandTimeoutMs ?? 5000;
    this.maxBackoffMs = opts.maxBackoffMs ?? 30000;
    this.log = opts.log || (() => {});

    this.socket = null;
    this.authed = false;
    this.state = 'disconnected'; // disconnected | connecting | connected | auth-failed | unreachable
    this.pending = new Map();
    this.nextId = 2;
    this.queue = Promise.resolve();
    this.backoffMs = 1000;
    this.retryAt = 0;
    this.lastError = null;
    this.stats = { sent: 0, failed: 0, reconnects: 0 };
  }

  /** Opens and authenticates a connection, unless already connected or still backing off. */
  async connect() {
    if (this.socket && this.authed) return;
    const now = Date.now();
    if (now < this.retryAt) {
      const wait = Math.ceil((this.retryAt - now) / 1000);
      throw new RconError('UNAVAILABLE', `RCON unavailable (${this.lastError || 'unknown'}); retrying in ${wait}s`);
    }
    this.state = 'connecting';
    try {
      await this._open();
      this.state = 'connected';
      this.lastError = null;
      this.backoffMs = 1000;
      this.retryAt = 0;
      this.stats.reconnects++;
      this.log('info', `RCON connected to ${this.host}:${this.port}`);
    } catch (err) {
      this._teardown();
      this.lastError = err.message;
      this.state = err.code === 'AUTH' ? 'auth-failed' : 'unreachable';
      this.retryAt = Date.now() + this.backoffMs;
      this.backoffMs = Math.min(this.backoffMs * 2, this.maxBackoffMs);
      this.log('error', `RCON connect failed: ${err.message}`);
      throw err;
    }
  }

  _open() {
    return new Promise((resolve, reject) => {
      const sock = net.createConnection({ host: this.host, port: this.port });
      this.socket = sock;
      this.authed = false;
      let settled = false;

      const finish = (err) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        if (err) reject(err);
        else resolve();
      };

      const timer = setTimeout(() => {
        finish(new RconError('TIMEOUT', `no RCON login reply from ${this.host}:${this.port} within ${this.connectTimeoutMs} ms`));
        sock.destroy();
      }, this.connectTimeoutMs);

      const parser = new PacketParser((pkt) => {
        if (!this.authed) {
          if (pkt.id === -1) {
            finish(new RconError('AUTH', 'RCON password rejected by server'));
            sock.destroy();
            return;
          }
          if (pkt.type === TYPE_COMMAND && pkt.id === LOGIN_ID) {
            this.authed = true;
            finish();
          }
          return; // ignore anything else before auth completes
        }
        const p = this.pending.get(pkt.id);
        if (p) {
          this.pending.delete(pkt.id);
          clearTimeout(p.timer);
          p.resolve(pkt.body);
        }
      });

      sock.on('connect', () => {
        sock.write(encodePacket(LOGIN_ID, TYPE_LOGIN, this.password));
      });
      sock.on('data', (chunk) => {
        try {
          parser.push(chunk);
        } catch (err) {
          sock.destroy(err);
        }
      });
      sock.on('error', (err) => {
        const e = new RconError('NETWORK', `${err.code || err.message}`);
        finish(e);
        this._failPending(e);
      });
      sock.on('close', () => {
        const e = new RconError('NETWORK', 'RCON connection closed');
        finish(e);
        this._failPending(e);
        if (this.socket === sock) {
          this.socket = null;
          this.authed = false;
          if (this.state === 'connected') this.state = 'disconnected';
        }
      });
    });
  }

  _failPending(err) {
    for (const [id, p] of this.pending) {
      clearTimeout(p.timer);
      this.pending.delete(id);
      p.reject(err);
    }
  }

  _teardown() {
    const s = this.socket;
    this.socket = null;
    this.authed = false;
    if (s) s.destroy();
  }

  _nextId() {
    const id = this.nextId;
    this.nextId = this.nextId >= 0x7ffffff0 ? 2 : this.nextId + 1;
    return id;
  }

  /** Sends one command. Commands are serialized so responses can never interleave. */
  send(command) {
    const run = () => this._sendOnce(command);
    const result = this.queue.then(run, run);
    this.queue = result.catch(() => {});
    return result;
  }

  async _sendOnce(command) {
    await this.connect();
    const id = this._nextId();
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        this.stats.failed++;
        reject(new RconError('TIMEOUT', `RCON command timed out after ${this.commandTimeoutMs} ms`));
      }, this.commandTimeoutMs);
      this.pending.set(id, {
        timer,
        resolve: (body) => {
          this.stats.sent++;
          resolve(body);
        },
        reject: (err) => {
          this.stats.failed++;
          reject(err);
        },
      });
      try {
        this.socket.write(encodePacket(id, TYPE_COMMAND, command));
      } catch (err) {
        this.pending.delete(id);
        clearTimeout(timer);
        this.stats.failed++;
        reject(new RconError('NETWORK', err.message));
      }
    });
  }

  close() {
    this._failPending(new RconError('NETWORK', 'bridge shutting down'));
    this._teardown();
    this.state = 'disconnected';
  }
}

module.exports = { RconClient, RconError, encodePacket, PacketParser, TYPE_RESPONSE, TYPE_COMMAND, TYPE_LOGIN };
