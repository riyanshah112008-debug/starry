## Buddy 1.0.0 — installable test build

A prebuilt, **debug-signed** APK so you can test Buddy without opening Android Studio.

**Download:** `buddy-1.0.0-debug.apk` (below)

| | |
|---|---|
| Package | `com.example.buddy` |
| Version | 1.0.0 (versionCode 1) |
| Min Android | 8.0 (API 26) |
| Target | Android 14 (API 34) |
| Size | ~7.3 MB |
| Signature | Android Debug certificate |
| SHA-256 | `75f36d1f003089b2e4006211ffcd065a41c9807b415aad799d88b3f43b0ee65c` |

### Install

**Option 1 — on the phone**
1. Download the APK on your Android device.
2. Open it; allow **Install unknown apps** for your browser/file manager when asked.
3. Launch **Buddy** from the app drawer.

**Option 2 — over USB with adb**
```bash
adb install -r buddy-1.0.0-debug.apk
```

### First run
1. Tap the **gear** (top right) → paste your **OpenAI API key** → **Save it**.
2. In the same dialog tap **"Turn on hands-free control"**, then enable
   **Buddy Phone Helper** in Accessibility.
   *(Android 13/14: if it's greyed out, use `Settings ▸ Apps ▸ Buddy ▸ ⋮ ▸ Allow restricted settings` first.)*
3. Try `hi`, then `open chrome`, then `remember my wifi password is blue123`.

### Notes
- This is a **debug build** — no API key or secret is embedded; you supply your own.
- `send_sms` and voice input are best on a real phone.
- The app needs an OpenAI key with credit, otherwise Buddy replies
  *"I need an API key first."*
- Full test plan: see [TESTING.md](https://github.com/riyanshah112008-debug/starry/blob/cline/1kcns2ha/TESTING.md).

### Verify the download (optional)
```bash
sha256sum buddy-1.0.0-debug.apk
# expect: 75f36d1f003089b2e4006211ffcd065a41c9807b415aad799d88b3f43b0ee65c
```
