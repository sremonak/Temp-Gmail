/* ============================================================
   Temp Gmail — mail API client
   Providers: mail.tm (primary), mail.gw (fallback), demo mode.
   No API key required. CORS-enabled public APIs.
   ============================================================ */

const PROVIDERS = [
  { id: 'mail.tm', base: 'https://api.mail.tm' },
  { id: 'mail.gw', base: 'https://api.mail.gw' },
];

const STORAGE_KEY = 'tempgmail.account.v1';

const WORD_A = ['swift','mint','coral','nova','pixel','ember','lunar','cobalt','amber','quartz','zephyr','iris','solar','echo','frost','terra'];
const WORD_B = ['falcon','river','tiger','otter','comet','maple','stone','drift','spark','wave','fox','hawk','dune','lynx','raven','moss'];

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }
function randLocalPart() {
  return `${pick(WORD_A)}.${pick(WORD_B)}${Math.floor(1000 + Math.random() * 9000)}`;
}
function randPassword() {
  const chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let out = '';
  const buf = new Uint32Array(18);
  crypto.getRandomValues(buf);
  for (let i = 0; i < 18; i++) out += chars[buf[i] % chars.length];
  return out + '7!';
}

async function postJSON(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${url} -> ${res.status}`);
  return res.json();
}

/* ------------------------------------------------------------
   Real client (mail.tm / mail.gw)
------------------------------------------------------------ */
class RealClient {
  constructor() {
    this.demo = false;
    this.base = null;
    this.provider = null;
    this.account = null; // {address,password,token,id,createdAt}
  }

  loadSaved() {
    try { return JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null'); }
    catch { return null; }
  }
  save() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        base: this.base, provider: this.provider, ...this.account,
      }));
    } catch { /* private mode */ }
  }
  clearSaved() {
    try { localStorage.removeItem(STORAGE_KEY); } catch { /* ignore */ }
  }

  get address() { return this.account?.address || ''; }

  /* Restore previous mailbox or create a fresh one */
  async init() {
    const saved = this.loadSaved();
    if (saved && saved.base && saved.address && saved.password && !saved.demo) {
      this.base = saved.base;
      this.provider = saved.provider || 'mail.tm';
      this.account = {
        address: saved.address, password: saved.password,
        token: saved.token, id: saved.id, createdAt: saved.createdAt || Date.now(),
      };
      try {
        await this.retoken();
        await this.listMessages(); // validate token works
        return this.account;
      } catch (e) {
        console.warn('Restored session failed, recreating:', e);
        this.account = null;
      }
    }
    return this.createAccount();
  }

  async createAccount() {
    this.clearSaved();
    let lastErr = null;
    for (const p of PROVIDERS) {
      try {
        const domRes = await fetch(`${p.base}/domains`, { headers: { Accept: 'application/json' } });
        if (!domRes.ok) throw new Error(`domains ${domRes.status}`);
        const domData = await domRes.json();
        const domains = (domData['hydra:member'] || domData.member || [])
          .filter(d => d && d.domain && d.isActive !== false);
        if (!domains.length) throw new Error('no domains');
        const domain = domains[0].domain;
        const address = `${randLocalPart()}@${domain}`;
        const password = randPassword();

        await postJSON(`${p.base}/accounts`, { address, password });
        const tok = await postJSON(`${p.base}/token`, { address, password });

        this.base = p.base;
        this.provider = p.id;
        this.account = {
          address, password,
          token: tok.token,
          id: tok.id || null,
          createdAt: Date.now(),
        };
        this.save();
        return this.account;
      } catch (e) {
        console.warn(`${p.id} failed:`, e);
        lastErr = e;
      }
    }
    // Both providers unreachable → demo mode so the app still works
    this.base = null;
    this.provider = 'demo';
    this.account = {
      address: `demo.${randLocalPart()}@tempgmail.demo`,
      password: '', token: '', id: 'demo', createdAt: Date.now(),
    };
    this.save();
    this.demo = true;
    this._demoMessages = seedDemoMessages();
    return this.account;
  }

  async retoken() {
    const tok = await postJSON(`${this.base}/token`, {
      address: this.account.address,
      password: this.account.password,
    });
    this.account.token = tok.token;
    this.save();
  }

  authHeaders() {
    return { Authorization: `Bearer ${this.account.token}`, Accept: 'application/json' };
  }

  async api(path, opts = {}, allowRetry = true) {
    if (this.demo) throw new Error('demo');
    const res = await fetch(this.base + path, {
      ...opts,
      headers: { ...this.authHeaders(), ...(opts.headers || {}) },
    });
    if (res.status === 401 && allowRetry) {
      await this.retoken();
      return this.api(path, opts, false);
    }
    if (res.status === 429) { const err = new Error('rate-limited'); err.rateLimited = true; throw err; }
    if (!res.ok) throw new Error(`API ${res.status}`);
    if (res.status === 204) return null;
    return res.json();
  }

  async listMessages() {
    if (this.demo) return this._demoMessages;
    const data = await this.api('/messages?page=1');
    return data['hydra:member'] || data.member || [];
  }

  async getMessage(id) {
    if (this.demo) return this._demoMessages.find(m => m.id === id) || null;
    return this.api(`/messages/${id}`);
  }

  async deleteMessage(id) {
    if (this.demo) { this._demoMessages = this._demoMessages.filter(m => m.id !== id); return; }
    await this.api(`/messages/${id}`, { method: 'DELETE' });
  }

  async markRead(id, seen = true) {
    if (this.demo) {
      const m = this._demoMessages.find(x => x.id === id);
      if (m) m.seen = seen;
      return;
    }
    await this.api(`/messages/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/merge-patch+json' },
      body: JSON.stringify({ seen }),
    });
  }

  /* Attachment download → returns {blob, filename} or null */
  async downloadAttachment(msgId, att) {
    if (this.demo) return null;
    const res = await fetch(`${this.base}/messages/${msgId}/attachment/${att.id}`, {
      headers: this.authHeaders(),
    });
    if (!res.ok) throw new Error(`attachment ${res.status}`);
    return { blob: await res.blob(), filename: att.filename || 'attachment' };
  }

  /* Delete mailbox and start fresh */
  async reset() {
    if (!this.demo && this.account?.id) {
      try { await this.api(`/accounts/${this.account.id}`, { method: 'DELETE' }); } catch { /* best effort */ }
    }
    this.demo = false;
    return this.createAccount();
  }
}

