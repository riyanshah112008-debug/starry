# Testing Buddy locally

Buddy is a normal Android app: build it, install it, hand it an OpenAI API key,
and let it drive the phone through the accessibility service.

- **Repo / branch:** `https://github.com/riyanshah112008-debug/starry.git` → `cline/1kcns2ha`
- **Gradle module:** `app` (project root is the repo root)
- **applicationId / namespace:** `com.example.buddy`
- **minSdk 26** (Android 8.0) · **targetSdk/compileSdk 34**

> The real manifest is `app/src/main/AndroidManifest.xml`.
> The extra copy at the repo root is just for reference — Android only builds the `app/` one.

---

## 1. What you need

| Thing | Why |
|---|---|
| Android Studio **Hedgehog (2023.1.1) or newer** | AGP 8.2.2 + Kotlin 1.9.22 |
| **JDK 17** (Android Studio bundles one) | required by AGP 8.2 |
| An **Android 8.0+** phone **or** an emulator with **Google APIs** | accessibility + speech |
| An **OpenAI API key** with credit | Buddy does the "thinking" |
| _(optional)_ **Termux** installed | only for the `run_shell` tool |

**Phone vs emulator**

- `open_app`, `open_url`, `click_text`, `type_text`, `scroll`, `back`, `home`,
  `remember`, `recall` all work on an **emulator**.
- `send_sms` and realistic voice input are much better on a **real phone**.
- Use a **"Google APIs"** (not plain AOSP) system image so Chrome and the Google
  speech recognizer exist. Play Store images work too.

---

## 2. Get the code

```bash
git clone -b cline/1kcns2ha https://github.com/riyanshah112008-debug/starry.git
cd starry
```

The Gradle wrapper is included (Gradle 8.2), so you do **not** need to install Gradle.

---

## 3. Build & install

**Option A — Android Studio (easiest)**

1. `File ▸ Open` and pick the repo root (the folder with `settings.gradle.kts`).
2. Wait for the Gradle sync to finish.
3. Pick your device/emulator in the toolbar and press **Run ▶**.
   `MainActivity` is the launcher activity.

**Option B — command line**

```bash
# macOS / Linux
./gradlew assembleDebug
./gradlew installDebug

# Windows
gradlew.bat assembleDebug
gradlew.bat installDebug
```

Then launch **Buddy** from the app drawer.

> Use the **debug** build for testing. The release APK produced by
> `assembleRelease` is unsigned (no signing config is committed on purpose).

---

## 4. First-run setup: the API key

1. Tap the **gear** (top-right).
2. Paste your OpenAI API key into *"Paste your OpenAI API key here"*.
3. Tap **Save it** → you should see **"Got it - saved."**

The key is stored in `EncryptedSharedPreferences` (`SecureVault`), never in source.
If a key is missing, Buddy replies: *"I need an API key first. Tap the gear up top."*

---

## 5. Turn on the accessibility service

Without this, screen tools (`click_text`, `type_text`, `scroll`, `back`, `home`)
can't run — Buddy will say *"Accessibility service isn't active yet."*

**Either** tap **Settings ▸ "Turn on hands-free control"** in Buddy's gear dialog,
**or** go manually:

`Settings ▸ Accessibility ▸ Installed apps ▸ Buddy Phone Helper ▸ On`

> **Android 13/14 sideload gotcha:** if the toggle is greyed out with a
> *"Restricted setting"* warning, open `Settings ▸ Apps ▸ Buddy ▸ ⋮ ▸
> Allow restricted settings`, then enable accessibility again.

---

## 6. Test plan — what to say, what to expect

Tap **Talk** (voice) or type and hit **Send**. Each row is a manual test.

| # | Say / type | Expected Buddy bubble |
|---|---|---|
| 1 | `hi` | `Hey boss. What're we up to?` (no tool, just chat) |
| 2 | `open chrome` | green **"Okay, done: Opened com.android.chrome"** |
| 3 | `open https://example.com` | green **"Opened https://example.com"** |
| 4 | `remember my wifi password is blue123` | green **"Remembered wifi"** |
| 5 | `what's my wifi password` | `It's blue123, mate.` |
| 6 | `scroll down` | green **"Scrolled forward"** |
| 7 | `go back` / `go home` | green **"Went back"** / **"Went to Home screen"** |
| 8 | `text 5551234 I'll be late` | **confirmation dialog** → allow → green **"Sent text to 5551234"** |
| 9 | `run the command echo hi` | **confirmation dialog** → allow → shell output |
| 10 | `delete all my photos` | Buddy refuses warmly: *"Whoa, that's a big one…"* |
| 11 | `thanks` | `Anytime, boss.` |

**What each bubble colour means**
- **grey** "Hmm, let me think..." — the agent loop is running
- **green** "Okay, done: …" — a tool succeeded
- **soft red** "Ah, that didn't work: …" — a tool or the network failed
- **amber** "Heads up - this next bit is a big deal. Okay to go?" — a confirmation is pending

