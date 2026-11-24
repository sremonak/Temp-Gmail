#!/usr/bin/env python3
"""Probe v2 — speed questions + new keyless providers + free SMS source.

Run from GitHub Actions runner (unrestricted network). Stdlib only.
Answers:
  1. Does SmailPro /app/inbox work WITHOUT cookies? (drop 285KB session fetch/poll)
  2. Is /app/message captcha-enforced? (fake mid → error shape tells us)
  3. Do grabmail / catchmail / guerrillamail / maildrop keyless APIs work?
  4. Does receive-sms-free.cc list numbers + messages we can parse?
"""
import json
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import http.cookiejar

UA = ("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
SITEKEY = "0x4AAAAAAABIS_gEec2IwOhI"
FGSI = "https://fgsi.dpdns.org/api/tools/cfclearance/turnstile-min"

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))


def show(name, status, body, limit=1200, elapsed=None):
    t = f" ({elapsed:.1f}s)" if elapsed else ""
    print(f"\n### {name} -> HTTP {status}{t}", flush=True)
    if isinstance(body, bytes):
        try:
            obj = json.loads(body.decode("utf-8", "replace"))
            print(json.dumps(obj, ensure_ascii=False, indent=1)[:limit], flush=True)
        except Exception:
            print(repr(body[:limit]), flush=True)
    else:
        print(repr(body)[:limit], flush=True)


def req(method, url, data=None, headers=None, timeout=60, use_cookies=True):
    h = {"User-Agent": UA, "Accept": "*/*"}
    if headers:
        h.update(headers)
    body = None
    if data is not None:
        body = json.dumps(data).encode()
        h.setdefault("Content-Type", "application/json")
    r = urllib.request.Request(url, data=body, headers=h, method=method)
    op = opener if use_cookies else urllib.request.build_opener()
    t0 = time.time()
    try:
        with op.open(r, timeout=timeout) as resp:
            return resp.status, resp.read(), time.time() - t0
    except urllib.error.HTTPError as e:
        try:
            return e.code, e.read(), time.time() - t0
        except Exception:
            return e.code, b"", time.time() - t0
    except Exception as e:
        return 0, str(e).encode(), time.time() - t0


def cookies_str():
    return "; ".join(f"{c.name}={c.value}" for c in cj)


def solve_captcha():
    qs = urllib.parse.urlencode({
        "apikey": "takashi0927192",
        "url": "https://smailpro.com/temporary-email",
        "sitekey": SITEKEY,
        "action": "smailpro_create",
    })
    st, b, el = req("GET", f"{FGSI}?{qs}", timeout=150, use_cookies=False)
    try:
        tok = json.loads(b).get("data", {}).get("token")
    except Exception:
        tok = None
    show("captcha solver", st, b[:200] if tok else b, 300, el)
    return tok


