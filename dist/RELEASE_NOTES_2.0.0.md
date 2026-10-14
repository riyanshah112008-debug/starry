## Buddy 2.0 — installable test build

Prebuilt, **debug-signed** APK. No Android Studio needed.

**Download:** `buddy-2.0.0-debug.apk` (below)

| | |
|---|---|
| Package | `com.example.buddy` |
| Version | 2.0.0 (versionCode 2) |
| Min Android | 8.0 (API 26) |
| Target | Android 14 (API 34) |
| Size | ~7.4 MB |
| Signature | Android Debug certificate |
| SHA-256 | `45509f6aa28e48b82abaad0ba83c50b83607bae4e438628973b083f66be67de5` |

### What's fixed in 2.0

**Your API key no longer gets burned in 5 seconds.**
- Common commands (`open chrome`, `text 5551234 ...`, `remember ...`, `volume up`,
  `turn on the torch`, `what time is it`, `hi`) are now handled **on-device with
  zero API calls**.
- One model call per request instead of up to 12, with a hard per-session
  **budget** (default 8 calls / 9000 tokens) that stops Buddy before it drains
  your key.
- `max_tokens` on every request, a 5-minute response cache, opt-in screen
  snapshots, and truncated prompts.

**It stops nagging for an API key.** The app is useful the moment you install it.
Keys are stored per-provider, encrypted, and there's a **Test connection** button.

**Hands-free.** A header toggle keeps the mic cycling so you never tap again, and
replies are spoken aloud. Most actions (open app, URLs, SMS, volume, torch,
alarms, memory) need **no accessibility at all**.
> Android *requires* you to enable an AccessibilityService by hand for
> tapping/typing/scrolling — no app can do it for itself. Buddy shows the status
> in the header and takes you straight there in one tap.

**Multi-model.** Ten providers behind one interface: OpenAI, Anthropic, Gemini,
**OpenRouter** (one key → hundreds of models), Groq, Mistral, DeepSeek, Together,
local **Ollama** (no key, no cost) and any custom OpenAI-compatible URL. Routing
modes: `AUTO`, `BEST`, `FALLBACK`, and `ENSEMBLE` (asks up to 3 models in
parallel and majority-votes the action).

Full write-up: [IMPROVEMENTS.md](https://github.com/riyanshah112008-debug/starry/blob/cline/1kcns2ha/IMPROVEMENTS.md)

### Install

**On the phone:** download the APK, open it, allow **Install unknown apps** when
asked, then launch **Buddy**.

**Over USB:** `adb install -r buddy-2.0.0-debug.apk`

### Try it immediately — no key required

`hi` · `what time is it` · `open chrome` · `remember my wifi password is blue123`
· `what's my wifi password` · `volume up` · `turn on the torch`

Then add an AI key in Settings when you want Buddy thinking for you.

### Verify the download
```bash
sha256sum buddy-2.0.0-debug.apk
# expect: 45509f6aa28e48b82abaad0ba83c50b83607bae4e438628973b083f66be67de5
```
