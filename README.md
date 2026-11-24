# 📧 Temp Gmail — অস্থায়ী ইমেইল অ্যাপ (APK)

Gmail-স্টাইলের একটি **অস্থায়ী ইমেইল (টেম্প মেইল)** অ্যাপ, যেটা সরাসরি **Android APK** হিসেবে বিল্ড হয়।
কোনো রেজিস্ট্রেশন ছাড়াই একটি অস্থায়ী ঠিকানা পাবেন — যেকোনো সাইটে OTP/ভেরিফিকেশন মেইল পাঠান, মেইলটি অ্যাপের ইনবক্সে স্বয়ংক্রিয়ভাবে চলে আসবে।

---

## ⬇️ APK কোথায় পাবেন

| উপায় | লিংক |
|---|---|
| **GitHub Release (সবচেয়ে সহজ)** | https://github.com/sremonak/Temp-Gmail/releases/tag/apk-latest |
| Actions artifact | https://github.com/sremonak/Temp-Gmail/actions → সর্বশেষ **Build APK** run → নিচে **Artifacts → TempGmail-apk** |
| রিপোতে কমিট করা ফাইল | `release/TempGmail-v1.0.apk` |

**ইনস্টল করার নিয়ম:** APK ফাইলটি ফোনে ডাউনলোড করুন → ট্যাপ করুন → **অজানা সোর্স (unknown sources)** অনুমোদন দিন → ইনস্টল হয়ে যাবে।
(এটি debug-signed APK — ব্যক্তিগত ব্যবহারের জন্য সরাসরি ইনস্টলযোগ্য।)

---

## ✨ ফিচার

- 📥 **Gmail-স্টাইল UI** — সাইডবার, ইনবক্স, স্টারড, অপঠিত ফিল্টার, ডার্ক মোড
- ⚡ **রিয়েল-টাইম ইনবক্স** — প্রতি ৬ সেকেন্ডে অটো রিফ্রেশ, নতুন মেইলে টোস্ট নোটিফিকেশন
- 🔽 **মেইল পড়া + অ্যাটাচমেন্ট ডাউনলোড** — HTML মেইল নিরাপদভাবে sandbox-এ রেন্ডার হয়
- 📋 **এক-ট্যাপে ঠিকানা কপি**, নতুন ঠিকানা তৈরি, ইনবক্স খালি করা
- 🌐 **দুটি ব্যাকএন্ড প্রোভাইডার** — mail.tm (প্রাথমিক), mail.gw (ফলব্যাক); দুটোই ফেল করলে অফলাইন **ডেমো মোড**
- 🔒 কোনো অ্যাকাউন্ট/পাসওযার্ড লাগে না — অ্যাপই অস্থায়ী মেইলবক্স তৈরি করে
- 🎨 লাইট + ডার্ক থিম, বাংলা ইন্টারফেস, নোটিফিকেশন-রেডি ব্র্যান্ড আইকন

## 📱 @gmail.com ঠিকানা নিয়ে সত্যি কথা

`@gmail.com` ডোমেইনের মেইল সার্ভার শুধুই Google-এর — বিশ্বে কোনো থার্ড-পার্টি সার্ভিস Google-এর ডোমেইনে মেইল রিসিভ/ডেলিভার করতে পারে না, তাই বৈধভাবে **কেউই আসল `@gmail.com` টেম্প ঠিকানা দিতে পারে না**।

এই অ্যাপ যা করে:

- ✅ **আসল, কার্যকর** টেম্প ঠিকানা দেয় (mail.tm-এর ডোমেইন, যেমন `swift.falcon1234@…`) — সেখানে পাঠানো মেইল সত্যিই ইনবক্সে আসে
- ✅ পুরো UI ও আইকন **Gmail-স্টাইলে** (লাল কনভার্ট, Gmail-মতো লেআউট)
- ❌ মেইল ঠিকানা দেখায় `@gmail.com` — এটি সম্ভব নয় (উপরে ব্যাখ্যা)

> সংক্ষেপে: **"Temp Gmail" মানে ব্র্যান্ড/স্টাইল Gmail-এর মতো, কিন্তু ঠিকানার ডোমেইন আসল টেম্প-মেইল ডোমেইন** — তাই মেইল সত্যিই কাজ করে।

---

## 🛠️ নিজে APK বিল্ড করে দেওয়ার নিয়ম

এই রিপোতে পুশ করলেই GitHub Actions স্বয়ংক্রিয়ভাবে APK বিল্ড করে Release-এ চড়ে দেয়:

1. `app/` (ওয়েব UI), `android/` (Capacitor নেটিভ প্রজেক্ট) বা `capacitor.config.json` এডিট করুন
2. `git push` করুন → ওয়ার্কফ্লো **Build APK** চলবে
3. শেষ হলে **Release `apk-latest`**-এ নতুন APK পাওয়া যাবে

লোকালি বিল্ড (Android Studio/SDK লাগবে):

```bash
npm ci
npx cap sync android
cd android && ./gradlew assembleDebug
# ফাইল: android/app/build/outputs/apk/debug/app-debug.apk
```

---

## 🗂️ প্রজেক্ট গঠন

```
app/                     # অ্যাপের UI (HTML/CSS/JS) — APK-এর ভেতরে যা চলে
  index.html
  css/style.css
  js/app.js              # UI controller
  js/mail-api.js         # mail.tm / mail.gw / demo client
  icons/                 # ওয়েব আইকন
android/                 # Capacitor Android প্রজেক্ট (Gradle)
scripts/                 # আইকন জেনারেটর
.github/workflows/       # APK build CI
capacitor.config.json
```

## 📌 টেক নোট

- Capacitor 8 · Gradle 8.14 · AGP 8.13 · compileSdk 36 · minSdk 24
- ব্যাকএন্ড: [mail.tm](https://docs.mail.tm) API (কোনো API-কী লাগে না, CORS চালু)
- মেইল ডেটা ফোনের localStorage-এ থাকে; ঠিকানা মুছলে মেইলও মুছে যায়
- মেইল HTML কখনো সরাসরি DOM-এ ঢোকানো হয় না — শুধু sandboxed iframe-এ (স্ক্রিপ্ট ব্লকড)

---

### English (quick)

**Temp Gmail** is a Gmail-styled disposable-email **Android APK**. One tap creates a real temporary mailbox (backed by the free mail.tm / mail.gw APIs, no signup); incoming OTP/verification mails appear in the inbox with 6-second auto-refresh, HTML rendering in a sandboxed frame, attachments, star/search/dark-mode. Real `@gmail.com` addresses cannot be issued by any third party (Google owns that domain) — the app uses real working temp-mail domains while keeping the Gmail look. APK is built automatically by GitHub Actions on every push and published to the `apk-latest` release.
