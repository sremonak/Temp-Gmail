/* ============================================================
   Temp Gmail — UI controller
   ============================================================ */
import { createClient } from './mail-api.js';

const $ = (id) => document.getElementById(id);

const els = {
  menuBtn: $('menuBtn'), sidebar: $('sidebar'), sidebarBackdrop: $('sidebarBackdrop'),
  themeBtn: $('themeBtn'), themeIcon: $('themeIcon'),
  refreshTopBtn: $('refreshTopBtn'), avatar: $('avatar'),
  searchInput: $('searchInput'),
  newAddressBtn: $('newAddressBtn'), newAddrBtn2: $('newAddrBtn2'),
  copyBtn: $('copyBtn'), copyBtnText: $('copyBtnText'),
  addressValue: $('addressValue'), createdAtLabel: $('createdAtLabel'),
  livePill: $('livePill'), livePillText: $('livePillText'),
  providerDot: $('providerDot'), providerLabel: $('providerLabel'),
  banner: $('banner'), bannerText: $('bannerText'), bannerAction: $('bannerAction'), bannerClose: $('bannerClose'),
  navList: $('navList'), tabs: $('tabs'),
  inboxBadge: $('inboxBadge'), unreadBadge: $('unreadBadge'),
  msgCount: $('msgCount'), refreshBtn: $('refreshBtn'), clearBtn: $('clearBtn'),
  mailList: $('mailList'), skeletons: $('skeletons'), emptyState: $('emptyState'),
  emptyTitle: $('emptyTitle'), emptyHint: $('emptyHint'),
  reader: $('reader'), readerBack: $('readerBack'), readerClose: $('readerClose'),
  readerStar: $('readerStar'), readerDelete: $('readerDelete'),
  readerSubject: $('readerSubject'), readerAvatar: $('readerAvatar'),
  readerFromName: $('readerFromName'), readerFromAddr: $('readerFromAddr'),
  readerTo: $('readerTo'), readerTime: $('readerTime'),
  readerAttachments: $('readerAttachments'), readerBody: $('readerBody'), readerFrame: $('readerFrame'),
  modalBackdrop: $('modalBackdrop'), modalTitle: $('modalTitle'), modalText: $('modalText'),
  modalCancel: $('modalCancel'), modalOk: $('modalOk'),
  toast: $('toast'),
};

const state = {
  client: null,
  messages: [],
  filter: 'inbox',
  query: '',
  openId: null,
  busy: false,
  pollTimer: null,
  starred: new Set(JSON.parse(localStorage.getItem('tempgmail.starred') || '[]')),
  prevCount: 0,
  firstLoad: true,
};

/* ---------------- helpers ---------------- */
const rtf = new Intl.RelativeTimeFormat('bn', { numeric: 'auto' });
const dtf = new Intl.DateTimeFormat('bn-BD', { dateStyle: 'medium', timeStyle: 'short' });

function relTime(iso) {
  const diff = (new Date(iso).getTime() - Date.now()) / 1000;
  const abs = Math.abs(diff);
  if (abs < 60) return rtf.format(Math.round(diff), 'second');
  if (abs < 3600) return rtf.format(Math.round(diff / 60), 'minute');
  if (abs < 86400) return rtf.format(Math.round(diff / 3600), 'hour');
  if (abs < 604800) return rtf.format(Math.round(diff / 86400), 'day');
  return new Intl.DateTimeFormat('bn-BD', { day: 'numeric', month: 'short' }).format(new Date(iso));
}

