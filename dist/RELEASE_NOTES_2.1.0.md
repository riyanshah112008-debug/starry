## Buddy 2.1.0 — ChatGPT-style UI + Sign in with Google

**Download:** `buddy-latest.apk` (stable) or `buddy-2.1.0-release.apk`

| | |
|---|---|
| Package | `com.example.buddy` |
| Version | 2.1.0 (versionCode 4) |
| Min Android | 8.0 (API 26) · Target 34 |
| Size | ~3.0 MB |
| Signed with | `CN=Starry, OU=Buddy, O=Starry` (release key) |
| SHA-256 | `5e718a26d68098c5a623915e11c8f62e9de212a8d3ebb44acefc7bc995a25a5c` |

### New: a ChatGPT-style shell

- **Chat history sidebar.** Swipe open the drawer for every conversation, most
  recent first. Tap to switch, long-press to delete. No schema change was needed —
  sessions are derived from the existing message rows.
- **Model switcher in the header.** The top bar shows the current model and budget;
  tap it to change provider or model.
- **Message layout like ChatGPT's.** Your messages sit right-aligned in a soft
  bubble; Buddy's replies are plain, selectable, full-width text with a small
  avatar; tool results, errors and confirmations appear as inline tinted chips.
- **Empty state with suggestions** — "What's on your mind?" plus tappable prompt
  chips.
- **Pill composer** with the mic inside, exactly like ChatGPT's input.
- **Light and dark themes** (Material 3 DayNight, `values-night` palette).

### New: Sign in with Google

Uses Android's **Credential Manager** (`androidx.credentials` + `googleid`) — no
Firebase, no `google-services.json`, no extra SDK.

- Tapping **Sign in with Google** shows the native account sheet.
- Buddy then greets you by name, and your name is fed into the system prompt so
  replies feel personal ("call them Riyaan sometimes").
- The avatar in the drawer shows your initial; **Sign out** clears the profile.
- Only display name, given name and email are stored, in
  `EncryptedSharedPreferences`. **The ID token is never persisted.**
- If no client ID is configured yet, you get a friendly explainer instead of a
  cryptic Play-services error.

> **You must supply your own OAuth client ID.** Google requires a **Web** client ID
> registered against your signing key's SHA-1. Step-by-step guide:
> [GOOGLE_SIGNIN.md](https://github.com/riyanshah112008-debug/starry/blob/cline/1kcns2ha/GOOGLE_SIGNIN.md)

### Still here from 2.0.x

Zero-API-call local commands, the per-session spend budget, one model call per
request, 10 AI providers with `AUTO`/`BEST`/`FALLBACK`/`ENSEMBLE` routing, hands-free
voice with spoken replies, and the warm confirmation gate.

### Install

Download on your phone → open it → allow installing from this source → install.

### Verify

```bash
sha256sum buddy-2.1.0-release.apk
# expect: 5e718a26d68098c5a623915e11c8f62e9de212a8d3ebb44acefc7bc995a25a5c
```
