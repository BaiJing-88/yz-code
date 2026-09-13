'use strict';

/**
 * YZ-Code 数据层（零 npm 依赖）
 * 优先使用 Node 内置 SQLite（node:sqlite，需 Node ≥ 22.5）；
 * 不可用时自动回退到 JSON 文件存储（兼容 Node 18/20/21）。
 */

const fs = require('node:fs');
const path = require('node:path');

let DatabaseSync = null;
try {
  ({ DatabaseSync } = require('node:sqlite'));
} catch {
  DatabaseSync = null;
}

function createStore(dataDir) {
  fs.mkdirSync(dataDir, { recursive: true });
  if (DatabaseSync) {
    return createSqliteStore(new DatabaseSync(path.join(dataDir, 'app.db')));
  }
  console.log('[YZ-Code] 当前 Node 版本无内置 node:sqlite，使用 JSON 文件存储（建议升级 Node ≥ 22.5）');
  return createJsonStore(path.join(dataDir, 'app.json'));
}

// ---------- SQLite 后端 ----------
function createSqliteStore(db) {
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

  return {
    backend: 'sqlite',
    findUserByName: (username) => qUserByName.get(username) || null,
    insertUser: (username, passwordHash, createdAt) => ({
      id: Number(qInsertUser.run(username, passwordHash, createdAt).lastInsertRowid),
    }),
    insertToken: (token, userId, createdAt) => qInsertToken.run(token, userId, createdAt),
    deleteToken: (token) => qDeleteToken.run(token),
    findUserByToken: (token) => qUserByToken.get(token) || null,
    insertCode: (userId, code, sender, message, receivedAt, createdAt) => ({
      id: Number(qInsertCode.run(userId, code, sender, message, receivedAt, createdAt).lastInsertRowid),
    }),
    listCodes: (userId, limit) => qListCodes.all(userId, limit),
    latestCode: (userId) => qLatestCode.get(userId) || null,
    deleteCode: (id, userId) => qDeleteCode.run(id, userId),
    clearCodes: (userId) => qClearCodes.run(userId),
    close: () => { try { db.close(); } catch {} },
  };
}

// ---------- JSON 文件后端（原子写入） ----------
function createJsonStore(file) {
  let data = { users: [], tokens: [], codes: [], nextUserId: 1, nextCodeId: 1 };
  try {
    const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
    if (raw && Array.isArray(raw.users) && Array.isArray(raw.tokens) && Array.isArray(raw.codes)) {
      data = raw;
      data.nextUserId = data.nextUserId || data.users.reduce((m, u) => Math.max(m, u.id || 0), 0) + 1;
      data.nextCodeId = data.nextCodeId || data.codes.reduce((m, c) => Math.max(m, c.id || 0), 0) + 1;
    }
  } catch {
    // 文件不存在或损坏：从空库开始
  }

  function save() {
    const tmp = file + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(data));
    fs.renameSync(tmp, file);
  }

  function sortedCodes(userId, limit) {
    return data.codes
      .filter((c) => c.user_id === userId)
      .sort((a, b) => (b.received_at - a.received_at) || (b.id - a.id))
      .slice(0, limit);
  }

  return {
    backend: 'json',
    findUserByName: (username) => data.users.find((u) => u.username === username) || null,
    insertUser: (username, passwordHash, createdAt) => {
      const user = { id: data.nextUserId++, username, password_hash: passwordHash, created_at: createdAt };
      data.users.push(user);
      save();
      return { id: user.id };
    },
    insertToken: (token, userId, createdAt) => {
      data.tokens.push({ token, user_id: userId, created_at: createdAt });
      save();
    },
    deleteToken: (token) => {
      data.tokens = data.tokens.filter((t) => t.token !== token);
      save();
    },
    findUserByToken: (token) => {
      const t = data.tokens.find((x) => x.token === token);
      if (!t) return null;
      const u = data.users.find((x) => x.id === t.user_id);
      return u ? { id: u.id, username: u.username } : null;
    },
    insertCode: (userId, code, sender, message, receivedAt, createdAt) => {
      const row = { id: data.nextCodeId++, user_id: userId, code, sender, message, received_at: receivedAt, created_at: createdAt };
      data.codes.push(row);
      save();
      return { id: row.id };
    },
    listCodes: (userId, limit) => sortedCodes(userId, limit),
    latestCode: (userId) => sortedCodes(userId, 1)[0] || null,
    deleteCode: (id, userId) => {
      const before = data.codes.length;
      data.codes = data.codes.filter((c) => !(c.id === id && c.user_id === userId));
      save();
      return { changes: before - data.codes.length };
    },
    clearCodes: (userId) => {
      data.codes = data.codes.filter((c) => c.user_id !== userId);
      save();
    },
    close: () => {},
  };
}

module.exports = { createStore };
