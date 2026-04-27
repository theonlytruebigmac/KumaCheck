# KumaCheck — Release checklist

How to cut a signed APK and publish it as a GitHub Release. The release flow is sideload-only — there is no Play Store listing.

There are two ways to produce a signed release APK:

- **Locally** — useful for smoke-testing before tagging.
- **In CI** — the [Release workflow](../.github/workflows/release.yml) runs on tag push or manual dispatch and attaches the signed APK to a GitHub Release.

Both paths use the same keystore. The local path reads it from `keystore.properties`; CI reads it from repo secrets.

## 1. Generate the release keystore (one-time setup)

Run the helper script from the repo root. It calls `keytool`, prompts for the passwords, and writes both `release.keystore` and `keystore.properties` (gitignored alongside `*.keystore` / `*.jks`):

```sh
./scripts/generate-release-keystore.sh
```

Back up the keystore somewhere offline (a password manager attachment is fine). If you lose it, future releases can't be signed with the same key, and existing installs can't be upgraded without uninstalling first.

`app/build.gradle.kts` reads credentials in this order: env vars (`RELEASE_KEYSTORE_FILE`, `RELEASE_KEYSTORE_STORE_PASSWORD`, `RELEASE_KEYSTORE_KEY_ALIAS`, `RELEASE_KEYSTORE_KEY_PASSWORD`) → `keystore.properties` → fall back to the Android debug keystore. The fallback exists so `assembleRelease` still completes on a fresh checkout, but a debug-signed APK is not safe to publish.

## 2. Bump version

Edit `app/build.gradle.kts`:

- `versionCode` — strictly greater than the previously released code.
- `versionName` — semver string (matches the git tag without the `v` prefix).

## 3. Build the artifact

```sh
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

R8 + ProGuard run with the rules in `app/proguard-rules.pro` (Socket.IO reflection, Moshi codegen adapters, kotlinx-coroutines internals, `app.kumacheck.data.model.**`).

## 4. Smoke-test the release build

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
```

Walk through:

- Splash → Login (token re-auth and fresh password login).
- Add a second server, switch between them, sign out — verify the pivot routes correctly.
- Theme switcher (Light / Dark / System).
- Pull to refresh on Overview, Monitor Detail, Maintenance List, Status Page Detail.
- Place a 2×2 tile widget, a 4×2 list widget, a 1×1 micro tile — confirm they receive a snapshot.
- Post and unpin a status-page incident (admin operations exposed in the Status Page Detail).

If anything regresses in release-only behavior (typically R8 stripping reflective access), update `proguard-rules.pro` and rebuild.

## 5. Cut a GitHub Release

### Option A — tag push (preferred)

```sh
git tag v0.5.0
git push origin v0.5.0
```

The [Release workflow](../.github/workflows/release.yml) runs unit tests, builds and signs the APK, verifies the signature with `apksigner`, attaches `KumaCheck-v0.5.0.apk` to a GitHub Release, and auto-generates release notes from commits since the previous tag.

### Option B — manual dispatch

From the **Actions** tab → **Release** → **Run workflow**, enter the tag (e.g. `v0.5.0`). Useful for re-running a failed release without rotating the tag.

### Required repo secrets

Set these once under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -i release.keystore` (one line, no wrapping) |
| `RELEASE_KEYSTORE_STORE_PASSWORD` | Store password from §1 |
| `RELEASE_KEYSTORE_KEY_ALIAS` | Key alias from §1 (default: `release`) |
| `RELEASE_KEYSTORE_KEY_PASSWORD` | Key password from §1 |

On macOS, copy the base64 to the clipboard with `base64 -i release.keystore | pbcopy`.

## 6. Crash recovery

There's no Play Console-side instrumentation. Logcat captures + an `adb bugreport` from an affected device are the recovery path. To roll back, delete the bad release from the GitHub Releases page (users won't be auto-updated; sideload installs don't pull from anywhere).
