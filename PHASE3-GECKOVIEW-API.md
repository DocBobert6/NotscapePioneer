# Notscape Pioneer — Phase 3 GeckoView API verification

**Target:** GeckoView **159 (stable)** — `org.mozilla.geckoview:geckoview:159.0.20261102144711`
(published 2026-11-03, per Maven Central).
**Researched:** 2026-11-11/12. **Status:** all items verified against Mozilla sources, not memory.

## Methodology / version caveat — read first

Mozilla publishes Javadoc **only for mozilla-central** (checked build
`162.0.20261111213251`); there is **no versioned 159 Javadoc**. Source was
cross-checked on searchfox mozilla-central. Every API below was present **at
or before 159** unless flagged. The v160–162 delta DOES matter this phase,
because two Phase 3-adjacent APIs landed there:

- **Picture-in-Picture is v160, not v161** (correction to the Phase 2-era
  note that said v161): the changelog puts the experimental PiP APIs under
  **v160** (bug 2065581 / 2065579). Either way it is **after 159 — NOT
  available to Phase 3**.
- **v160 content-disposition validation** (bug 1822968): the `WebResponse`
  passed to `onExternalResponse` carries a desktop-validated filename only
  from v160. On **159 the header is the raw server-sent value** and the app
  must parse/validate it itself.

"Since" versions come from the GeckoView API Changelog
(`doc-files/CHANGELOG.md`) or the cited Bugzilla bug where the changelog is
silent. Where no introduction version could be pinned, this is stated as
"long-standing (present well before 159; exact version not pinned)" rather
than guessed.

Key Javadoc: <https://mozilla.github.io/geckoview/javadoc/mozilla-central/>
Source: <https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/>
Changelog: <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/doc-files/CHANGELOG>
Permissions doc: <https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/permissions.html>

---

## 1. Downloads — `ContentDelegate.onExternalResponse` — YES

```java
@UiThread
default void onExternalResponse(@NonNull GeckoSession session,
                                @NonNull WebResponse response)
```

- **Since:** **v82** in this `WebResponse` form (bug 1530022). The older
  `WebResponseInfo` overload was deprecated in v82 and **removed in v86** —
  do not call it, do not implement it.
- **Doc:** <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ContentDelegate.html>
- **Semantics (Javadoc):** "Fired when there is a response that cannot be
  handled by Gecko (e.g., a download)."
- **There is no separate "download delegate" for the app.** The only other
  download-adjacent API is `WebExtensionController`'s `DownloadDelegate`,
  which serves **extensions** using the `browser.downloads` WebExtension API
  — it is not an app-facing download API and Phase 3 does not use it.

### 1a. `WebResponse` — YES, but it is NOT `WebResponseInfo`

```java
@WrapForJNI @AnyThread
public class WebResponse extends WebMessage   // NOT WebResponseInfo
```

- **Source:** <https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/WebResponse.java>
- **Doc:** <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebResponse.html>
- **Inherited from `WebMessage`** (`WebMessage` Javadoc:
  <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/WebMessage.html>):
  `public final String uri`, `public final Map<String,String> headers`
  (unmodifiable map of headers).
- **Own fields (all `public final`):**
  `int statusCode`, `boolean redirected`, `boolean isSecure`,
  `@Nullable X509Certificate certificate`,
  `@Nullable InputStream body`,
  `@Nullable boolean requestExternalApp`,
  `@Nullable boolean skipConfirmation`.
- **Body semantics (source, WebResponse.java):** "An InputStream containing
  the response body, if available. Attention: the stream must be closed
  whenever the app is done with it, even when the body is ignored.
  Otherwise the connection will not be closed until the stream is garbage
  collected." `public void setReadTimeoutMillis(long millis)`,
  `public static final long DEFAULT_READ_TIMEOUT_MS = 30000`
  (0 = block indefinitely). **Design consequence:** since v82 the app can
  read `response.body` directly instead of re-fetching — no second request,
  cookies preserved. Consume it on a background thread; close it always.
- **There are NO direct `filename` / `contentLength` / `contentType`
  fields on `WebResponse`.** Those lived on the removed `WebResponseInfo`
  (`uri`, `contentType`, `contentLength`, `@Nullable filename` from
  content-disposition — see
  <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.WebResponseInfo.html>).
  On 159 the app must parse them from `response.headers` itself:
  `Content-Disposition` (filename), `Content-Length`, `Content-Type`.
  **v160 only:** the content-disposition filename in the `WebResponse` is
  validated with desktop download-saving rules (bug 1822968) — **not in
  159**, so Phase 3 parses and sanitises the raw header exactly as Mariner
  does (`URLUtil.guessFileName` / `Downloads.resolveFileName` equivalent).
