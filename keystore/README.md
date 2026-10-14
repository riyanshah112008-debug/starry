# Signing key

`buddy-release.keystore` is the **Starry release signing key** for Buddy.

| | |
|---|---|
| Keystore | `keystore/buddy-release.keystore` |
| Alias | `buddy` |
| Algorithm | RSA 2048, SHA256withRSA, 10000 days |
| Subject | `CN=Starry, OU=Buddy, O=Starry, L=Internet, ST=Internet, C=US` |
| Certificate SHA-256 | `b713459518d9acc5cb47dd9d2f278618c1b66d3f9f67782b548127926c94edd7` |

Credentials are in `keystore.properties` at the repo root, which
`app/build.gradle.kts` reads automatically. `./gradlew assembleRelease` produces a
signed `app-release.apk`.

> ## ⚠️ This is a development key
>
> The password is committed on purpose so anybody can reproduce a build. That makes
> it **useless as a secret**.
>
> Before publishing to Google Play — or if you ever ship a build users rely on —
> generate your own key, keep it out of version control, and never lose it
> (Play won't let you update an app with a different key):
>
> ```bash
> keytool -genkeypair -v \
>   -keystore my-release.keystore -alias myalias \
>   -keyalg RSA -keysize 4096 -validity 10000
> ```
>
> Then point `keystore.properties` at it and add that file to `.gitignore`.
