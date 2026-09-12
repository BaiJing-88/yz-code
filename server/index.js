'use strict';

/**
 * YZ-Code 验证码查看器 · 服务端
 * 零 npm 依赖：node:http + node:sqlite + node:crypto
 * 监听 0.0.0.0:3000
 */

const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { DatabaseSync } = require('node:sqlite');

const HOST = '0.0.0.0';
const PORT = 3000;
const MAX_BODY_BYTES = 256 * 1024; // JSON body 上限 256KB
const RATE_LIMIT_WINDOW_MS = 60 * 1000;
const RATE_LIMIT_MAX = 20; // 登录/注册：每 IP 每分钟 20 次

const DATA_DIR = path.join(__dirname, 'data');
const PUBLIC_DIR = path.join(__dirname, 'public');

// ---------- 数据库 ----------
fs.mkdirSync(DATA_DIR, { recursive: true });
const db = new DatabaseSync(path.join(DATA_DIR, 'app.db'));
db.exec(`
PRAGMA journal_mode = WAL;
PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS users (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  created_at    INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS tokens (
  token      TEXT PRIMARY KEY,
  user_id    INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tokens_user ON tokens(user_id);

CREATE TABLE IF NOT EXISTS codes (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  code        TEXT NOT NULL,
  sender      TEXT,
  message     TEXT,
  received_at INTEGER NOT NULL,
  created_at  INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_codes_user_time ON codes(user_id, received_at DESC, id DESC);
`);

const qUserByName = db.prepare('SELECT id, username, password_hash FROM users WHERE username = ?');
const qInsertUser = db.prepare('INSERT INTO users (username, password_hash, created_at) VALUES (?, ?, ?)');
const qInsertToken = db.prepare('INSERT INTO tokens (token, user_id, created_at) VALUES (?, ?, ?)');
const qDeleteToken = db.prepare('DELETE FROM tokens WHERE token = ?');
const qUserByToken = db.prepare(`
  SELECT u.id, u.username
  FROM tokens t JOIN users u ON u.id = t.user_id
  WHERE t.token = ?
`);
const qInsertCode = db.prepare(
  'INSERT INTO codes (user_id, code, sender, message, received_at, created_at) VALUES (?, ?, ?, ?, ?, ?)'
);
const qListCodes = db.prepare(
  'SELECT id, code, sender, message, received_at, created_at FROM codes WHERE user_id = ? ORDER BY received_at DESC, id DESC LIMIT ?'
);
const qLatestCode = db.prepare(
  'SELECT id, code, sender, message, received_at, created_at FROM codes WHERE user_id = ? ORDER BY received_at DESC, id DESC LIMIT 1'
);
const qDeleteCode = db.prepare('DELETE FROM codes WHERE id = ? AND user_id = ?');
const qClearCodes = db.prepare('DELETE FROM codes WHERE user_id = ?');

// ---------- 密码 / token ----------
function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString('hex');
  const hash = crypto.scryptSync(password, salt, 64).toString('hex');
  return `${salt}:${hash}`;
}

function verifyPassword(password, stored) {
  const sep = stored.indexOf(':');
  if (sep <= 0) return false;
  const salt = stored.slice(0, sep);
  const hash = stored.slice(sep + 1);
  let expect;
  try {
    expect = Buffer.from(hash, 'hex');
  } catch {
    return false;
  }
  const calc = crypto.scryptSync(password, salt, 64);
  return expect.length === calc.length && crypto.timingSafeEqual(expect, calc);
}

function newToken() {
  return crypto.randomBytes(32).toString('hex');
}

// ---------- 内存级限流（滑动窗口） ----------
const rateBuckets = new Map(); // ip -> number[] (timestamps)
function isRateLimited(ip) {
  const now = Date.now();
  let hits = rateBuckets.get(ip);
  if (!hits) {
    hits = [];
    rateBuckets.set(ip, hits);
  }
  const cutoff = now - RATE_LIMIT_WINDOW_MS;
  while (hits.length > 0 && hits[0] <= cutoff) hits.shift();
  if (hits.length >= RATE_LIMIT_MAX) return true;
  hits.push(now);
  return false;
}
setInterval(() => {
  const cutoff = Date.now() - RATE_LIMIT_WINDOW_MS;
  for (const [ip, hits] of rateBuckets) {
    while (hits.length > 0 && hits[0] <= cutoff) hits.shift();
    if (hits.length === 0) rateBuckets.delete(ip);
  }
}, 5 * 60 * 1000).unref();

// ---------- 响应工具 ----------
function sendJson(res, status, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
}

function fail(res, status, error) {
  sendJson(res, status, { ok: false, error });
}

function readJsonBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    const cleanup = () => {
      req.off('data', onData);
      req.off('end', onEnd);
      req.off('error', onError);
    };
    const onData = (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        cleanup();
        req.resume();
        const err = new Error('请求体过大（上限 256KB）');
        err.status = 413;
        reject(err);
        return;
      }
      chunks.push(chunk);
    };
    const onEnd = () => {
      cleanup();
      const text = Buffer.concat(chunks).toString('utf8').trim();
      if (!text) {
        resolve({});
        return;
      }
      try {
        const data = JSON.parse(text);
        if (data === null || typeof data !== 'object' || Array.isArray(data)) {
          throw new Error('bad');
        }
        resolve(data);
      } catch {
        const err = new Error('请求体必须是合法的 JSON 对象');
        err.status = 400;
        reject(err);
      }
    };
    const onError = (err) => {
      cleanup();
      reject(err);
    };
    req.on('data', onData);
    req.on('end', onEnd);
    req.on('error', onError);
  });
}

function getBearerToken(req) {
  const h = req.headers['authorization'];
  if (typeof h !== 'string') return null;
  const m = /^Bearer\s+(.+)$/i.exec(h.trim());
  return m ? m[1].trim() : null;
}

function getAuthUser(req) {
  const token = getBearerToken(req);
  if (!token) return null;
  return qUserByToken.get(token) || null;
}

// ---------- 输入校验 ----------
function asOptionalString(v, name, maxLen) {
  if (v === undefined || v === null) return null;
  if (typeof v !== 'string') {
    const err = new Error(`${name} 必须是字符串`);
    err.status = 400;
    throw err;
  }
  const t = v.trim();
  if (maxLen && t.length > maxLen) {
    const err = new Error(`${name} 最长 ${maxLen} 个字符`);
    err.status = 400;
    throw err;
  }
  return t === '' ? null : t;
}

// ---------- API 路由 ----------
async function handleApi(req, res, url, pathname) {
  const method = req.method;

  // 登录 / 注册：先做 IP 限流
  if (method === 'POST' && (pathname === '/api/register' || pathname === '/api/login')) {
    const ip = req.socket.remoteAddress || 'unknown';
    if (isRateLimited(ip)) {
      fail(res, 429, '操作过于频繁，请一分钟后再试');
      return;
    }
  }

  // ---- POST /api/register ----
  if (method === 'POST' && pathname === '/api/register') {
    const body = await readJsonBody(req);
    const username = typeof body.username === 'string' ? body.username.trim() : '';
    const password = typeof body.password === 'string' ? body.password : '';
    if (username.length < 2 || username.length > 32) {
      fail(res, 400, '用户名需为 2-32 个字符');
      return;
    }
    if (password.length < 6) {
      fail(res, 400, '密码至少 6 位');
      return;
    }
    if (qUserByName.get(username)) {
      fail(res, 409, '用户名已存在');
      return;
    }
    const now = Date.now();
    const info = qInsertUser.run(username, hashPassword(password), now);
    const token = newToken();
    qInsertToken.run(token, Number(info.lastInsertRowid), now);
    sendJson(res, 200, { ok: true, token, username });
    return;
  }

  // ---- POST /api/login ----
  if (method === 'POST' && pathname === '/api/login') {
    const body = await readJsonBody(req);
    const username = typeof body.username === 'string' ? body.username.trim() : '';
    const password = typeof body.password === 'string' ? body.password : '';
    if (!username || !password) {
      fail(res, 400, '请提供用户名和密码');
      return;
    }
    const user = qUserByName.get(username);
    if (!user || !verifyPassword(password, user.password_hash)) {
      fail(res, 401, '用户名或密码错误');
      return;
    }
    const token = newToken();
    qInsertToken.run(token, user.id, Date.now());
    sendJson(res, 200, { ok: true, token, username: user.username });
    return;
  }

  // ---- 以下接口均需认证 ----
  const token = getBearerToken(req);
  const user = token ? qUserByToken.get(token) : null;
  if (!user) {
    fail(res, 401, '未登录或登录已过期，请重新登录');
    return;
  }

  // ---- POST /api/logout ----
  if (method === 'POST' && pathname === '/api/logout') {
    qDeleteToken.run(token);
    sendJson(res, 200, { ok: true });
    return;
  }

  // ---- GET /api/me ----
  if (method === 'GET' && pathname === '/api/me') {
    sendJson(res, 200, { ok: true, username: user.username });
    return;
  }

  // ---- POST /api/codes ----
  if (method === 'POST' && pathname === '/api/codes') {
    const body = await readJsonBody(req);
    const code = typeof body.code === 'string' ? body.code.trim() : '';
    if (!code) {
      fail(res, 400, 'code 为必填项');
      return;
    }
    if (code.length > 32) {
      fail(res, 400, 'code 最长 32 个字符');
      return;
    }
    const sender = asOptionalString(body.sender, 'sender', 64);
    const message = asOptionalString(body.message, 'message');
    let receivedAt = Date.now();
    if (body.received_at !== undefined && body.received_at !== null) {
      const t = Number(body.received_at);
      if (!Number.isFinite(t) || t <= 0) {
        fail(res, 400, 'received_at 必须是毫秒时间戳');
        return;
      }
      receivedAt = Math.round(t);
    }
    const info = qInsertCode.run(user.id, code, sender, message, receivedAt, Date.now());
    sendJson(res, 200, { ok: true, id: Number(info.lastInsertRowid) });
    return;
  }

  // ---- GET /api/codes?limit= ----
  if (method === 'GET' && pathname === '/api/codes') {
    let limit = 100;
    const raw = url.searchParams.get('limit');
    if (raw !== null && raw !== '') {
      const n = Number(raw);
      if (!Number.isFinite(n) || n <= 0) {
        fail(res, 400, 'limit 必须是正整数');
        return;
      }
      limit = Math.min(Math.floor(n), 500);
    }
    const codes = qListCodes.all(user.id, limit);
    sendJson(res, 200, { ok: true, codes });
    return;
  }

  // ---- GET /api/codes/latest ----
  if (method === 'GET' && pathname === '/api/codes/latest') {
    const row = qLatestCode.get(user.id);
    sendJson(res, 200, { ok: true, code: row || null });
    return;
  }

  // ---- DELETE /api/codes/:id ----
  const delMatch = /^\/api\/codes\/([^/]+)$/.exec(pathname);
  if (method === 'DELETE' && delMatch) {
    const idStr = delMatch[1];
    if (!/^\d+$/.test(idStr)) {
      fail(res, 400, '无效的验证码 ID');
      return;
    }
    const info = qDeleteCode.run(Number(idStr), user.id);
    if (info.changes === 0) {
      fail(res, 404, '记录不存在或不属于当前用户');
      return;
    }
    sendJson(res, 200, { ok: true });
    return;
  }

  // ---- DELETE /api/codes ----
  if (method === 'DELETE' && pathname === '/api/codes') {
    qClearCodes.run(user.id);
    sendJson(res, 200, { ok: true });
    return;
  }

  fail(res, 404, '接口不存在');
}