/* ------------------------------------------------------------
   Demo messages (used only when both providers are offline)
------------------------------------------------------------ */
function seedDemoMessages() {
  const now = Date.now();
  const mk = (i, from, name, subject, body, minsAgo) => ({
    id: `demo-${i}`,
    from: { address: from, name },
    to: [{ address: 'demo@tempgmail.demo', name: '' }],
    subject,
    intro: body.replace(/<[^>]+>/g, '').slice(0, 120),
    seen: false,
    hasAttachments: false,
    createdAt: new Date(now - minsAgo * 60000).toISOString(),
    html: [body],
    text: body.replace(/<[^>]+>/g, ''),
    attachments: [],
    demo: true,
  });
  return [
    mk(1, 'no-reply@accounts.google.com', 'Google',
      'আপনার Google ভেরিফিকেশন কোড: 482913',
      `<div style="font-family:Arial,sans-serif;color:#202124">
        <h2 style="color:#d93025">Temp Gmail</h2>
        <p>আপনার ভেরিফিকেশন কোড:</p>
        <p style="font-size:32px;letter-spacing:6px;font-weight:bold">482913</p>
        <p style="color:#5f6368;font-size:13px">এই কোডটি ১০ মিনিটের জন্য কার্যকর। কেউ আপনার কাছ থেকে এটি চাইলে শেয়ার করবেন না।</p>
      </div>`, 2),
    mk(2, 'security@github.com', 'GitHub',
      'Your GitHub verification code is 731-204',
      `<div style="font-family:Arial,sans-serif;color:#24292f">
        <h2>Verify your email address</h2>
        <p>Enter this code to continue:</p>
        <p style="font-size:30px;font-weight:bold;background:#f6f8fa;padding:12px;text-align:center;border-radius:8px">731-204</p>
        <p style="color:#57606a;font-size:13px">If you didn't request this, you can safely ignore this email.</p>
      </div>`, 9),
    mk(3, 'hello@newsletter.dev', 'Dev Weekly',
      'সাপ্তাহিক নিউজলেটার — সেরা ৫টি টুল',
      `<div style="font-family:Arial,sans-serif;color:#202124">
        <h2>এই সপ্তাহের সেরা টুল 🛠️</h2>
        <ol>
          <li>নতুন CSS container queries</li>
          <li>Node.js 22-এর পারফরম্যান্স উন্নতি</li>
          <li>একটি দারুণ ওপেন-সোর্স ডাটাবেস GUI</li>
          <li>ওয়ার্কফ্লো অটোমেশন টিপস</li>
          <li>এই মাসের সালারি রিপোর্ট</li>
        </ol>
        <p style="color:#5f6368;font-size:13px">এই মেইলটি ডেমো মোডে পাওয়া যাচ্ছে — সঞ্চয় করা নমুনা।</p>
      </div>`, 47),
  ];
}

export function createClient() { return new RealClient(); }
