# CI release signing

`ci-debug.keystore` is a fixed, checked-in keystore used **only** as a fallback
when no real release keystore is configured — see
`.github/workflows/release.yml` and the signing block in `app/build.gradle.kts`.

It exists because Android's package installer rejects an app upgrade whose
signing certificate doesn't match the currently-installed one, and
`AppUpdateManager` now checks this explicitly too. Without a fixed keystore,
every GitHub Actions run would get a different randomly-generated debug
keystore (Android Studio's implicit `debug` signing config is generated
per-machine on first use), so consecutive CI releases would silently stop
being installable as upgrades over each other.

Its password is intentionally public (`illumera-ci-debug`, for both the
keystore and the key) — it provides **no security**, only a consistent
identity for test/debug releases. **Never use it for a real published
release** you expect users to trust or keep long-term; if this repo's CI
starts publishing releases meant for real users, add a real release keystore
(see below) so those builds stop being debug-signed.

## Switching to a real release keystore

1. Generate one (keep it somewhere safe — losing it means you can never sign
   an upgrade to an already-installed release again):
   ```bash
   keytool -genkeypair -v -keystore release.keystore -alias illumera-release \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Base64-encode it and add these as **Actions secrets** on the
   `HereLiesAz/illumera` repo (Settings → Secrets and variables → Actions):
   - `KEYSTORE_RAW` — output of `base64 -w0 release.keystore`
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`

   The following are optional certificate metadata (owner DN, SHA-1/SHA-256
   fingerprints, exported public/private keys and cert chain) some workflows
   generate alongside a keystore — handy for things like verifying
   `assetlinks.json` or enrolling in Play App Signing, but not read by this
   pipeline: `KEYSTORE_OWNER`, `KEYSTORE_SHA1`, `KEYSTORE_SHA256`,
   `KEYSTORE_PRIVATE`, `KEYSTORE_PUBLIC`, `KEYSTORE_CHAIN`, `KEYSTORE_RSA`.
3. Once all four required secrets are set, `.github/workflows/release.yml`
   picks them up automatically on the next push to `main` and stops using
   `ci-debug.keystore`. Note this is a one-way switch for real users: once
   they've installed a build signed with the real keystore, they can never
   go back to a `ci-debug.keystore`-signed build without uninstalling first
   (Android will refuse the "upgrade" as a signature mismatch).

For a local `./gradlew assembleRelease` signed with the real keystore instead
of the CI fallback, add the same four values to `local.properties` (not
committed) as `release.storeFile`, `release.storePassword`,
`release.keyAlias`, `release.keyPassword` — `release.storeFile` should be a
path to the keystore file, relative to the repo root or absolute.
