# 📧 Temp Gmail — নেটিভ Kotlin Android অ্যাপ (APK)

**সম্পূর্ণ নেটিভ Kotlin**-এ লেখা অস্থায়ী ইমেইল অ্যাপ — কোনো HTML/CSS wrapper নয়, **কোনো demo/fake ডেটা নেই**। আসল `@gmail.com` ও `@outlook.com` টেম্প ঠিকানা (SmailPro-র ম্যানেজড রিয়েল অ্যাকাউন্ট পুল থেকে), সাথে mail.tm সাধারণ টেম্প ডোমেইন ফলব্যাক।

---

## ⬇️ APK ডাউনলোড

| উপায় | লিংক |
|---|---|
| **GitHub Release** | https://github.com/sremonak/Temp-Gmail/releases/tag/apk-latest → **TempGmail-v2.0.apk** |
| Actions artifact | https://github.com/sremonak/Temp-Gmail/actions → সর্বশেষ **Build APK** → `TempGmail-apk` |
| রিপোতে | [`release/TempGmail-v2.0.apk`](https://github.com/sremonak/Temp-Gmail/blob/arena/31b25773-temp-gmail/release/TempGmail-v2.0.apk) |

**ইনস্টল:** ফোনে ডাউনলোড → ট্যাপ → "অজানা সোর্স" অনুমোদন → Install। Android 7+ সব ফোনে চলবে (minSdk 24)।

---

## ✨ ফিচার

- 📱 **১০০% নেটিভ Kotlin + Material 3** — RecyclerView, SwipeRefresh, ডার্ক মোড, বাংলা UI
- ✉️ **আসল `@gmail.com` ঠিকানা** — SmailPro-র রিয়েল Gmail পুল (~৩০০০+ অ্যাকাউন্ট), অনেক সাইটে disposable block এড়ায়
- 📥 **রিয়েল ইনবক্স** — প্রতি ৬ সেকেন্ডে অটো রিফ্রেশ, নতুন মেইলে টোস্ট
- 🔐 **আসল captcha যাচাই** — প্রথমে ফ্রি অটো-সলভার (retry সহ), না হলে অ্যাপের ভেতরেই Cloudflare চেকবক্স (এক ট্যাপ)। কোনো বাইপাস/ফেক নয়
- 📖 মেইল পড়া (HTML মেইল নিরাপদ WebView-এ, **জাভাস্ক্রিপ্ট বন্ধ**), স্টার, অপঠিত ফিল্টার, ইনবক্স খালি
- 🌗 লাইট / ডার্ক / সিস্টেম থিম
- 🗂️ ৩টি ঠিকানা-রক্ষক: **@gmail.com**, **@outlook.com**, **সাধারণ টেম্প ডোমেইন (mail.tm)**

## 🔁 ঠিকানা তৈরির flow (লাইভভাবে যাচাইকৃত)

```
smailpro.com/temporary-email   → সেশন কুকি
fgsi ফ্রি সলভার / WebView      → Turnstile টোকেন
smailpro.com/app/create        → {address: name.x.y@gmail.com, timestamp, key}
smailpro.com/app/inbox         → payload
api.sonjj.com/v1/temp_gmail/inbox → {messages:[{mid,textFrom,textTo,textSubject,textDate}]}
smailpro.com/app/message       → payload → v1/temp_gmail/message → {body: html}
```

- ইন্টারনেট সমস্যা/সার্ভার এরর হলে **সৎ error ব্যানার + আবার চেষ্টা** — ভুল ডেটা কখনো দেখানো হয় না
- mail.tm মোডে captcha লাগে না (সম্পূর্ণ বিকল্প, সবসময় কাজ করে)

## 🛠️ নিজে বিল্ড

পুশ করলেই GitHub Actions APK বিল্ড করে Release-এ দেয়:

```bash
# লোকালি (Android Studio / SDK লাগবে)
cd android && ./gradlew assembleDebug
# ফাইল: android/app/build/outputs/apk/debug/app-debug.apk
```

## 🗂️ প্রজেক্ট গঠন

```
android/                     # নেটিভ Gradle প্রজেক্ট (Kotlin)
  app/src/main/java/com/tempgmail/app/
    MainActivity.kt          # ইনবক্স স্ক্রিন
    ReaderActivity.kt        # মেইল রিডার (WebView body)
    MailAdapter.kt           # RecyclerView অ্যাডাপ্টার
    api/SmailproClient.kt    # smailpro + api.sonjj ক্লায়েন্ট
    api/MailTmClient.kt      # mail.tm ক্লায়েন্ট
    api/Captcha.kt           # টোকেন: ফ্রি সলভার → WebView ডায়ালগ
    api/MailRepo.kt          # ফেসাড + error mapping
    api/Net.kt               # OkHttp, তারিখ/অ্যাভাটার utils
    store/Session.kt         # SharedPreferences (অ্যাকাউন্ট, star/read)
    model/MailModels.kt
  app/src/main/res/          # Material3 থিম, layouts, আইকন
scripts/                     # আইকন জেনারেটর + API probe
.github/workflows/           # APK build CI
release/TempGmail-v2.0.apk
```

## 📌 টেক নোট

- Kotlin 2.2.20 · AGP 8.13 · Gradle 8.14.3 · compileSdk 36 · minSdk 24
- OkHttp (CORS সমস্যাই নেই — নেটিভ HTTP), Material 1.12, Coroutines
- Captcha-র ফ্রি সলভারে rate-limit থাকতে পারে → তখন অ্যাপ নিজে WebView ডায়ালগ দেখায় (ইউজার এক ট্যাপে যাচাই করেন)
- মেইল ডেটা ফোনে SharedPreferences-এ; নতুন ঠিকানা তৈরিতে পুরনো লোকাল state মুছে যায়

---

### English

**Temp Gmail** is a **fully native Kotlin** disposable-email Android app (no web wrapper, no demo data). It issues **real `@gmail.com` / `@outlook.com` addresses** from SmailPro's managed receive-only pools (Cloudflare Turnstile solved automatically with a free solver, falling back to a one-tap in-app WebView checkbox — real verification, nothing faked), plus a mail.tm fallback for regular disposable domains. 6-second auto-refresh, HTML mail reading in a hardened WebView (JS disabled), stars/unread filters, dark mode, Bengali UI. Internet/server failures surface honest retryable error banners — fake messages never appear. The APK is built by GitHub Actions on every push and published to the `apk-latest` release.
