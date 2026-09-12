'use strict';

/* ============ YZ-Code Web 前端（原生 JS） ============ */

const TOKEN_KEY = 'yzcode_token';
const POLL_INTERVAL = 4000;

const state = {
  token: localStorage.getItem(TOKEN_KEY) || null,
  username: null,
  codes: [],
  filter: '',
  latestId: null,
  pollTimer: null,
  timeTimer: null,
  authMode: 'login',
};

const $ = (id) => document.getElementById(id);
const els = {
  authView: $('auth-view'),
  dashView: $('dash-view'),
  tabs: document.querySelector('.tabs'),
  tabLogin: $('tab-login'),
  tabRegister: $('tab-register'),
  authForm: $('auth-form'),
  authUsername: $('auth-username'),
  authPassword: $('auth-password'),
  authError: $('auth-error'),
  authSubmit: $('auth-submit'),
  userChip: $('user-chip'),
  logoutBtn: $('logout-btn'),
  latestCard: $('latest-card'),
  latestCode: $('latest-code'),
  latestMeta: $('latest-meta'),
  copyBtn: $('copy-btn'),
  copyBtnText: $('copy-btn-text'),
  historyList: $('history-list'),
  historyCount: $('history-count'),
  filterInput: $('filter-input'),
  clearAllBtn: $('clear-all-btn'),
  emptyState: $('empty-state'),
  emptyTitle: $('empty-title'),
  emptySub: $('empty-sub'),
  modalOverlay: $('modal-overlay'),
  modalCancel: $('modal-cancel'),
  modalConfirm: $('modal-confirm'),
  toast: $('toast'),
};

/* ---------- 工具 ---------- */
function escapeHtml(s) {
  return String(s)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;');
}