// ---------- 静态文件 ----------
const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.txt': 'text/plain; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.mp3': 'audio/mpeg',
  '.mp4': 'video/mp4',
};

function sendFile(res, filePath, headOnly) {
  const ext = path.extname(filePath).toLowerCase();
  const type = MIME_TYPES[ext] || 'application/octet-stream';
  const stat = fs.statSync(filePath);
  res.writeHead(200, {
    'Content-Type': type,
    'Content-Length': stat.size,
    'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=3600',
  });
  if (headOnly) {
    res.end();
    return;
  }
  fs.createReadStream(filePath).pipe(res);
}

function serveStatic(res, pathname, headOnly) {
  let rel;
  try {
    rel = decodeURIComponent(pathname);
  } catch {
    rel = '/';
  }
  if (rel === '/' || rel === '') rel = '/index.html';

  // 防路径穿越：resolve 后必须仍在 public 目录内
  const filePath = path.resolve(PUBLIC_DIR, '.' + rel);
  if (!filePath.startsWith(PUBLIC_DIR + path.sep)) {
    sendFile(res, path.join(PUBLIC_DIR, 'index.html'), headOnly);
    return;
  }

  let stat = null;
  try {
    stat = fs.statSync(filePath);
  } catch {
    stat = null;
  }
  if (stat && stat.isFile()) {
    sendFile(res, filePath, headOnly);
    return;
  }
  // SPA 兜底：未知非 /api 路径返回 index.html
  sendFile(res, path.join(PUBLIC_DIR, 'index.html'), headOnly);
}

// ---------- 服务器 ----------
const server = http.createServer(async (req, res) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('X-Frame-Options', 'DENY');
  res.setHeader('Referrer-Policy', 'no-referrer');
  try {
    const url = new URL(req.url, 'http://127.0.0.1');
    let pathname = url.pathname;
    if (pathname.length > 1 && pathname.endsWith('/')) pathname = pathname.slice(0, -1);

    if (pathname.startsWith('/api/') || pathname === '/api') {
      await handleApi(req, res, url, pathname);
      return;
    }
    if (req.method === 'GET' || req.method === 'HEAD') {
      serveStatic(res, pathname, req.method === 'HEAD');
      return;
    }
    fail(res, 405, '方法不允许');
  } catch (err) {
    if (err && err.status) {
      fail(res, err.status, err.message);
      return;
    }
    console.error('服务器内部错误:', err);
    if (!res.headersSent) fail(res, 500, '服务器内部错误');
    else res.end();
  }
});

server.listen(PORT, HOST, () => {
  console.log(`YZ-Code 服务端已启动: http://${HOST}:${PORT}`);
  console.log(`数据库文件: ${path.join(DATA_DIR, 'app.db')}`);
});

function shutdown() {
  server.close(() => {
    try {
      db.close();
    } catch {}
    process.exit(0);
  });
  setTimeout(() => process.exit(0), 1000).unref();
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
