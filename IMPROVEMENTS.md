# What changed in Buddy 2.0

Three complaints, three fixes. Plus the multi-model work.

---

## 1. "The API got exhausted in 5 seconds"

**What was wrong**

- Every message — even `open chrome` — made an LLM call.
- The system prompt included a full screen snapshot on **every** loop iteration.
- The agent loop ran up to **12** iterations, so one command could cost a dozen
  calls with a fat prompt each time.
- No `max_tokens`, no spending cap, no cache.

**What it does now**

| Fix | Effect |
|---|---|
| `LocalCommandRouter` — a deterministic fast path | **Zero** API calls for open app / open URL / SMS / remember / recall / back / home / scroll / volume / torch / clock / small talk |
| One model call per request (default) | After a tool succeeds we already know the result, so `WarmVoice` phrases the reply locally instead of paying for a second round-trip |
| `max_tokens` on every request | A runaway completion can't cost 4k tokens |
| Screen snapshot is **opt-in** (default off) and skipped when accessibility is off | Prompts shrink dramatically |
| Memory capped at 15 entries, values truncated to 120 chars | No more unbounded prompt growth |
| `TokenBudget` — hard per-session ceiling (default 8 calls / 9000 tokens) | Buddy **stops** and says so instead of draining your key |
| `ResponseCache` (5 min TTL) | Asking the same thing twice is free |
| `RateLimiter` lowered 30 → 12 calls/min | No accidental bursts |
| Loop cap 12 → 4 iterations | Worst case is a fraction of before |

**Result:** most commands now cost **nothing**, and the ones that do cost **one**
small call, with a ceiling you control in Settings.

---

## 2. "It keeps asking for the API all the time"

- The app no longer blocks you without a key — the local fast path works with
  **no key at all**, so Buddy is useful the second you install it.
- Keys are stored **per provider** in `EncryptedSharedPreferences`, and the
  original OpenAI key is migrated automatically.
- The model/provider choice is remembered.
- There's a **Test connection** button that makes the cheapest possible call and
  tells you plainly whether the key works.

---

## 3. "It should work hands-free without the accessibility option"

**The honest part:** Android *requires* the user to switch an AccessibilityService
on in Settings. No app can enable it for itself — that's a security boundary, not
a limitation I can code around. Anything claiming otherwise is lying.

**What I did instead**

- **One tap**: the header shows `Hands-free control: OFF — tap to switch on` and
  deep-links straight to the right Settings page.
- **Most things don't need it.** `open_app`, `open_url`, `send_sms`, `volume`,
  `flashlight`, `set_alarm`, `remember`, `recall` all work without accessibility.
  Only `click_text`, `type_text`, `scroll`, `back`, `home` need it.
- **Real hands-free voice**: a toggle in the header keeps the mic cycling so you
  never tap again, and replies are **spoken aloud** with TextToSpeech.
- Buddy now says *"I need hands-free mode switched on for that"* instead of a
  generic failure, so the cause is obvious.

---

## 4. "Make it a combination of every AI model in this world"

No app can literally call "every model", but Buddy now speaks to **ten providers**
through one interface, and can consult several at once:

| Provider | Notes |
|---|---|
| OpenAI | GPT-4o, GPT-4.1, o4-mini, … |
| Anthropic | Claude 3.5 / 3.7 |
| Google Gemini | 2.0 / 2.5 Flash and Pro |
| **OpenRouter** | **one key → hundreds of models** (`vendor/model`) |
| Groq | very fast, generous free tier |
| Mistral | Small / Large |
| DeepSeek | V3, R1 |
| Together AI | open-weight models |
| **Ollama** | **local, on-device, no key, no cost** |
| Custom | any OpenAI-compatible base URL |

Model ids drift over time, so the catalogue is editable and you can type **any**
model id — including `vendor/model` OpenRouter ids.

**Routing strategies** (Settings → Routing):

- `AUTO` — local fast path first, then the cheapest working model
- `BEST` — highest-tier model you have a key for
- `FALLBACK` — walk down the list until one answers
- `ENSEMBLE` — ask up to 3 models **in parallel and majority-vote the action**

Ensemble voting compares the *action* (tool + normalised args), because that's
the only part with a single right answer; chit-chat takes the first reply.

---

---

## 2.0.1 — fixes for real device testing

These came from actually running the 2.0.0 build on a phone.

### The `CLEARTEXT communication to 127.0.0.1 not permitted` error

**Symptom:** every message that needed a model failed with that raw OkHttp error,
and it silently ate 2 of the 8 budgeted calls.

**Cause:** `ModelRouter.isConfigured` didn't exist. The router treated
`requiresKey == false` as "ready to use", so **Ollama** (default
`http://127.0.0.1:11434`) and **Custom** were always in the candidate list. Android
blocks cleartext HTTP by default on targetSdk 34, so both attempts failed instantly.
The budget was charged before the request, so failures still cost calls.

**Fix:**
- A provider is only usable if the user set it up: a key for keyed providers, an
  explicitly saved base URL for keyless ones (Ollama / custom).
- `AUTO` now tries at most 2 configured providers instead of walking the whole
  catalogue.
- Providers are resolved **before** spending, so misconfiguration is free.
- Nothing configured → `NoProviderConfiguredException` and a warm, actionable
  message instead of a network error.
- Added `res/xml/network_security_config.xml`: HTTPS-only, **except** loopback, so a
  genuine local Ollama works.
- New `FriendlyErrors` maps raw failures to human text (bad key, 404 model, 429,
  5xx, no internet, timeout).

### Voice input

**Symptom:** tapping Talk produced "Couldn't catch that, mate" with no explanation.

**Cause:** `onError` discarded the error code, and there was only one generic toast.
`startListening` also didn't `cancel()` first, which throws when the recogniser is
busy.

**Fix:**
- Error codes are logged (`adb logcat -s Buddy`) and handled individually.
- `ERROR_NO_MATCH` / `SPEECH_TIMEOUT` → listen again quietly.
- `ERROR_RECOGNIZER_BUSY` → 500 ms delay and retry.
- Client / audio / permission failures → automatic fallback to the **system
  voice-input screen** (`ACTION_RECOGNIZE_SPEECH` activity), which works wherever a
  speech app is installed. Added the matching `<queries>` entry.
- `cancel()` before every start; creation and start wrapped in try/catch.

### "This app may be harmful"

**Fix:** shipped as a **signed release build** — R8-minified, non-debuggable, signed
with the Starry release key (`CN=Starry, OU=Buddy, O=Starry`), APK Signature
Scheme v2. Also **removed `QUERY_ALL_PACKAGES`**, which is a sensitive permission
that can trigger warnings; app-name lookup still works via a scoped `<queries>`
block for launcher apps. Result: 2.5 MB instead of 7.4 MB.

> Play Protect will still prompt for any app installed outside the Play Store —
> that's a policy about the source, not a virus scan. Choose *Install anyway*.

### Website

`docs/index.html` — a Starry landing page with download, install steps, a
"why does Android warn me" explainer, provider list and privacy notes.
`.nojekyll` is included so GitHub Pages serves it as-is.


| Say | API calls used |
|---|---|
| `hi` | 0 |
| `what time is it` | 0 |
| `open chrome` | 0 |
| `remember my wifi password is blue123` | 0 |
| `what's my wifi password` | 0 |
| `volume up` / `turn on the torch` | 0 |
| `text 5551234 I'll be late` | 0 (just a confirmation) |
| `write me a haiku about tea` | 1 |
