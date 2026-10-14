## Buddy 2.0.1 — signed release build

**Download:** `buddy-latest.apk` (stable link) or `buddy-2.0.1-release.apk`

| | |
|---|---|
| Package | `com.example.buddy` |
| Version | 2.0.1 (versionCode 3) |
| Min Android | 8.0 (API 26) |
| Target | Android 14 (API 34) |
| Size | ~2.5 MB (was ~7.4 MB as a debug build) |
| **Signed with** | **CN=Starry, OU=Buddy, O=Starry** — a real release key, not the debug key |
| Schemes | APK Signature Scheme v2 (verified) |
| SHA-256 | `7081b70eb347309c38f90ead3f3b98549d25ccf1862c28cf61a1771cde883b02` |

### Fixed in this build

**1. The `CLEARTEXT communication to 127.0.0.1 not permitted` error**

The router treated "needs no API key" as "is ready", so a local Ollama at
`http://127.0.0.1:11434` was tried on **every** request — even though you don't run
one. It failed, ate 2 of your 8 calls, and showed a raw OkHttp message.

Now a provider only counts as usable when you've actually set it up:
- keyed providers need a key
- keyless ones (Ollama / custom) need a base URL you deliberately saved

If nothing is configured you get a warm, actionable message instead of a network
error, and the attempt costs **nothing**. Misconfigured providers are also
resolved *before* spending, so they no longer consume budget. A
`network_security_config.xml` now allows cleartext **only** to loopback, so a real
local Ollama does work.

**2. Voice input**

`onError` used to throw the error code away and show one vague toast. Now:
- the actual `SpeechRecognizer` error code is logged (`adb logcat -s Buddy`)
- `ERROR_NO_MATCH` / `SPEECH_TIMEOUT` → quietly listen again
- `ERROR_RECOGNIZER_BUSY` → short delay and retry
- client / audio / permission failures → **automatic fallback to the system's own
  voice-input screen**, which works on any device with a speech app
- `cancel()` before every `startListening()` (calling it on a busy recogniser throws)
- creation and start are wrapped in try/catch

**3. "App is harmful" warning**

- Ships as a **signed release build** (minified with R8, non-debuggable) instead of
  a debug APK, and it's 3× smaller.
- **Removed `QUERY_ALL_PACKAGES`** — a sensitive permission that can trigger
  warnings. App-name lookup still works through a scoped `<queries>` block for
  launcher apps.
- Added a `network_security_config` (HTTPS-only except loopback).

> Play Protect still prompts for *any* app installed outside the Play Store — that's
> a policy about the source, not a virus result. Choose **Install anyway**, or tap
> **Details → Scan**.

**4. Friendlier errors.** Raw failures are now mapped to warm, actionable text
(bad key, rate limit, unknown model, no internet, timeout, no provider set up).

**5. Website.** A Starry landing page now lives in [`docs/`](https://github.com/riyanshah112008-debug/starry/tree/cline/1kcns2ha/docs).

### Install
Download the APK on your phone → open it → allow installing from this source →
install. No setup needed: try `open chrome`, `volume up`, `what time is it`.

### Verify
```bash
sha256sum buddy-2.0.1-release.apk
# expect: 7081b70eb347309c38f90ead3f3b98549d25ccf1862c28cf61a1771cde883b02

apksigner verify --print-certs buddy-2.0.1-release.apk
# expect: CN=Starry, OU=Buddy, O=Starry
```
