# Buddy — a warm, informal Android AI assistant

Buddy is a chat-based Android app (Kotlin, viewBinding, no Compose) that talks
like a close friend and can drive your phone through an `AccessibilityService`.

- Chat UI with voice + text input, warm message bubbles, and a settings gear for
  your OpenAI API key
- `AutoService` accessibility service: `clickByText`, `typeText`, `back`, `home`,
  `scrollForward/Backward`, `screenSnapshot`
- Room database: chat history, action audit log, long-term memory, preferences
- Security layer: `EncryptedSharedPreferences` vault, destructive-tool confirmation
  gate + biometrics, prompt-injection guard on screen text
- AI layer: OkHttp client with retries/exponential backoff, 30 calls/min rate
  limiter, strict JSON-only responses
- Agent loop: tool registry, memory-aware context builder, 12-iteration
  orchestrator with full audit logging

**Want to run it?** See **[TESTING.md](TESTING.md)** for a step-by-step guide
(build, install, API key, enabling accessibility, and a per-tool test plan).

Requires Android Studio Hedgehog+ / JDK 17. `minSdk 26`, `targetSdk 34`.