function relTime(ts) {
  const diff = Date.now() - Number(ts);
  if (diff < 15e3) return '刚刚';
  if (diff < 60e3) return `${Math.floor(diff / 1e3)} 秒前`;
  if (diff < 3600e3) return `${Math.floor(diff / 6e4)} 分钟前`;
  if (diff < 86400e3) return `${Math.floor(diff / 36e5)} 小时前`;
  if (diff < 7 * 86400e3) return `${Math.floor(diff / 864e5)} 天前`;
  const d = new Date(Number(ts));
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

let toastTimer = null;
function toast(msg, kind = 'ok') {
  els.toast.textContent = msg;
  els.toast.className = `toast show ${kind}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => els.toast.classList.remove('show'), 2200);
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // 非安全上下文（http 局域网访问）回退方案
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.cssText = 'position:fixed;opacity:0;pointer-events:none';
    document.body.appendChild(ta);
    ta.select();
    let ok = false;
    try { ok = document.execCommand('copy'); } catch { ok = false; }
    ta.remove();
    return ok;
  }
}

/* ---------- API ---------- */
async function api(path, { method = 'GET', body } = {}) {
  const headers = {};
  if (state.token) headers['Authorization'] = `Bearer ${state.token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res;
  try {
    res = await fetch(path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  } catch {
    throw new Error('网络异常，无法连接服务器');
  }
  let data = null;
  try { data = await res.json(); } catch { /* 忽略非 JSON 响应 */ }
  if (res.status === 401 && state.token && path !== '/api/login' && path !== '/api/register') {
    forceLogout('登录已过期，请重新登录');
  }
  if (!res.ok || !data || data.ok !== true) {
    throw new Error((data && data.error) || `请求失败（HTTP ${res.status}）`);
  }
  return data;
}

/* ---------- 视图切换 ---------- */
function showAuth() {
  els.dashView.classList.add('hidden');
  els.authView.classList.remove('hidden');
  stopPolling();
  els.authPassword.value = '';
  els.authError.textContent = '';
  setTimeout(() => els.authUsername.focus(), 60);
}

function showDash() {
  els.authView.classList.add('hidden');
  els.dashView.classList.remove('hidden');
  els.userChip.textContent = state.username;
  state.latestId = null;
  refreshCodes(true);
  startPolling();
  maybeAskNotification();
}

function forceLogout(msg) {
  state.token = null;
  state.username = null;
  localStorage.removeItem(TOKEN_KEY);
  showAuth();
  if (msg) toast(msg, 'err');
}

/* ---------- 登录 / 注册 ---------- */
function setAuthMode(mode) {
  state.authMode = mode;
  const isLogin = mode === 'login';
  els.tabs.dataset.active = mode;
  els.tabLogin.classList.toggle('active', isLogin);
  els.tabRegister.classList.toggle('active', !isLogin);
  els.tabLogin.setAttribute('aria-selected', String(isLogin));
  els.tabRegister.setAttribute('aria-selected', String(!isLogin));
  els.authSubmit.querySelector('.btn-text').textContent = isLogin ? '登 录' : '注册并登录';
  els.authPassword.setAttribute('autocomplete', isLogin ? 'current-password' : 'new-password');
  els.authError.textContent = '';
}

async function submitAuth(e) {
  e.preventDefault();
  const username = els.authUsername.value.trim();
  const password = els.authPassword.value;
  if (username.length < 2 || username.length > 32) {
    els.authError.textContent = '用户名需为 2-32 个字符';
    return;
  }
  if (password.length < 6) {
    els.authError.textContent = '密码至少 6 位';
    return;
  }
  els.authSubmit.classList.add('loading');
  els.authSubmit.disabled = true;
  els.authError.textContent = '';
  try {
    const data = await api(`/api/${state.authMode}`, { method: 'POST', body: { username, password } });
    state.token = data.token;
    state.username = data.username;
    localStorage.setItem(TOKEN_KEY, data.token);
    toast(state.authMode === 'register' ? '注册成功，欢迎加入' : `欢迎回来，${data.username}`);
    showDash();
  } catch (err) {
    els.authError.textContent = err.message;
  } finally {
    els.authSubmit.classList.remove('loading');
    els.authSubmit.disabled = false;
  }
}

/* ---------- 验证码数据 ---------- */
async function refreshCodes(initial = false) {
  try {
    const data = await api('/api/codes?limit=200');
    const changed = JSON.stringify(data.codes) !== JSON.stringify(state.codes);
    const newest = data.codes[0] || null;
    const isNew = newest && state.latestId !== null && newest.id !== state.latestId;
    state.codes = data.codes;
    if (initial || changed) {
      renderLatest(isNew);
      renderHistory();
    }
    state.latestId = newest ? newest.id : null;
  } catch (err) {
    if (!initial) console.warn('轮询失败:', err.message);
  }
}

function renderLatest(flash = false) {
  const latest = state.codes[0] || null;
  if (!latest) {
    els.latestCode.textContent = '------';
    els.latestCode.disabled = true;
    els.latestMeta.textContent = '等待第一条验证码…';
    els.copyBtn.disabled = true;
    return;
  }
  els.latestCode.textContent = latest.code;
  els.latestCode.disabled = false;
  els.copyBtn.disabled = false;
  const sender = latest.sender ? `来自 ${latest.sender}` : '未知发送方';
  els.latestMeta.textContent = `${sender} · ${relTime(latest.received_at)}`;
  if (flash) {
    els.latestCard.classList.remove('flash');
    void els.latestCard.offsetWidth; // 重启动画
    els.latestCard.classList.add('flash');
    notifyNewCode(latest);
  }
}

function filteredCodes() {
  const kw = state.filter.trim().toLowerCase();
  if (!kw) return state.codes;
  return state.codes.filter((c) =>
    (c.code || '').toLowerCase().includes(kw) ||
    (c.sender || '').toLowerCase().includes(kw) ||
    (c.message || '').toLowerCase().includes(kw)
  );
}

function renderHistory() {
  const list = filteredCodes();
  els.historyCount.textContent = state.filter
    ? `${list.length} / ${state.codes.length} 条`
    : state.codes.length ? `${state.codes.length} 条` : '';

  if (list.length === 0) {
    els.historyList.innerHTML = '';
    els.emptyState.classList.remove('hidden');
    if (state.codes.length === 0) {
      els.emptyTitle.textContent = '暂无验证码';
      els.emptySub.textContent = 'Android 端收到短信后会自动同步到这里';
    } else {
      els.emptyTitle.textContent = '没有匹配的记录';
      els.emptySub.textContent = '换个关键字试试';
    }
    return;
  }
  els.emptyState.classList.add('hidden');

  els.historyList.innerHTML = list.map((c, i) => {
    const sender = c.sender
      ? escapeHtml(c.sender)
      : '<span class="no-sender">未知发送方</span>';
    const message = c.message ? escapeHtml(c.message) : '（无短信原文）';
    return `
    <li class="history-item" data-id="${c.id}" style="animation-delay:${Math.min(i * 35, 350)}ms">
      <span class="item-code" role="button" tabindex="0" title="点击复制" data-copy="${escapeHtml(c.code)}">${escapeHtml(c.code)}</span>
      <span class="item-sender">${sender}</span>
      <span class="item-time">${relTime(c.received_at)}</span>
      <span class="item-message">${message}</span>
      <button type="button" class="item-del" title="删除此条" data-del="${c.id}" aria-label="删除">✕</button>
    </li>`;
  }).join('');
}

function startPolling() {
  stopPolling();
  state.pollTimer = setInterval(() => refreshCodes(false), POLL_INTERVAL);
  state.timeTimer = setInterval(() => {
    // 仅刷新相对时间显示
    const list = filteredCodes();
    document.querySelectorAll('.item-time').forEach((el, i) => {
      if (list[i]) el.textContent = relTime(list[i].received_at);
    });
    const latest = state.codes[0];
    if (latest) {
      const sender = latest.sender ? `来自 ${latest.sender}` : '未知发送方';
      els.latestMeta.textContent = `${sender} · ${relTime(latest.received_at)}`;
    }
  }, 15000);
}

function stopPolling() {
  clearInterval(state.pollTimer);
  clearInterval(state.timeTimer);
  state.pollTimer = null;
  state.timeTimer = null;
}

/* ---------- 通知（可选） ---------- */
function maybeAskNotification() {
  if (!('Notification' in window)) return;
  if (Notification.permission === 'default') {
    Notification.requestPermission().catch(() => {});
  }
}

function notifyNewCode(code) {
  if (!('Notification' in window) || Notification.permission !== 'granted') return;
  if (!document.hidden) return;
  try {
    const n = new Notification('收到新验证码', {
      body: `${code.code}${code.sender ? ` · 来自 ${code.sender}` : ''}`,
      icon: '/icon.svg',
      tag: 'yzcode-latest',
    });
    n.onclick = () => { window.focus(); n.close(); };
  } catch { /* 忽略 */ }
}

/* ---------- 事件绑定 ---------- */
function bindEvents() {
  els.tabLogin.addEventListener('click', () => setAuthMode('login'));
  els.tabRegister.addEventListener('click', () => setAuthMode('register'));
  els.authForm.addEventListener('submit', submitAuth);

  els.logoutBtn.addEventListener('click', async () => {
    els.logoutBtn.disabled = true;
    try { await api('/api/logout', { method: 'POST' }); } catch { /* 忽略 */ }
    els.logoutBtn.disabled = false;
    toast('已退出登录');
    forceLogout();
  });

  // 复制最新验证码
  const doCopyLatest = async () => {
    const latest = state.codes[0];
    if (!latest) return;
    const ok = await copyText(latest.code);
    if (ok) {
      els.copyBtnText.textContent = '已复制 ✓';
      toast(`验证码 ${latest.code} 已复制`);
      setTimeout(() => { els.copyBtnText.textContent = '复制验证码'; }, 1600);
    } else {
      toast('复制失败，请手动复制', 'err');
    }
  };
  els.copyBtn.addEventListener('click', doCopyLatest);
  els.latestCode.addEventListener('click', doCopyLatest);

  // 关键字过滤
  els.filterInput.addEventListener('input', () => {
    state.filter = els.filterInput.value;
    renderHistory();
  });

  // 列表内点击：复制 / 删除
  els.historyList.addEventListener('click', async (e) => {
    const copyEl = e.target.closest('[data-copy]');
    if (copyEl) {
      const ok = await copyText(copyEl.dataset.copy);
      toast(ok ? `验证码 ${copyEl.dataset.copy} 已复制` : '复制失败，请手动复制', ok ? 'ok' : 'err');
      return;
    }
    const delBtn = e.target.closest('[data-del]');
    if (delBtn) {
      const id = Number(delBtn.dataset.del);
      delBtn.disabled = true;
      try {
        await api(`/api/codes/${id}`, { method: 'DELETE' });
        state.codes = state.codes.filter((c) => c.id !== id);
        renderLatest(false);
        renderHistory();
        toast('已删除');
      } catch (err) {
        toast(err.message, 'err');
        delBtn.disabled = false;
      }
    }
  });
  els.historyList.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.matches('[data-copy]')) e.target.click();
  });

  // 清空全部（需确认）
  els.clearAllBtn.addEventListener('click', () => {
    if (state.codes.length === 0) {
      toast('当前没有可清空的记录');
      return;
    }
    els.modalOverlay.classList.remove('hidden');
  });
  els.modalCancel.addEventListener('click', () => els.modalOverlay.classList.add('hidden'));
  els.modalOverlay.addEventListener('click', (e) => {
    if (e.target === els.modalOverlay) els.modalOverlay.classList.add('hidden');
  });
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') els.modalOverlay.classList.add('hidden');
  });
  els.modalConfirm.addEventListener('click', async () => {
    els.modalConfirm.disabled = true;
    try {
      await api('/api/codes', { method: 'DELETE' });
      state.codes = [];
      state.latestId = null;
      renderLatest(false);
      renderHistory();
      toast('已清空全部历史记录');
    } catch (err) {
      toast(err.message, 'err');
    } finally {
      els.modalConfirm.disabled = false;
      els.modalOverlay.classList.add('hidden');
    }
  });

  // 页面回到前台时立即刷新一次
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && state.token && !els.dashView.classList.contains('hidden')) {
      refreshCodes(false);
    }
  });
}

/* ---------- 启动 ---------- */
async function boot() {
  bindEvents();
  setAuthMode('login');
  if (!state.token) {
    showAuth();
    return;
  }
  try {
    const data = await api('/api/me');
    state.username = data.username;
    showDash();
  } catch {
    forceLogout();
  }
}

boot();
