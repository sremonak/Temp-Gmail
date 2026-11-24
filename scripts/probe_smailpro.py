#!/usr/bin/env python3
"""Probe SmailPro / Sonjj endpoints to discover which flows really work
and what the exact response schemas are. Runs from GitHub Actions runner
(which has unrestricted internet). Stdlib only.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import http.cookiejar

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
SITEKEY = "0x4AAAAAAABIS_gEec2IwOhI"
BASE = "https://smailpro.com"
SONJJ_V1 = "https://api.sonjj.com/v1"
SONJJ_EMAIL = "https://api.sonjj.com/email/gm"
FGSI = "https://fgsi.dpdns.org/api/tools/cfclearance/turnstile-min"
RAPID_KEY = "f871a22852mshc3ccc49e34af1e8p126682jsn734696f1f081"

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def show(name, status, body, limit=1800):
    print(f"\n### {name} -> HTTP {status}", flush=True)
    if isinstance(body, bytes):
        try:
            txt = body.decode("utf-8", "replace")
            obj = json.loads(txt)
            print(json.dumps(obj, ensure_ascii=False, indent=1)[:limit], flush=True)
        except Exception:
            print(repr(body[:limit]), flush=True)
    else:
        print(repr(body)[:limit], flush=True)


def req(method, url, data=None, headers=None, timeout=60):
    h = {"User-Agent": UA, "Accept": "application/json, text/plain, */*"}
    if headers:
        h.update(headers)
    body = None
    if data is not None:
        body = json.dumps(data).encode()
        h.setdefault("Content-Type", "application/json")
    r = urllib.request.Request(url, data=body, headers=h, method=method)
    try:
        with opener.open(r, timeout=timeout) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        try:
            return e.code, e.read()
        except Exception:
            return e.code, b""
    except Exception as e:
        return 0, str(e).encode()


def cookies_str():
    return "; ".join(f"{c.name}={c.value}" for c in cj)


def main():
    out = {}

    # ---------- 0. mail.tm sanity ----------
    st, b = req("GET", "https://api.mail.tm/domains")
    show("mail.tm/domains", st, b, 400)

    # ---------- Flow B (no captcha): app/key -> email/gm/get -> check -> read ----------
    st, b = req("POST", f"{BASE}/app/key",
                data={"domain": "gmail.com", "username": "random",
                      "server": "server-1", "type": "alias"},
                headers={"x-g-token": "", "X-XSRF-TOKEN": "",
                         "Referer": f"{BASE}/temporary-email"})
    show("B1 POST /app/key", st, b)
    key = None
    try:
        j = json.loads(b)
        items = j.get("items")
        key = items if isinstance(items, str) else (items or {}).get("key") or (items or {}).get("token")
        if isinstance(items, dict):
            out["B1_items_keys"] = list(items.keys())
    except Exception:
        pass
    out["key"] = key
    print(f"\nextracted key: {key!r}", flush=True)

    if key:
        q = urllib.parse.urlencode({"key": key, "domain": "gmail.com",
                                    "username": "random", "server": "server-1",
                                    "type": "alias"})
        st, b = req("GET", f"{SONJJ_EMAIL}/get?{q}")
        show("B2 GET /email/gm/get", st, b)
        email_obj = None
        try:
            items = json.loads(b).get("items") or []
            email_obj = items[0] if items else None
            if isinstance(items, dict):
                email_obj = items
        except Exception:
            pass
        out["email_obj"] = email_obj
        print(f"\nemail_obj: {email_obj}", flush=True)

        if email_obj:
            addr = email_obj.get("email") or email_obj.get("address")
            ts = email_obj.get("timestamp")
            # list messages WITHOUT rapidapi key
            q2 = urllib.parse.urlencode({"key": key, "email": addr, "timestamp": ts})
            st, b = req("GET", f"{SONJJ_EMAIL}/check?{q2}")
            show("B3 GET /email/gm/check (no rapid key)", st, b)
            # list messages WITH rapidapi key
            q2b = urllib.parse.urlencode({"key": key, "email": addr, "timestamp": ts,
                                          "rapidapi-key": RAPID_KEY})
            st, b = req("GET", f"{SONJJ_EMAIL}/check?{q2b}")
            show("B3b GET /email/gm/check (rapid key)", st, b)

    # ---------- Flow A (captcha): session -> fgsi -> create -> inbox -> sonjj ----------
    st, b = req("GET", f"{BASE}/temporary-email")
    print(f"\n### A1 GET /temporary-email -> HTTP {st}, "
          f"cookies={cookies_str()!r}, len={len(b)}", flush=True)

    # A2: free turnstile solver
    qs = urllib.parse.urlencode({"apikey": "takashi0927192",
                                 "url": f"{BASE}/temporary-email",
                                 "sitekey": SITEKEY,
                                 "action": "smailpro_create"})
    st, b = req("GET", f"{FGSI}?{qs}", timeout=150)
    show("A2 fgsi solver", st, b, 700)
    token = None
    try:
        token = json.loads(b).get("data", {}).get("token")
    except Exception:
        pass
    print(f"\nturnstile token: {(token[:40] + '...') if token else None}", flush=True)

    # A3: create with captcha token
    cq = urllib.parse.urlencode({"username": "random", "type": "alias",
                                 "domain": "gmail.com", "server": "1"})
    hdr = {"Referer": f"{BASE}/temporary-email", "Cookie": cookies_str()}
    if token:
        hdr["x-captcha"] = token
        st, b = req("GET", f"{BASE}/app/create?{cq}", headers=hdr)
        show("A3 POST /app/create (with x-captcha)", st, b)
        try:
            out["created"] = json.loads(b)
        except Exception:
            pass
    # A3b: create WITHOUT captcha (does it work?)
    st, b = req("GET", f"{BASE}/app/create?{cq}",
                headers={"Referer": f"{BASE}/temporary-email", "Cookie": cookies_str()})
    show("A3b /app/create WITHOUT captcha", st, b, 700)

    created = out.get("created") or {}
    if created.get("address"):
        addr, ts, k = created["address"], created.get("timestamp"), created.get("key")
        # A4: inbox payload
        st, b = req("POST", f"{BASE}/app/inbox",
                    data=[{"address": addr, "timestamp": ts, "key": k}],
                    headers={"Referer": f"{BASE}/temporary-email", "Cookie": cookies_str()})
        show("A4 POST /app/inbox", st, b, 700)
        payload = None
        try:
            arr = json.loads(b)
            payload = arr[0].get("payload")
        except Exception:
            pass
        if payload:
            u = f"{SONJJ_V1}/temp_gmail/inbox?payload={urllib.parse.quote(payload)}"
            st, b = req("GET", u, headers={"Referer": f"{BASE}/temporary-email"})
            show("A5 GET /v1/temp_gmail/inbox (schema!)", st, b, 2500)

    # ---------- official v1 endpoints unauthenticated? ----------
    st, b = req("GET", f"{SONJJ_V1}/temp_gmail/random")
    show("C1 GET /v1/temp_gmail/random (no auth)", st, b, 600)

    # ---------- schema discovery: smailpro frontend JS ----------
    try:
        st, b = req("GET", f"{BASE}/temporary-email", timeout=60)
        html = b.decode("utf-8", "replace")
        import re
        from urllib.parse import urljoin
        scripts = re.findall(r'<script[^>]+src=["\']([^"\']+)["\']', html)
        print(f"\n### page <script> srcs ({len(scripts)}):", flush=True)
        for s in scripts[:20]:
            print("   ", s, flush=True)

        print("\n### inline HTML windows around message-field keys:", flush=True)
        seen = set()
        for pat in [r"\.mid\b", r"subject", r"v-for", r"messages", r"snippet", r"from", r"date"]:
            for m in list(re.finditer(pat, html, re.I))[:4]:
                w = html[max(0, m.start() - 140): m.end() + 160].replace("\n", " ")
                key = w[:80]
                if key in seen:
                    continue
                seen.add(key)
                print(f"[{pat}] ...{w}...", flush=True)

        for s in scripts:
            if s.startswith("//") or s.startswith("http"):
                if "smailpro.com" not in s and not s.startswith("/"):
                    continue
            url = urljoin(f"{BASE}/temporary-email", s)
            if "smailpro.com" not in url:
                continue
            st2, b2 = req("GET", url, timeout=45)
            if st2 != 200:
                continue
            txt = b2.decode("utf-8", "replace")
            hits = 0
            for pat in [r"\.mid\b", r"subject", r"msg\.", r"message\.", r"body", r"from", r"date", r"received"]:
                for m in list(re.finditer(pat, txt))[:6]:
                    w = txt[max(0, m.start() - 130): m.end() + 170].replace("\n", " ")
                    print(f"[JS {s.split('/')[-1]}|{pat}] ...{w}...", flush=True)
                    hits += 1
                    if hits > 60:
                        break
                if hits > 60:
                    break
            if hits > 60:
                break
    except Exception as e:
        print("schema hunt failed:", e, flush=True)

    print("\n=== SUMMARY ===", flush=True)
    print(json.dumps({k: v for k, v in out.items() if k != "created"}
                     | {"created_address": (out.get("created") or {}).get("address")},
                     ensure_ascii=False, indent=1), flush=True)


if __name__ == "__main__":
    main()