function escapeHtml(s) {
  return String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function senderOf(m) {
  const name = m.from?.name || '';
  const addr = m.from?.address || '';
  return { name: name || addr.split('@')[0] || 'অজানা', addr };
}

function avatarColor(seed) {
  const palette = ['#5f6368', '#1a73e8', '#d93025', '#188038', '#9334e6', '#e37400', '#007b83', '#c5221f'];
  let h = 0;
  for (const c of String(seed)) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return palette[h % palette.length];
}

function saveStarred() {
  localStorage.setItem('tempgmail.starred', JSON.stringify([...state.starred]));
}

function showToast(text, ms = 2200) {
  els.toast.textContent = text;
  els.toast.classList.add('show');
  clearTimeout(showToast._t);
  showToast._t = setTimeout(() => els.toast.classList.remove('show'), ms);
}

function showBanner(text, { action = false, persist = false } = {}) {
  els.bannerText.textContent = text;
  els.bannerAction.hidden = !action;
  els.banner.classList.remove('hidden');
  clearTimeout(showBanner._t);
  if (!persist) showBanner._t = setTimeout(() => els.banner.classList.add('hidden'), action ? 12000 : 5000);
}
function hideBanner() { els.banner.classList.add('hidden'); }

function confirmDialog(title, text, okLabel = 'ঠিক আছে') {
  return new Promise((resolve) => {
    els.modalTitle.textContent = title;
    els.modalText.textContent = text;
    els.modalOk.textContent = okLabel;
    els.modalBackdrop.classList.remove('hidden');
    const done = (v) => {
      els.modalBackdrop.classList.add('hidden');
      els.modalOk.onclick = els.modalCancel.onclick = null;
      resolve(v);
    };
    els.modalOk.onclick = () => done(true);
    els.modalCancel.onclick = () => done(false);
    els.modalBackdrop.onclick = (e) => { if (e.target === els.modalBackdrop) done(false); };
  });
}

/* ---------------- theme ---------------- */
function initTheme() {
  const saved = localStorage.getItem('tempgmail.theme');
  const theme = saved || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  setTheme(theme);
}
function setTheme(t) {
  document.documentElement.dataset.theme = t;
  localStorage.setItem('tempgmail.theme', t);
  els.themeIcon.innerHTML = `<use href="#i-${t === 'dark' ? 'sun' : 'moon'}"/>`;
}
els.themeBtn.addEventListener('click', () => {
  setTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
});

/* ---------------- address card ---------------- */
function renderAddress() {
  const acc = state.client.account;
  if (!acc) return;
  els.addressValue.textContent = acc.address;
  els.avatar.textContent = (acc.address[0] || 'T').toUpperCase();
  els.createdAtLabel.textContent = `তৈরি: ${dtf.format(new Date(acc.createdAt))}`;

  if (state.client.demo) {
    els.livePill.classList.add('demo');
    els.livePillText.textContent = 'ডেমো';
    els.providerDot.className = 'live-dot warn';
    els.providerLabel.textContent = 'অফলাইন — ডেমো মোড';
    showBanner('ইন্টারনেট সংযোগ পাওয়া যায়নি। এখন নমুনা (ডেমো) মেইল দেখানো হচ্ছে।', { action: true, persist: true });
    els.bannerAction.onclick = async () => {
      els.bannerAction.disabled = true;
      state.client.demo = false;
      await bootstrap({ forceCreate: true });
      els.bannerAction.disabled = false;
    };
  } else {
    els.livePill.classList.remove('demo');
    els.livePillText.textContent = 'লাইভ';
    els.providerDot.className = 'live-dot';
    els.providerLabel.textContent = `সংযোগ: ${state.client.provider}`;
  }
}

async function copyAddress() {
  const text = state.client?.address || '';
  if (!text) return;
  let ok = false;
  try {
    await navigator.clipboard.writeText(text);
    ok = true;
  } catch {
    try {
      const ta = document.createElement('textarea');
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      ok = document.execCommand('copy');
      ta.remove();
    } catch { /* ignore */ }
  }
  if (ok) {
    els.copyBtn.classList.add('copied');
    els.copyBtnText.textContent = 'কপি হয়েছে';
    els.copyBtn.querySelector('use')?.setAttribute('href', '#i-check');
    showToast('ঠিকানা কপি হয়েছে ✓');
    setTimeout(() => {
      els.copyBtn.classList.remove('copied');
      els.copyBtnText.textContent = 'কপি';
      els.copyBtn.querySelector('use')?.setAttribute('href', '#i-copy');
    }, 1600);
  } else {
    showToast('কপি করা যায়নি — ঠিকানাটি টাচ করে ধরে কপি করুন');
  }
}

async function requestNewAddress() {
  const yes = await confirmDialog(
    'নতুন ঠিকানা তৈরি করবেন?',
    'বর্তমান ঠিকানা ও তার সব মেইল স্থায়ীভাবে মুছে যাবে। নতুন একটি অস্থায়ী ঠিকানা তৈরি হবে।',
    'হ্যাঁ, নতুন ঠিকানা'
  );
  if (!yes) return;
  await newAddress();
}

async function newAddress() {
  if (state.busy) return;
  state.busy = true;
  try {
    closeReader();
    els.skeletons.classList.remove('hidden');
    els.emptyState.classList.add('hidden');
    els.mailList.querySelectorAll('.mail-row').forEach(n => n.remove());
    const acc = await state.client.reset();
    state.messages = [];
    state.prevCount = 0;
    renderAddress();
    renderList();
    showToast('নতুন ঠিকানা তৈরি হয়েছে');
  } catch (e) {
    console.error(e);
    showBanner('নতুন ঠিকানা তৈরি করা যায়নি। আবার চেষ্টা করুন।', { action: true });
  } finally {
    state.busy = false;
    els.skeletons.classList.add('hidden');
  }
}

/* ---------------- list ---------------- */
function visibleMessages() {
  let list = state.messages;
  if (state.filter === 'unread') list = list.filter(m => !m.seen);
  if (state.filter === 'starred') list = list.filter(m => state.starred.has(m.id));
  if (state.query) {
    const q = state.query.toLowerCase();
    list = list.filter(m =>
      (m.subject || '').toLowerCase().includes(q) ||
      (m.intro || '').toLowerCase().includes(q) ||
      (m.from?.name || '').toLowerCase().includes(q) ||
      (m.from?.address || '').toLowerCase().includes(q)
    );
  }
  return list;
}

function renderBadges() {
  const total = state.messages.length;
  const unread = state.messages.filter(m => !m.seen).length;
  const set = (el, n) => { el.hidden = n <= 0; el.textContent = n > 99 ? '99+' : n; };
  set(els.inboxBadge, total);
  set(els.unreadBadge, unread);
  els.msgCount.textContent = total ? `${visibleMessages().length} / ${total} টি মেইল` : '';
}

function renderList() {
  els.skeletons.classList.add('hidden');
  const rows = els.mailList.querySelectorAll('.mail-row');
  rows.forEach(n => n.remove());

  const list = visibleMessages();
  renderBadges();

  if (!list.length) {
    els.emptyState.classList.remove('hidden');
    if (state.query) {
      els.emptyTitle.textContent = 'কিছু পাওয়া যায়নি';
      els.emptyHint.textContent = 'অন্য কোনো শব্দ দিয়ে খুঁজুন।';
    } else if (state.filter === 'starred') {
      els.emptyTitle.textContent = 'কোনো স্টারড মেইল নেই';
      els.emptyHint.textContent = 'মেইলের পাশে ★ আইকনে ট্যাপ করলে এখানে জমা হবে।';
    } else if (state.filter === 'unread') {
      els.emptyTitle.textContent = 'সব মেইল পড়া হয়ে গেছে';
      els.emptyHint.textContent = 'নতুন মেইল এলে এখানে দেখা যাবে।';
    } else {
      els.emptyTitle.textContent = 'এখনো কোনো মেইল আসেনি';
      els.emptyHint.textContent = 'উপরের ঠিকানাটি কপি করে যেকোনো ওয়েবসাইটে ব্যবহার করুন — মেইল এলে স্বয়ংক্রিয়ভাবে এখানে দেখা যাবে।';
    }
    return;
  }
  els.emptyState.classList.add('hidden');

  const frag = document.createDocumentFragment();
  list.forEach((m, i) => {
    const from = senderOf(m);
    const starred = state.starred.has(m.id);
    const row = document.createElement('div');
    row.className = 'mail-row' + (m.seen ? '' : ' unread');
    row.dataset.id = m.id;
    row.style.animationDelay = `${Math.min(i * 30, 240)}ms`;

    row.innerHTML = `
      <button class="row-star ${starred ? 'on' : ''}" aria-label="স্টার">
        <svg class="ic"><use href="#i-${starred ? 'star-on' : 'star'}"/></svg>
      </button>
      <div class="row-avatar" style="background:${avatarColor(from.addr || from.name)}">${escapeHtml((from.name[0] || '?').toUpperCase())}</div>
      <div class="row-body">
        <div class="row-top">
          <span class="row-from">${escapeHtml(from.name)}</span>
          <span class="row-time">${escapeHtml(relTime(m.createdAt))}</span>
        </div>
        <div class="row-subject">${escapeHtml(m.subject || '(বিষয়হীন)')}</div>
        <div class="row-snippet">${escapeHtml(m.intro || m.text || '')}</div>
      </div>
      ${m.seen ? '' : '<span class="row-unread-dot"></span>'}
      <div class="row-actions">
        <button class="icon-btn act-star" aria-label="স্টার"><svg class="ic"><use href="#i-${starred ? 'star-on' : 'star'}"/></svg></button>
        <button class="icon-btn act-del" aria-label="মুছুন"><svg class="ic"><use href="#i-trash"/></svg></button>
      </div>`;

    row.addEventListener('click', (e) => {
      if (e.target.closest('.row-star, .act-star')) { toggleStar(m.id); return; }
      if (e.target.closest('.act-del')) { deleteOne(m.id); return; }
      openReader(m.id);
    });
    frag.appendChild(row);
  });
  els.mailList.appendChild(frag);
}

function toggleStar(id) {
  if (state.starred.has(id)) state.starred.delete(id);
  else state.starred.add(id);
  saveStarred();
  renderList();
  if (state.openId === id) syncReaderStar();
}

async function deleteOne(id) {
  const m = state.messages.find(x => x.id === id);
  try {
    await state.client.deleteMessage(id);
    state.messages = state.messages.filter(x => x.id !== id);
    state.starred.delete(id); saveStarred();
    if (state.openId === id) closeReader();
    renderList();
    showToast('মেইলটি মুছে ফেলা হয়েছে');
  } catch (e) {
    console.error(e);
    showToast('মুছতে সমস্যা হয়েছে — আবার চেষ্টা করুন');
  }
}

async function clearInbox() {
  const n = state.messages.length;
  if (!n) { showToast('ইনবক্স ইতিমধ্যেই খালি'); return; }
  const yes = await confirmDialog('ইনবক্স খালি করবেন?', `${n} টি মেইল স্থায়ীভাবে মুছে ফেলা হবে।`, 'সব মুছুন');
  if (!yes) return;
  let failed = 0;
  const deletedIds = [];
  for (const m of [...state.messages]) {
    try { await state.client.deleteMessage(m.id); deletedIds.push(m.id); }
    catch { failed++; }
  }
  const deleted = new Set(deletedIds);
  state.messages = state.messages.filter(m => !deleted.has(m.id));
  for (const id of deleted) state.starred.delete(id);
  saveStarred();
  closeReader();
  renderList();
  showToast(failed ? `${failed} টি মুছতে সমস্যা হয়েছে` : 'ইনবক্স খালি হয়েছে');
}

/* ---------------- reader ---------------- */
function buildBodyHtml(m) {
  const raw = (m.html && m.html.length) ? m.html.join('\n') : null;
  if (raw) {
    return `<base target="_blank"><style>body{margin:0;font-family:Roboto,'Noto Sans Bengali',Arial,sans-serif;color:#202124}</style>${raw}`;
  }
  const text = escapeHtml(m.text || m.intro || '(খালি)');
  return `<base target="_blank"><style>body{margin:0;padding:14px;font-family:Roboto,'Noto Sans Bengali',Arial,sans-serif;color:#202124;white-space:pre-wrap}</style>${text}`;
}

async function openReader(id) {
  const m = state.messages.find(x => x.id === id);
  if (!m) return;
  state.openId = id;

  const from = senderOf(m);
  els.readerSubject.textContent = m.subject || '(বিষয়হীন)';
  els.readerFromName.textContent = from.name;
  els.readerFromAddr.textContent = from.addr ? `<${from.addr}>` : '';
  els.readerTo.textContent = `প্রাপক: ${(m.to || []).map(t => t.address).join(', ') || state.client.address}`;
  els.readerTime.textContent = dtf.format(new Date(m.createdAt));
  els.readerAvatar.textContent = (from.name[0] || '?').toUpperCase();
  els.readerAvatar.style.background = avatarColor(from.addr || from.name);
  els.readerFrame.srcdoc = buildBodyHtml(m);

  // attachments
  const atts = m.attachments || [];
  if (atts.length) {
    els.readerAttachments.classList.remove('hidden');
    els.readerAttachments.innerHTML = '';
    for (const a of atts) {
      const chip = document.createElement('button');
      chip.className = 'att-chip';
      chip.innerHTML = `<svg class="ic"><use href="#i-clip"/></svg><span class="att-name">${escapeHtml(a.filename || 'file')}</span><span>(${formatSize(a.size)})</span>`;
      chip.onclick = () => downloadAtt(m.id, a);
      els.readerAttachments.appendChild(chip);
    }
  } else {
    els.readerAttachments.classList.add('hidden');
    els.readerAttachments.innerHTML = '';
  }

  syncReaderStar();
  els.reader.classList.remove('hidden');
  document.body.style.overflow = 'hidden';

  if (!m.seen) {
    m.seen = true;
    renderList();
    try { await state.client.markRead(m.id, true); } catch { /* ignore */ }
  }
}

function syncReaderStar() {
  const on = state.starred.has(state.openId);
  els.readerStar.classList.toggle('on', on);
  els.readerStar.querySelector('use')?.setAttribute('href', on ? '#i-star-on' : '#i-star');
}

function closeReader() {
  if (els.reader.classList.contains('hidden')) return;
  els.reader.classList.add('hidden');
  els.readerFrame.srcdoc = '';
  state.openId = null;
  document.body.style.overflow = '';
  renderList();
}

function formatSize(n) {
  if (!n) return '';
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(1) + ' KB';
  return (n / 1048576).toFixed(1) + ' MB';
}

async function downloadAtt(msgId, att) {
  try {
    showToast('ডাউনলোড শুরু হচ্ছে...');
    const res = await state.client.downloadAttachment(msgId, att);
    if (!res) { showToast('ডেমো মোডে ডাউনলোড সম্ভব নয়'); return; }
    const url = URL.createObjectURL(res.blob);
    const a = document.createElement('a');
    a.href = url; a.download = res.filename;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
  } catch (e) {
    console.error(e);
    showToast('ডাউনলোড ব্যর্থ হয়েছে');
  }
}

/* ---------------- polling ---------------- */
async function refresh({ silent = true } = {}) {
  if (state.busy || !state.client) return;
  state.busy = true;
  els.refreshBtn.querySelector('.ic')?.classList.add('spinning');
  try {
    const msgs = await state.client.listMessages();
    const before = state.messages.length;
    const prevUnread = state.messages.filter(m => !m.seen).length;
    state.messages = (msgs || []).sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt));

    const nowUnread = state.messages.filter(m => !m.seen).length;
    if (!state.firstLoad && nowUnread > prevUnread) {
      showToast('📨 নতুন মেইল এসেছে!');
    }
    state.firstLoad = false;
    if (before === 0 && state.messages.length === 0 && state.filter === 'inbox' && !state.query) {
      els.emptyState.classList.remove('hidden');
      els.skeletons.classList.add('hidden');
    }
    renderList();
    hideBanner();
  } catch (e) {
    console.warn('refresh failed:', e);
    if (e.rateLimited) {
      // back off silently
    } else if (!state.client.demo) {
      showBanner('ইনবক্স রিফ্রেশ করা যায়নি — সংযোগ পরীক্ষা করুন।', { action: true });
      els.bannerAction.onclick = () => refresh({ silent: false });
    }
  } finally {
    state.busy = false;
    els.refreshBtn.querySelector('.ic')?.classList.remove('spinning');
  }
}

