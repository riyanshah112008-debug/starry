# Buddy — a warm, informal Android AI assistant

Buddy is a chat-based Android app (Kotlin, viewBinding, no Compose) that talks
like a close friend and can drive your phone through an `AccessibilityService`.

- Chat UI with voice + text input, warm message bubbles, and a settings gear for
  your OpenAI API key
- **ChatGPT-style shell** — history sidebar, header model switcher, plain
  full-width assistant replies, prompt suggestions, light + dark themes
- **Sign in with Google** via Credential Manager (no Firebase needed) — Buddy
  greets you by name and personalises replies
- **Works with no API key and no accessibility for most commands** — a local
  fast path handles open-app, URLs, SMS, volume, torch, alarms and memory
- **Hands-free voice loop** with spoken replies (TextToSpeech)
- **10 AI providers** in one interface — OpenAI, Anthropic, Gemini, OpenRouter,
  Groq, Mistral, DeepSeek, Together, local Ollama, or any custom
  OpenAI-compatible endpoint — with `AUTO` / `BEST` / `FALLBACK` / `ENSEMBLE`
  routing (parallel calls + majority vote)
- **Spend guardrails** — per-session call/token budget, response cache, capped
  iterations, `max_tokens`, opt-in screen snapshots
- `AutoService` accessibility service: `clickByText`, `typeText`, `back`, `home`,
  `scrollForward/Backward`, `screenSnapshot`
- Room database: chat history, action audit log, long-term memory, preferences
- Security layer: `EncryptedSharedPreferences` vault, destructive-tool confirmation
  gate + biometrics, prompt-injection guard on screen text

**Want to run it?** See **[TESTING.md](TESTING.md)** for a step-by-step guide,
and **[IMPROVEMENTS.md](IMPROVEMENTS.md)** for what changed in 2.0.

Requires Android Studio Hedgehog+ / JDK 17. `minSdk 26`, `targetSdk 34`.
