# Sign in with Google — setup

Buddy uses Android's **Credential Manager** (`androidx.credentials` +
`googleid`) for "Sign in with Google". That means there is **no Firebase, no
`google-services.json`, and no extra SDK** — but Google still requires you to
register an OAuth client for your app's signing key.

Until you do, tapping **Sign in with Google** shows a friendly explainer instead
of failing cryptically.

---

## 1. Get your signing key's SHA-1

The debug keystore lives in `~/.android/debug.keystore` (password `android`):

```bash
keytool -list -v \
  -keystore ~/.android/debug.keystore \
  -alias androiddebugkey -storepass android -keypass android \
  | grep SHA1
```

For a release build, use the key in `keystore/buddy-release.keystore`:

```bash
keytool -list -v \
  -keystore keystore/buddy-release.keystore \
  -alias buddy -storepass buddyTest2026 \
  | grep SHA1
```

You need **both** if you test debug and release builds.

## 2. Create the OAuth client

1. Open <https://console.cloud.google.com/> and create (or pick) a project.
2. **APIs & Services → OAuth consent screen** — set it up if you haven't.
   *External* + *Testing* is fine for personal use; add your own Google account
   under **Test users**.
3. **APIs & Services → Credentials → Create credentials → OAuth client ID**
4. Application type: **Android**
   - Package name: `com.example.buddy`
   - SHA-1: paste the fingerprint from step 1
5. **Create credentials → OAuth client ID** again, this time type **Web application**
   (no redirect URI needed).
6. Copy the **Web client ID** — it looks like
   `1234567890-abcdefg.apps.googleusercontent.com`.

## 3. Paste it into Buddy

Open the drawer → **Settings** → the field labelled
*"Google Web client ID (for Sign in with Google)"* → **Save it**.

Then tap **Sign in with Google**. Buddy will use the account's name to greet you
and will feed the name into its system prompt so replies feel personal.

> **Important:** it must be the **Web** client ID, not the Android one. The
> Android client ID only identifies the app; Credential Manager needs the Web
> client ID as the `serverClientId`.

---

## What Buddy stores

Only the profile fields it needs — display name, given name, email — in
`EncryptedSharedPreferences`. **The ID token is not persisted.** Sign out from the
drawer clears all three.

## Troubleshooting

| Symptom | Fix |
|---|---|
| "I need a Google client id..." | Field is empty — do step 3 |
| `NoCredentialException` / "couldn't find a Google account" | No Google account on the device, or the account isn't a **test user** on the consent screen |
| `GetCredentialCancellationException` | You dismissed the sheet — harmless |
| Sign-in works but no name | Consent screen not fully configured; re-check step 2 |
| Works in debug, fails in release | You registered only the debug SHA-1 — add the release one too |
| `DEVELOPER_ERROR` (10) | SHA-1 mismatch, or you used the Android client ID instead of the Web one |