function startPolling() {
  stopPolling();
  state.pollTimer = setInterval(() => {
    if (document.visibilityState === 'visible') refresh({ silent: true });
  }, 6000);
}
function stopPolling() { if (state.pollTimer) clearInterval(state.pollTimer); state.pollTimer = null; }

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') refresh({ silent: true });
});

/* ---------------- filters / search ---------------- */
function setFilter(f) {
  state.filter = f;
  els.navList.querySelectorAll('.nav-item').forEach(b => b.classList.toggle('active', b.dataset.filter === f));
  els.tabs.querySelectorAll('.tab').forEach(b => b.classList.toggle('active', b.dataset.filter === f));
  renderList();
}

els.navList.addEventListener('click', (e) => {
  const btn = e.target.closest('.nav-item');
  if (btn) { setFilter(btn.dataset.filter); closeSidebar(); }
});
els.tabs.addEventListener('click', (e) => {
  const btn = e.target.closest('.tab');
  if (btn) setFilter(btn.dataset.filter);
});

let searchDebounce;
els.searchInput.addEventListener('input', () => {
  clearTimeout(searchDebounce);
  searchDebounce = setTimeout(() => {
    state.query = els.searchInput.value.trim();
    renderList();
  }, 180);
});

/* ---------------- sidebar (mobile) ---------------- */
function openSidebar() {
  document.body.classList.add('sidebar-open');
  els.sidebarBackdrop.hidden = false;
}
function closeSidebar() {
  document.body.classList.remove('sidebar-open');
  els.sidebarBackdrop.hidden = true;
}
els.menuBtn.addEventListener('click', () => {
  document.body.classList.contains('sidebar-open') ? closeSidebar() : openSidebar();
});
els.sidebarBackdrop.addEventListener('click', closeSidebar);

