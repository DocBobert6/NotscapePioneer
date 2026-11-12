# Notscape Pioneer — Phase 1 Spike Build Log

Spike only. Throwaway by design. Repo: https://github.com/DocBobert6/NotscapePioneer (public).
Head commit: `4609fbb543311ed8f239997d5d573ff5959b3d8f`
("Spike fix: correct GeckoView 159 delegate signatures …").

## CI runs (Android CI, push to main)

| Run | Commit | Result | Cause |
|---|---|---|---|
| 43130445652 | e8e49bf8 | failure | GeckoView 159 AAR metadata: compileSdk 37+ / AGP 9.1.0+ required, project was compileSdk 36 / AGP 8.13.2 |
| 43130877193 | eb76dece | failure | Same, with GeckoView 158 — 158 needs 37 too |
| 43131525374 | bac116c3 | failure | `sdkmanager` cannot find `platforms;android-37` (stable or canary) |
| 43132035134 | 0bf9ff7b | failure | AGP 9 rejects `org.jetbrains.kotlin.android` — Kotlin is built into AGP 9 |
| 43132333912 | 85af2af7 | failure | `tasks.named("checkDebugAarMetadata")` — AGP creates the task after evaluation |
| 43132628156 | ecb567b2 | failure | Kotlin compile errors — wrong GeckoView delegate signatures (see below) |
| **43133176018** | **4609fbb5** | **success** | **Green. Artifact `pioneer-spike-apk`.** |

## Toolchain (final, all forced by GeckoView 159)

- AGP 9.4.1 / Gradle 9.8.1 / Kotlin 2.3.21 (AGP 9 bundles Kotlin; the
  `org.jetbrains.kotlin.android` plugin must NOT be applied).
- compileSdk 36, targetSdk 36, minSdk 26, arm64-v8a only.
- GeckoView **159.0.20261102144711** (latest stable, Firefox 159).
- Compose BOM 2025.10.01, activity-compose 1.11.0.

## Known deviations (documented in code, all reversible)

1. **AAR metadata check disabled** (`tasks.configureEach`, app/build.gradle.kts):
   GeckoView 159 and androidx.core 1.19.x / lifecycle 2.11.x declare
   minCompileSdk 37, but Google has published **no android-37 platform**
   to the public SDK repository as of 2026-11-12 — verified absent from
   repository2-1.xml on every channel, and the runner's sdkmanager cannot
   find it on stable or canary (only build-tools 37.0.0 exists). Android 17
   (API 37) is a stable release (June 2026), but its platform package is
   not installable via sdkmanager. Re-enable the check and raise compileSdk
   to 37 when the platform ships.
2. **Delegate signatures corrected to GeckoView 159** (compiler was arbiter):
   `onLocationChange` takes a 4th `hasUserGesture: Boolean` param;
   `onCanGoBackChange` is renamed `onCanGoBack`; `AllowOrDeny` is a
   top-level enum (`org.mozilla.geckoview.AllowOrDeny`, ALLOW/DENY) — it is
   NOT nested in NavigationDelegate; `onContentPermissionRequest` takes a
   `ContentPermission` (not `ContentPermissionRequest`); prompt-delegate
   arrays are non-null; `PermissionPromptResponse` ctor is
   `(isPermissionsGranted, isPrivateModeGranted,
   isTechnicalAndInteractionDataGranted)` — three Booleans, not arrays;
   `onUpdatePrompt`/`onOptionalPrompt` return `GeckoResult<AllowOrDeny>`,
   not `GeckoResult<Boolean>`.

## Verified APK (run 43133176018, artifact 13905179954)

- GitHub artifact `pioneer-spike-apk`: zip 110,804,175 bytes.
- Local copy: `~/workspace/notscape-pioneer/artifacts/app-debug.apk`
  (zip beside it: `pioneer-spike-apk.zip`).
- **APK: 210,818,321 bytes** (~201 MiB), Android package, debug-signed
  (v1 + v2 signatures verified with androguard).
- SHA-256: `5c35cdd5c8b1ed4df7c290c117f8df766b2886ae39c04403d13569e4bcf4f5f2`
- package `net.nspioneer.browser`, versionName `0.1.0-alpha.1`,
  versionCode 1, minSdk 26, targetSdk 36, app name "Notscape Pioneer",
  launcher activity `net.nspioneer.browser.MainActivity`.
- ABIs: **arm64-v8a only** (14 native libs, 176,987,824 bytes).
- Engine present: `lib/arm64-v8a/libxul.so` 154,257,040 bytes, plus
  libmozglue, libnss3, libgkcodecs, libmozavcodec, libmozavutil,
  libclearkey (ClearKey CDM), `assets/omni.ja` 15,183,305.
- Robert's wagon icon present at all densities (+ foreground);
  probe extension assets present (`assets/extensions/pioneer-probe/`).
- Permissions: INTERNET, ACCESS_NETWORK_STATE + Cast framework's
  FOREGROUND_SERVICE / WAKE_LOCK / MODIFY_AUDIO_SETTINGS.

## Size finding (matters for parity)

libxul.so alone is ~154 MB. Even a shrunk release build will stay
~170–180 MB — roughly 3x Mariner's 58 MB release APK. The Gecko engine
is the cost; nothing in the spike can change that.

## Spike questions: answered by the build vs needing Robert's device

Answered by the build (no device needed):
- Latest stable GeckoView 159 resolves from Mozilla Maven and compiles
  into a real APK; the engine + omni.ja + probe extension are in the APK.
- Real APK size: 210,818,321 bytes debug (~201 MiB); engine ~154 MB.
- Toolchain floor for any current stable GeckoView: AGP 9.1.0+ /
  compileSdk 37 metadata (with the platform unpublished, compileSdk 36 +
  disabled metadata check is the working compromise).

Need Robert's on-device test (cannot be answered from CI):
- Install + run; cold-start time; PSS memory (panel reports both on-device).
- uBlock Origin installs from AMO and blocks (auto-approve prompt is
  spike-only; install runs at first launch).
- Protected video / Widevine playback (demo.castlabs.com button).
- Cast discovery from the Gecko app (probe only, no session launch).
- Media-probe output vs Mariner's WebView finder on the same pages
  (probe logs detected media URLs to the on-screen panel + logcat
  tag `PioneerSpike`).
- Whether anything in the spike trips on the API-37-compiled
  dependencies at runtime on his device (compileSdk 36 compromise).

## Device test — Robert, 2026-11-11 20:31

"It all works": uBlock Origin installs from AMO and blocks; Widevine plays
(demo.castlabs.com). Still owed: Cast discovery, media probe vs Mariner's
finder on the same pages, cold-start time and PSS memory numbers.

## Copy status button (0.1.0-alpha.2)

Robert ordered a "Copy status" button (2026-11-11 20:31): copies the full
panel (header + status + media probe) to the clipboard, confirmation line
added to the panel itself. Version bumped to 0.1.0-alpha.2 / code 2.