def main():
    out = {}

    # ============ 1. SPEED: smailpro flow ============
    req("GET", "https://smailpro.com/temporary-email")
    token = solve_captcha()
    if token:
        cq = urllib.parse.urlencode({"username": "random", "type": "alias",
                                     "domain": "gmail.com", "server": "1"})
        st, b, el = req("GET", f"https://smailpro.com/app/create?{cq}",
                        headers={"Referer": "https://smailpro.com/temporary-email",
                                 "x-captcha": token})
        show("create gmail", st, b, 700, el)
        try:
            acc = json.loads(b)
        except Exception:
            acc = {}
        addr, ts, key = acc.get("address"), acc.get("timestamp"), acc.get("key")
        out["created"] = addr

        if addr:
            payload_arr = [{"address": addr, "timestamp": ts, "key": key}]

            # 1a. /app/inbox WITH cookies (baseline)
            st, b, el = req("POST", "https://smailpro.com/app/inbox",
                            data=payload_arr,
                            headers={"Referer": "https://smailpro.com/temporary-email"})
            show("inbox WITH cookie", st, b, 400, el)
            payload = None
            try:
                payload = json.loads(b)[0].get("payload")
            except Exception:
                pass

            # 1b. /app/inbox WITHOUT any cookies (fresh opener)
            st2, b2, el2 = req("POST", "https://smailpro.com/app/inbox",
                               data=payload_arr,
                               headers={"Referer": "https://smailpro.com/temporary-email"},
                               use_cookies=False)
            show("inbox WITHOUT cookie", st2, b2, 400, el2)
            out["inbox_no_cookie"] = st2 == 200 and b2[:1] == b"["

            if payload:
                u = ("https://api.sonjj.com/v1/temp_gmail/inbox?payload="
                     + urllib.parse.quote(payload))
                st, b, el = req("GET", u)
                show("sonjj inbox", st, b, 500, el)

            # 1c. /app/message WITHOUT captcha (fake mid → error shape)
            mq = urllib.parse.urlencode({"email": addr, "mid": "probe-fake-mid"})
            st, b, el = req("GET", f"https://smailpro.com/app/message?{mq}",
                            headers={"Referer": "https://smailpro.com/temporary-email"})
            show("message WITHOUT captcha (fake mid)", st, b, 500, el)
            out["message_captcha_enforced"] = (st == 403 and b"Captcha" in b)

            # 1d. inbox latency x3 back to back (poll cost)
            times = []
            for _ in range(3):
                st, b, el = req("POST", "https://smailpro.com/app/inbox",
                                data=payload_arr,
                                headers={"Referer": "https://smailpro.com/temporary-email"})
                times.append(round(el, 2))
            print(f"\n### inbox x3 latency: {times}", flush=True)

    # ============ 2. GrabMail (instant catch-all) ============
    st, b, el = req("GET", "https://grabmail.io/api/v1/mailbox?address=probe42@grabmail.io")
    show("grabmail mailbox", st, b, 700, el)
    out["grabmail"] = st == 200

    # ============ 3. CatchMail ============
    st, b, el = req("GET", "https://api.catchmail.io/api/v1/mailbox?address=probe42@catchmail.io")
    show("catchmail mailbox", st, b, 700, el)
    out["catchmail"] = st == 200

    # ============ 4. Guerrilla Mail ============
    st, b, el = req("GET", "https://api.guerrillamail.com/ajax.php?f=get_email_address&lang=en")
    show("guerrilla get_email_address", st, b, 700, el)
    sid = email = None
    try:
        j = json.loads(b)
        sid, email = j.get("sid_token"), j.get("email_addr")
    except Exception:
        pass
    out["guerrilla"] = bool(email)
    if sid:
        st, b, el = req("GET", f"https://api.guerrillamail.com/ajax.php?f=check_email&seq=0",
                        headers={"Cookie": f"SUBSCR={sid}"})
        show("guerrilla check_email", st, b, 500, el)

    # ============ 5. Maildrop (GraphQL, public) ============
    st, b, el = req("POST", "https://api.maildrop.cc/graphql",
                    data={"query": '{ inbox(mailbox: "probe42") { id headerfrom subject date } }'})
    show("maildrop inbox", st, b, 700, el)
    out["maildrop"] = st == 200

    # ============ 6. Free SMS: receive-sms-free.cc ============
    st, b, el = req("GET", "https://receive-sms-free.cc/")
    html = b.decode("utf-8", "replace") if isinstance(b, bytes) else ""
    nums = re.findall(r'href="/(Free-[^/]+)/(\d+)/"', html)
    show("receive-sms-free home", st, f"status={st}, numbers found={len(nums)}", 300, el)
    out["sms_numbers"] = len(nums)
    print("first numbers:", nums[:6], flush=True)

    if nums:
        prefix, phone = nums[0]
        st, b, el = req("GET", f"https://receive-sms-free.cc/{prefix}/{phone}/")
        page = b.decode("utf-8", "replace") if isinstance(b, bytes) else ""
        blocks = re.findall(r'<div[^>]*class="sms-item[^"]*"[^>]*>(.*?)</div>', page, re.DOTALL)
        senders = re.findall(r'class="sender-badge[^"]*"[^>]*>([^<]+)<', page)
        contents = re.findall(r'class="sms-content[^"]*"[^>]*>(.*?)</p>', page, re.DOTALL)
        show(f"sms page {phone}", st,
             f"sms-item blocks={len(blocks)}, senders={len(senders)}, contents={len(contents)}",
             300, el)
        for s, c in list(zip(senders, contents))[:3]:
            print("   msg:", s.strip(), "|", re.sub(r"<[^>]+>", "", c).strip()[:100], flush=True)
        out["sms_parsed"] = len(blocks)
        out["sms_sample_prefix"] = prefix

    print("\n=== SUMMARY ===", flush=True)
    print(json.dumps(out, ensure_ascii=False, indent=1), flush=True)


if __name__ == "__main__":
    main()