/* ---------------- wiring ---------------- */
els.copyBtn.addEventListener('click', copyAddress);
els.newAddressBtn.addEventListener('click', requestNewAddress);
els.newAddrBtn2.addEventListener('click', requestNewAddress);
els.refreshBtn.addEventListener('click', () => refresh({ silent: false }));
els.refreshTopBtn.addEventListener('click', () => refresh({ silent: false }));
els.clearBtn.addEventListener('click', clearInbox);
els.bannerClose.addEventListener('click', hideBanner);

els.readerBack.addEventListener('click', closeReader);
els.readerClose.addEventListener('click', closeReader);
els.readerStar.addEventListener('click', () => state.openId && toggleStar(state.openId));
els.readerDelete.addEventListener('click', () => state.openId && deleteOne(state.openId));
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    if (!els.modalBackdrop.classList.contains('hidden')) els.modalCancel.click();
    else closeReader();
  }
});

/* ---------------- spin animation for refresh ---------------- */
const style = document.createElement('style');
style.textContent = `.spinning{animation:spin .8s linear infinite}@keyframes spin{to{transform:rotate(360deg)}}`;
document.head.appendChild(style);

/* ---------------- boot ---------------- */
async function bootstrap({ forceCreate = false } = {}) {
  els.skeletons.classList.remove('hidden');
  els.emptyState.classList.add('hidden');
  try {
    if (forceCreate || !state.client) state.client = createClient();
    const acc = await state.client.init();
    renderAddress();
    await refresh({ silent: true });
    startPolling();
  } catch (e) {
    console.error('boot failed:', e);
    showBanner('অ্যাপ চালু করা যায়নি। আবার চেষ্টা করুন।', { action: true });
    els.bannerAction.onclick = () => bootstrap({ forceCreate: true });
    els.skeletons.classList.add('hidden');
    els.emptyState.classList.remove('hidden');
  }
}

initTheme();
bootstrap();