- `requestExternalApp`: "the contents should request to be opened in
  another Android application. For example, provide PDF content and set
  this to true to request that Android opens the PDF in a system PDF
  viewer." Phase 3 does **not** set it (PDFs go through the download flow
  as files; see PHASE3-DESIGN.md §8.4).
- `skipConfirmation`: "the app may skip requesting the download in the UI.
  A confirmation of the download will still be shown." Phase 3 does **not**
  set it (Mariner's own confirm dialog is the confirmation).

---

## 2. Fullscreen — `ContentDelegate.onFullScreen` — YES

```java
@UiThread
default void onFullScreen(@NonNull GeckoSession session, boolean fullScreen)
```

- **Since:** long-standing (present well before 159; exact version not
  pinned). Present in the archived 85 Javadoc era.
- **Doc:** `GeckoSession.ContentDelegate` Javadoc (link in §1).
- **Semantics (Javadoc):** "A page has entered or exited full screen mode.
  Typically, the implementation would set the Activity containing the
  GeckoSession to full screen when the page is in full screen mode."
- **What the app must do itself:** hide/show the system bars, resize the
  container to `MATCH_PARENT`, and restore both on exit. GeckoView does
  none of that. There is no custom-view object handed over (unlike
  WebView's `onShowCustomView`); the page's fullscreen element stays
  inside the `GeckoView` — the app only changes chrome around it.

---

## 3. File chooser — `PromptDelegate.onFilePrompt` — YES

```java
@UiThread
default @Nullable GeckoResult<PromptResponse> onFilePrompt(
    @NonNull GeckoSession session, @NonNull FilePrompt prompt)
```

- **Since:** PromptDelegate rewrite **v70** (bug 1499394); `capture`
  parameter added **v70** (bug 1553603).
- **Doc:** <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.PromptDelegate.FilePrompt.html>
- `FilePrompt` fields: `int type`, `@Nullable String[] mimeTypes` (from the
  input's `accept` attribute), `int capture`.
- `FilePrompt.Type`: `SINGLE = 1`, `MULTIPLE = 2`,
  **`FOLDER` — since v138** (bug 1674428; changelog: "Added
  `PromptDelegate.FilePrompt.Type.FOLDER` to show directory picker").
  Type Javadoc:
  <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.PromptDelegate.FilePrompt.Type.html>
- `FilePrompt.Capture`: `NONE = 0`, `ANY = 1`, `USER = 2`,
  `ENVIRONMENT = 3`.
- Confirm (both `@UiThread`):
  `PromptResponse confirm(@NonNull Context context, @NonNull Uri uri)`,
  `PromptResponse confirm(@NonNull Context context, @NonNull Uri[] uri)`.
  **SINGLE with ≠ 1 URI throws `IllegalArgumentException`.**
- **Folder upload:** `onFolderUploadPrompt` +
  `PromptDelegate.FolderUploadPrompt` — **since v138** (bug 1674428).
- **Trap — `getFile()` / `content://` URIs:** the prompt's file resolution
  handles `file://` directly but resolves `content://` only via a `_data`
  column query; it returns null (logging "Only file URIs are supported")
  for SAF URIs without `_data`. **The chooser must copy a picked
  `content://` file to a temp file and confirm with a `file://` URI.**
  Verify on device (Gate 3).

---

## 4. Popups — `PromptDelegate.onPopupPrompt` — YES

```java
@UiThread
default @Nullable GeckoResult<PromptResponse> onPopupPrompt(
    @NonNull GeckoSession session, @NonNull PopupPrompt prompt)
```

- **Since:** present in 159; exact introduction version not pinned by the
  changelog. (The 2018 proposal in bug 1481296 named it `onPopupRequest`;
  the shipped name is `onPopupPrompt`.)
- `PopupPrompt.targetUri` (`@Nullable String`); confirm via
  `confirm(@NonNull AllowOrDeny response): PromptResponse`.
- **Semantics (Javadoc):** "Display a popup request prompt; this occurs
  when content attempts to open a new window in a way that doesn't appear
  to be the result of user input."
- **Note:** this covers only the *request* to open a popup. A real new tab
  (user-gesture `window.open` / `target=_blank`) arrives as
  `NavigationDelegate.onNewSession` (Phase 2 §, already implemented).

---

## 5. Permissions — `PermissionDelegate` — YES (three categories)

Doc: <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.PermissionDelegate.html>
Guide: <https://firefox-source-docs.mozilla.org/mobile/android/geckoview/consumer/permissions.html>

> "There are three broad categories of permission that the
> `PermissionDelegate` handles, Android Permissions, Content Permissions
> and Media Permissions." — permissions doc.

### 5a. Content permissions — `onContentPermissionRequest` — YES

```java
@UiThread
default @Nullable GeckoResult<Integer> onContentPermissionRequest(
    @NonNull GeckoSession session, @NonNull ContentPermission perm)
```

- **Since:** **v91** in this `ContentPermission` form (bug 1699480:
  "Add setPermission, update onContentPermissionRequest to use
  ContentPermission, and let Gecko manage and persist permissions in GV").
  Default return is `VALUE_PROMPT`; the returned value updates the stored
  permission for the site.
- `ContentPermission` fields: `uri` (String), `thirdPartyOrigin`
  (`@Nullable String`, storage-access only), `privateMode` (boolean),
  `permission` (int), `value` (int), `contextId` (`@Nullable String`).
  Values: `VALUE_ALLOW = 1`, `VALUE_DENY = 2`, `VALUE_PROMPT = 3`.
- **Content permission kinds (constants on `ContentPermission`):**
  `PERMISSION_GEOLOCATION`, `PERMISSION_DESKTOP_NOTIFICATION`,
  `PERMISSION_PERSISTENT_STORAGE`, `PERMISSION_XR`,
  `PERMISSION_AUTOPLAY_INAUDIBLE`, `PERMISSION_AUTOPLAY_AUDIBLE`,
  `PERMISSION_MEDIA_KEY_SYSTEM_ACCESS`, `PERMISSION_TRACKING`
  ("Permission for trackers to operate on the page — disables all tracking
  protection features for a given site"), `PERMISSION_STORAGE_ACCESS`,
  `PERMISSION_LOCAL_DEVICE_ACCESS`, `PERMISSION_LOCAL_NETWORK_ACCESS`.
  String mapping (source): `"trackingprotection"` /
  `"trackingprotection-pb"` → `PERMISSION_TRACKING`; `"geolocation"`,
  `"desktop-notification"`, `"persistent-storage"`, `"xr"`,
  `"autoplay-media-inaudible"`, `"autoplay-media-audible"`,
  `"media-key-system-access"`, `"storage-access"` likewise.
- **There is NO separate `onGeolocationPermissionRequest`.**
  Geolocation arrives here as `PERMISSION_GEOLOCATION` (WebView's
  `onGeolocationPermissionsShowPrompt` has no GeckoView equivalent).
- **Persistence (permissions doc):** "Once a permission has been set in
  this fashion, GeckoView will persist it across sessions until it is
  cleared or modified. When a page is loaded, the active permissions
  associated with it (both allowed and denied) will be reported in
  `onLocationChange` as a list of `ContentPermission` objects."
- **`PERMISSION_PERSISTENT_STORAGE` once granted cannot be revoked** — the
  app must remember the grant and suppress re-prompting itself.
- **`ContentPermission.notifyShown()` — added v154** (bug 2009145):
  lets the app signal that a permission prompt UI was displayed, for
  per-prompt telemetry. Optional; Phase 3 calls it when it shows Mariner's
  dialog.
- **Old TP-exception API is deprecated:** bug 1699480 also did "Deprecate
  most existing TP exception API in GV and migrate it to new permissions
  API". Per-site ETP exception = `setPermission` with
  `PERMISSION_TRACKING` (see §5b), not the old
  `ContentBlockingController` exception methods.

### 5b. Reading / writing stored permissions — `StorageController` — YES

Doc: <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/StorageController.html>
Access: `runtime.storageController` (Phase 2 verified `GeckoRuntime`
exposes the controller; `ContentBlockingController` is deprecated since
**v121** — use `StorageController`).

```java
GeckoResult<ContentPermission[]> getAllPermissions()
GeckoResult<ContentPermission[]> getPermissions(String uri, boolean privateMode)
GeckoResult<ContentPermission[]> getPermissions(String uri, String contextId, boolean privateMode)
void setPermission(ContentPermission permission, int value)   // value = VALUE_ALLOW / VALUE_DENY / VALUE_PROMPT
void setPrivateBrowsingPermanentPermission(ContentPermission permission, int value)
```

- **Since:** **v91** (bug 1699480 overhaul). `getPermissions(String,
  boolean)` is marked "This API will be deprecated in the future"
  (bug 1797379) — prefer the `contextId` overload in new code.
- **Semantics (permissions doc):** "one may check all stored content
  permissions by calling `getAllPermissions` and the content permissions
  associated with a given URI by calling `getPermissions`. In order to
  modify an existing permission, you will need the associated
  `ContentPermission` (which can be retrieved from any of the above
  methods); then, call `setPermission` with the desired new value, or
  `VALUE_PROMPT` if you wish to unset the permission and let the site
  request it again in the future."
- **Caveat:** `getPermissions(url)` in private mode historically returned
  empty (bug 1723280, duplicate) — Phase 3 does not rely on reading
  private-mode permissions back; it keeps its own `PioneerStore` record
  as the source of truth for what to show in Settings (see design doc).
- **Android-permission denials are NOT stored by GeckoView** — the app
  must store the response state itself (Mariner already does, via
  `siteperm:$host:$resource`).

### 5c. Android permissions — `onAndroidPermissionsRequest` — YES

```java
@UiThread
default void onAndroidPermissionsRequest(
    @NonNull GeckoSession session, @Nullable String[] permissions,
    @NonNull Callback callback)
```

- **Since:** long-standing (present well before 159).
- Possible values: `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`,
  `CAMERA`, `RECORD_AUDIO`. `Callback.grant()` / `Callback.reject()`.
  Default implementation rejects.
- **Design consequence:** the OS permission is a separate gate from the
  site decision. Mariner's `grantWithOsCheck` pattern ports directly:
  site decision first (dialog), then request the missing Android
  permission(s), then grant to Gecko only what the OS allowed.

### 5d. Media permissions (camera / mic) — `onMediaPermissionRequest` — YES

```java
@UiThread
default void onMediaPermissionRequest(
    @NonNull GeckoSession session, @NonNull String uri,
    @Nullable MediaSource[] video, @Nullable MediaSource[] audio,
    @NonNull MediaCallback callback)
```

- **Since:** long-standing (present well before 159). Default rejects.
- **Must call grant or reject per request** — an unanswered request hangs
  the page's `getUserMedia` promise.
- `MediaCallback.grant(MediaSource video, MediaSource audio)` /
  `grant(String video, String audio)` / `reject()`.
- `MediaSource` fields: `id`, `name`, `source`, `type`.
  `source`: `SOURCE_CAMERA = 0`, `SOURCE_SCREEN = 1`,
  `SOURCE_MICROPHONE = 2`, `SOURCE_AUDIOCAPTURE = 3`, `SOURCE_OTHER = 4`.
  `type`: `TYPE_VIDEO = 0`, `TYPE_AUDIO = 1`.
- **Trap:** requests still arrive even when the OS permission was denied,
  if sources exist — the app must suppress the UI in that case instead of
  prompting for something it cannot grant.

---

## 6. Content blocking observation — `ContentBlocking.Delegate` — YES

```java
@UiThread
default void onContentBlocked(@NonNull GeckoSession session,
                              @NonNull BlockEvent event)
@UiThread
default void onContentLoaded(@NonNull GeckoSession session,
                             @NonNull BlockEvent event)
```

- **Since:** long-standing (present well before 159).
- `BlockEvent`:
  `BlockEvent(String uri, int atCat, int sbCat, int cbCat, boolean isBlocking)`;
  field `public final String uri`;
  `getAntiTrackingCategory()`, `getSafeBrowsingCategory()`,
  `getCookieCategory()`, `isBlocking()`.
  Doc: <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/ContentBlocking.BlockEvent.html>
- Anti-tracking categories: `AD`, `ANALYTIC`, `CONTENT`, `CRYPTOMINING`,
  `FINGERPRINTING`, `SOCIAL`, `EMAIL`, `DEFAULT`, `STRICT`.
  Safe-browsing categories: `MALWARE`, `UNWANTED`, `HARMFUL`, `PHISHING`.
- **Use in Phase 3:** blocked-count telemetry only (per-tab count for the
  site-info sheet, same number Mariner shows from `FilterEngine`). ETP
  itself is **not** the blocker — uBlock Origin + Mariner's lists are
  (see design doc §8.1). `onContentLoaded` fires for content that *could*
  have been blocked but loaded — do not count it as blocked.
- `ContentBlockingController`: **deprecated v121** (use
  `StorageController`); `clearTrackingDb` added **v153**;
  tracking-db query APIs (`getTrackingDbEventsByDateRange`,
  `sumAllTrackingDbEvents`, `getTrackingDbEarliestRecordedDate`) added
  **v152**; delegate setters changed `@AnyThread` → `@UiThread` in
  **v154**.
- **Cookie Banner Handling was REMOVED in v154** (bug 2058143):
  `ContentBlocking.CookieBannerMode`, the `Settings` cookie-banner
  setters, `ContentDelegate.onCookieBannerDetected` /
  `onCookieBannerHandled`, and the `StorageController` cookie-banner
  methods are gone in 159. Mariner has no cookie-banner handling either,
  so nothing is lost.
- ETP level (Phase 2 §7, restated): set at runtime via
  `runtime.settings.contentBlocking.setEnhancedTrackingProtectionLevel(EtpLevel.STRICT
  / DEFAULT / NONE)` — **v71** — and it is a no-op unless the cookie
  behaviour is `ACCEPT_NON_TRACKERS` or
  `ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS`.

---

## 7. Media session — `MediaSession` — YES, with a v83 limit

```java
public class MediaSession   // created per GeckoSession; delegate via
                            // GeckoSession.setMediaSessionDelegate(...)
void play() / pause() / stop()
void seekTo(double time, boolean fast)
void seekForward() / seekBackward()
void nextTrack() / previousTrack()
void skipAd()
void muteAudio(boolean mute)
boolean isActive()
void notifySystemAudioFocusChange(int change)   // added v154
```

- **Since:** added **v81**. `notifySystemAudioFocusChange` added **v154**
  (bug 2048732). `onAudioSessionTypeChanged` added **v153**.
- **Delegate** (`MediaSession.Delegate`, `@UiThread`):
  `onActivated`, `onDeactivated`, `onAudioSessionTypeChanged(session,
  mediaSession, String type)`, `onFeatures(session, mediaSession, long
  features)`, `onFullscreen(session, mediaSession, boolean enabled,
  ElementMetadata meta)`, `onMetadata(session, mediaSession, Metadata
  meta)`, `onPlay`, `onPause`, `onStop`, `onPositionState(session,
  mediaSession, PositionState state)`.
  Doc: <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/MediaSession.Delegate.html>
  and <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/MediaSession.html>
- **v83 limit (changelog): `MediaSession.Delegate.onMetadata` is no
  longer dispatched for plain media elements.** It fires only for pages
  that use the Media Session API (YouTube, Spotify web, etc. do). A plain
  `<video src="…">` page produces **no** metadata event. Phase 3 therefore
  uses `MediaSession` only as a *supplementary* now-playing signal (title /
  play-pause state for API pages); it is **not** the media detector.
- **Setter thread:** `GeckoSession.setMediaSessionDelegate` changed
  `@AnyThread` → `@UiThread` in **v154** — call it on the UI thread.

### 7a. `MediaElement` — NO (removed)

- Added v65, **deprecated v88, removed v91 — do NOT use**. Its
  replacement is `MediaSession` (§7).
- `GeckoSession.MediaDelegate` has **only**
  `onRecordingStatusChanged(session, devices)` (microphone/camera
  recording state) — it is **not** a media-element detector.

---

## 8. Picture-in-Picture — NO in 159 (added v160, experimental)

- `PictureInPictureController.SessionPictureInPicture` +
  `GeckoSession.setPictureInPictureDelegate` /
  `getSessionPictureInPicture` / `request` / `onEnterPictureInPicture` /
  `onExitPictureInPicture` / `setKeepActive` +
  `GeckoSessionSettings.Builder.pictureInPictureToken` — **added v160**
  (bugs 2065581, 2065579), marked experimental in the changelog.
- **Pioneer pins 159: none of this exists.** The pop-up player must
  launch Pioneer's own player activity into Android PiP
  (`enterPictureInPictureMode`), exactly as Mariner does. Do not write
  code against the v160 API and do not bump GeckoView to get it.

---

## 9. Extension uninstall / enable / disable — YES

```java
@AnyThread @NonNull
public GeckoResult<Void> uninstall(@NonNull WebExtension extension)
@AnyThread @NonNull
public GeckoResult<WebExtension> enable(@NonNull WebExtension extension,
                                        @EnableSources int source)
@AnyThread @NonNull
public GeckoResult<WebExtension> disable(@NonNull WebExtension extension,
                                         @EnableSources int source)
@AnyThread @NonNull
public GeckoResult<List<WebExtension>> list()
@AnyThread @NonNull
public GeckoResult<WebExtension> update(@NonNull WebExtension extension)
```

- **Since:** `uninstall` **v73** (bug 1599580; changelog). `enable` /
  `disable` / `update` **v74**. `EnableSource.USER = 1`, `APP = 2`.
- **Source:** <https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/WebExtensionController.java>
  (`uninstall` at line 2013).
- **Semantics (Javadoc):** "Uninstalling an extension will remove it
  from the current `GeckoRuntime` instance, delete all its data and
  trigger a request to close all extension pages currently open."
- `AddonManagerDelegate` callbacks: `onInstalled`, `onInstalling`,
  `onUninstalled`, `onUninstalling`, `onDisabled`, `onDisabling`,
  `onEnabled`, `onEnabling`.
- **Phase 2 gap closed:** Phase 2 noted "no Engine uninstall method
  yet" and Settings said removing uBlock was not supported. It **is**
  supported — Phase 3 adds `Engine.uninstallUBlock()` on top of this API.
  (Uninstalling uBlock removes its data; the per-site exception state
  lives in `PioneerStore`, not in the extension, so it survives.)

---

## 10. `webRequest` in a built-in extension — YES (with a build-time check)

- **Verified:** Mozilla's own **webcompat built-in extension uses the
  `webRequest` API** in GeckoView/Fenix — bug 1728461 states: "The
  extension uses the webRequest API. Only one built-in extension uses the
  API, i.e. the webcompat add-on"
  (<https://bugzilla.mozilla.org/show_bug.cgi?id=1728461>; the add-on
  lives at `components/feature/webcompat/src/main/assets/extensions/webcompat/`
  in android-components).
- **Compat list:** sammacbeth.eu (2019, GeckoView API list) marks
  `webRequest` ✔ and `webNavigation` ✔:
  <https://sammacbeth.eu/running-webextensions-on-android-with-geckoview>
- **MDN:** `webRequest` lists Firefox for Android 48+:
  <https://developer.mozilla.org/en-US/docs/Mozilla/Add-ons/WebExtensions/API/webRequest>
- **Why this matters:** Mozilla kept MV2 **blocking** `webRequest` on
  Firefox for Android, which is why full uBlock Origin works there — and
  why Pioneer's probe background script can observe requests the same way.
- **Requirements for the probe:** MV2 manifest, `"webRequest"` permission
  (+ `"webRequestBlocking"` only if the script *blocks*, which the probe
  does not — it only observes), host permission `"<all_urls>"`, and a
  **background script** (content scripts cannot use `webRequest`).
- **Still open (builder's first verification item):** confirm the probe's
  background `webRequest.onBeforeRequest` listener actually fires on
  **159** in Pioneer's runtime and reports a YouTube manifest URL. The
  sources above prove the capability exists in GeckoView; they do not
  prove Pioneer's wiring. If it does not fire, the YouTube-detection row
  of the Gate 3 scorecard fails honestly — do not fake it with DOM data.

---

## 11. `evaluateJavascript` — NO (does not exist)

- There is **no `evaluateJavascript`** (or any equivalent) in GeckoView.
  All JavaScript runs through a WebExtension (content script / background
  script + native messaging), as Phase 2 established. This is why cosmetic
  injection happens at `document_idle` from the probe instead of at
  document start from the app (see design doc §8.1).

## 12. Per-session text zoom — NO (does not exist)

- Phase 2 §2, restated: no per-session text-zoom API. Probe content
  script applies the factor (`documentElement` font-size scaling) on a
  `"zoom"` native message; fallback is runtime-global
  `GeckoRuntimeSettings.setFontSizeFactor` (NOT per-site — report as a
  gap, do not pretend otherwise).

## 13. Extension store / AMO browsing UI — NO (does not exist)

- GeckoView has no add-on store UI. Extensions install by URL
  (`WebExtensionController.installBuiltIn` / AMO `.xpi` URL), as Phase 2
  does for uBlock Origin. Only Robert-approved extensions by name;
  uBlock Origin first.

## 14. `WebResponseInfo` overload of `onExternalResponse` — NO (removed v86)

- Deprecated v82, **removed v86**. Any code or tutorial showing
  `onExternalResponse(session, WebResponseInfo)` does not compile against
  159. Use the `WebResponse` form (§1).