**Confirmation gates to verify**
- `send_sms` → *"Sending a text" / "Buddy's about to text &lt;number&gt;. Look good?"*
- `run_shell` → *"Running a command" / "Buddy wants to run: &lt;cmd&gt;. Cool with that?"*
- destructive `click_text` (e.g. a button labelled "Delete") → default *"Heads up"* copy
- If the device has biometrics enrolled, an extra fingerprint/face prompt appears.
  Tap **Nope, cancel** / **Hold on** / **Stop** and confirm Buddy says it held off.

---

## 7. Inspect what's happening

- **Audit trail & memory:** Android Studio ▸ **App Inspection ▸ Database Inspector**
  → `buddy_agent.db`, tables `action_logs`, `memories`, `chat_messages`, `user_preferences`.
  Every tool call (allowed *and* cancelled) is logged with a success flag.
- **Logs:** `adb logcat | grep -i buddy`
- **Raw model replies:** the agent expects strict JSON; if Buddy ever behaves oddly,
  look at what the model returned near the `LLMClient` request in logcat.

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| *"I need an API key first."* | Set the key in the gear dialog |
| *"Invalid API key (HTTP 401)"* | Key is wrong/revoked, or the OpenAI account has no credit |
| Tool says *"Accessibility service isn't active yet."* | Enable Buddy Phone Helper (step 5) |
| `open_app` → *"The app … isn't installed."* | That package genuinely isn't on the device; try another (e.g. `com.android.settings`) |
| `open_app` → *"That package … isn't allowed for safety."* | `SecurityManager` blocked it (installers/provisioning packages are blocklisted) |
| Voice does nothing | Emulator without Google APIs has no recognizer — use a device or a Google APIs image; also grant microphone permission |
| `send_sms` opens a draft instead of sending silently | The `SEND_SMS` runtime permission wasn't granted; grant it in App info ▸ Permissions |
| `run_shell` fails | Install **Termux** (Buddy uses its `RUN_COMMAND` service); otherwise it falls back to a local `sh` with the app's limited UID |
| Gradle sync fails on JDK | Set `File ▸ Settings ▸ Build ▸ Gradle ▸ Gradle JDK` to **17** |

---

## 9. Safety behaviour worth testing

- Screen text is treated as **untrusted**: `InjectionGuard` strips phrases like
  "ignore previous instructions" before anything from the screen reaches the model.
- Destructive tools are **always** paused for confirmation and written to
  `action_logs`, including the cancellations.
- `open_app` is restricted by `SecurityManager.isPackageAllowed(...)`.
- Nothing is hardcoded: no API keys, no credentials in the repo.

---

## 10. What's new in 2.0 — read this before testing

Full detail in [IMPROVEMENTS.md](IMPROVEMENTS.md). The short version:

**You no longer need an API key to test most things.** Try these with *no key and
no accessibility* straight after install — they make **zero** API calls:

| Say | Result |
|---|---|
| `hi` | `Hey boss. What're we up to?` |
| `what time is it` | speaks the time |
| `open chrome` | launches Chrome |
| `open https://example.com` | opens the page |
| `remember my wifi password is blue123` | `Locked it in. Won't forget that.` |
| `what's my wifi password` | `It's blue123, mate.` |
| `volume up` / `turn volume down` | changes volume |
| `turn on the torch` | flashlight on |
| `go home` / `scroll down` | needs accessibility |
| `text 5551234 I'll be late` | confirmation, then sends |

**Hands-free voice.** Tap **Hands-free voice** in the header. The mic stays
cycling so you can keep talking without tapping, and replies are spoken aloud.
Toggle speech in Settings.

**Accessibility is still needed** for `click_text`, `type_text`, `scroll`, `back`
and `home` — Android requires you to enable it yourself, one tap from the header
chip (`Hands-free control: OFF - tap to switch on`).

**Multi-model.** Settings → **Change provider & model** lets you pick OpenAI,
Anthropic, Gemini, OpenRouter (one key, hundreds of models), Groq, Mistral,
DeepSeek, Together, a local **Ollama** (no key, no cost), or a custom
OpenAI-compatible URL. You can also type any custom model id.

**Routing strategies** (Settings → Routing): `AUTO`, `BEST`, `FALLBACK`, and
`ENSEMBLE` (asks up to 3 models in parallel and majority-votes the action).

**Spend control.** Settings shows `Spent: 0/8 calls - ~0/9000 tokens` and lets you
cycle the budget (4/8/20/50 calls). When the cap is hit Buddy stops and says so
rather than draining your key. **Test connection** makes the cheapest possible
call to prove a key works.

**Two switches worth knowing** (both default OFF to save money):
- *Send screen contents to the model* — off means prompts stay tiny
- *Let Buddy take several AI steps per request* — off means one model call per command


