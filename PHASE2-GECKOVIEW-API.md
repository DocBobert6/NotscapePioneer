# Notscape Pioneer — Phase 2 GeckoView API verification

**Target:** GeckoView **159 (stable)** — `org.mozilla.geckoview:geckoview:159.0.20261102144711`
(published 2026-11-03, per Maven Central).
**Researched:** 2026-11-11/12. **Status:** all 9 items verified against Mozilla sources, not memory.

## Methodology / version caveat — read first

Mozilla publishes Javadoc **only for mozilla-central** (checked build
`162.0.20261111213251`); there is **no versioned 159 Javadoc**. Source was
cross-checked on searchfox mozilla-central (`GeckoSession.java`,
`SessionFinder.java`). Every API below was present **at or before 159**
unless flagged, so the 162-vs-159 delta does not affect the findings:
the newest API cited is `flushSessionState()` (v144) and `ContextElement.linkText`
(v148) — both ≤ 159. Nothing in this document relies on anything added in
v160–162.

"Since" versions come from the GeckoView API Changelog
(`doc-files/CHANGELOG.md`) or the cited Bugzilla bug where the changelog is
silent. Where the changelog does not pin an introduction version, this is
stated as "≤ 85, long-standing" (verified present in the archived 85 Javadoc)
rather than guessed.

Key Javadoc: <https://mozilla.github.io/geckoview/javadoc/mozilla-central/>
Source: <https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/>
Changelog: <https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/doc-files/CHANGELOG>

---

## 1. Find in page — YES, `SessionFinder`

There is no `session.finder` property. Access is via a getter returning a
`SessionFinder` instance owned by the session.

```kotlin
// GeckoSession.java — @AnyThread
@NonNull public SessionFinder getFinder()

// SessionFinder.java — @AnyThread
@NonNull public GeckoResult<GeckoSession.FinderResult> find(
    @Nullable String searchString,
    @FinderFindFlags int flags)

public void clear()
public void setDisplayFlags(@FinderDisplayFlags int flags)
@FinderDisplayFlags public int getDisplayFlags()
```

- **Find / find-next / find-previous:** all go through the same `find()`.
  `searchString == null` means "find again using the previous search string",
  so next = `find(null, GeckoSession.FINDER_FIND_FORWARD)`,
  previous = `find(null, GeckoSession.FINDER_FIND_BACKWARDS)`.
  Results arrive asynchronously as a `GeckoResult<FinderResult>` (use
  `.then { … }` / `.accept { … }` — there is no callback delegate for find).
- **Flags** (`GeckoSession` constants): `FINDER_FIND_FORWARD`,
  `FINDER_FIND_BACKWARDS`, `FINDER_FIND_MATCH_CASE` (default insensitive),
  `FINDER_FIND_WHOLE_WORD`, `FINDER_FIND_LINKS_ONLY`.
- **Display flags** (`setDisplayFlags`): `FINDER_DISPLAY_HIGHLIGHT_ALL`,
  `FINDER_DISPLAY_DIM_PAGE`, `FINDER_DISPLAY_DRAW_LINK_OUTLINE`.
- **Clearing:** `clear(): void` — "Clear any highlighted find-in-page matches."
  There is no separate "close find session" call; `clear()` is the whole API.
- **Results** — `GeckoSession.FinderResult`, all `public final` fields:
  ```java
  public final boolean found;          // whether a match was found
  public final boolean wrapped;        // wrapped around top/bottom of page
  public final int current;            // ordinal of current match from 1, 0 if no match
  public final int total;              // total matches found SO FAR, or -1 if unknown
  public final @NonNull String searchString;
  public final @FinderFindFlags int flags;
  public final @Nullable String linkUri;   // URI if the current match is a link
  public final @Nullable RectF clientRect; // bounds of current match, client coords
  ```
  Note: there is **no "find all, return total count" call**. `total` is
  "found so far" and may be `-1` (unknown). A `current / total` ("3 of 12")
  UI must tolerate `total == -1` and update as the user steps through matches.
- **Since:** GeckoView **62** — bug 1463484, "Add SessionFinder", landed
  2018-07-10 (Firefox 62).

Links: [SessionFinder Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/SessionFinder.html) ·
[FinderResult Javadoc](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.FinderResult.html) ·
[SessionFinder.java (searchfox)](https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/SessionFinder.java) ·
[bug 1463484](https://bugzilla.mozilla.org/show_bug.cgi?id=1463484)

---

## 2. Text zoom — NO per-session API; runtime-global only

There is **no `setTextZoom` on `GeckoSession`** (verified: no such method in
the `GeckoSession` Javadoc method list) and no per-session/per-site text-size
setting anywhere in `GeckoSessionSettings`. The only text-zoom API is a
**global font-size factor on the runtime**:

```kotlin
// GeckoRuntimeSettings — live settings object, not builder-only
public GeckoRuntimeSettings setFontSizeFactor(float fontSizeFactor)
public float getFontSizeFactor()
// Builder form (at runtime creation):
GeckoRuntimeSettings.Builder.fontSizeFactor(float fontSizeFactor)
```

- "Set a font size factor that will operate as a **global text zoom**. All font
  sizes will be multiplied by this factor. The default factor is 1.0."
  Setting `0` disables both this feature and font inflation.
- "This setting cannot be modified if `automatic font size adjustment` has
  already been enabled" — i.e. `setAutomaticFontSizeAdjustment(true)` (follow
  the device system font scale) and `setFontSizeFactor` are mutually exclusive;
  pick one.
- Related: `setFontInflationEnabled(boolean)` (font inflation for pages with
  no `<meta viewport>`, magnitude depends on the font-size factor in use).
- **Reload behaviour — UNVERIFIED for 159:** the old (v85) Javadoc said
  "Currently, any changes only take effect after a reload of the session."
  The current (162) Javadoc no longer carries that sentence. Treat "change
  factor, then `session.reload()`" as the safe pattern until verified on-device.
- **Consequence for per-site zoom:** it cannot be done with a GeckoView API.
  Options are (a) runtime-global factor + reload (all tabs change together),
  or (b) a built-in WebExtension content script that injects per-site CSS
  (e.g. `zoom` / `font-size` on the document) — which requires the extension
  route in §8, since there is no public JS-eval API.
- **Since:** v**67** — changelog: "Added `setFontSizeFactor` to
  `GeckoRuntimeSettings` … and `setAutomaticFontSizeAdjustment`".

Links: [GeckoRuntimeSettings.Builder#fontSizeFactor](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.Builder.html) ·
[GeckoRuntimeSettings](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.html)

---

## 3. Session save / restore — YES, but there is no `saveState()` call

State is **pushed to the app**; the app stores it and hands it back later.
There is no `GeckoSession.saveState()` method in GeckoView 162 (verified by
searching the full `GeckoSession` method list).

```kotlin
// ProgressDelegate — @UiThread; fires on navigation, scroll, form-data change
default void onSessionStateChange(
    @NonNull GeckoSession session,
    @NonNull GeckoSession.SessionState sessionState)

// GeckoSession — @AnyThread
public void restoreState(@NonNull SessionState state)
public void flushSessionState()          // added v144, see below

// SessionState — Parcelable + List<HistoryItem>
public static class SessionState
    extends AbstractSequentialList<HistoryDelegate.HistoryItem>
    implements HistoryDelegate.HistoryList, Parcelable {
  public SessionState(@NonNull SessionState state)   // copy constructor
  public static @Nullable SessionState fromString(@Nullable String value)
  public @Nullable String toString()                 // JSON — persist this
  public int getCurrentIndex()
  public HistoryDelegate.HistoryItem get(int index)  // getUri(), getTitle()
}
```

- **Save (the app's job):** implement `ProgressDelegate.onSessionStateChange`
  and keep the latest `SessionState` per tab. "The browser session state has
  changed. This can happen in response to navigation, scrolling, or form data
  changes; the session state passed includes the most up to date information
  on all of these." Persist it with `SessionState.toString()` (JSON string)
  or as a `Parcelable` in a `Bundle`; re-create with
  `SessionState.fromString(String)`.
- **Force a fresh state snapshot:** `flushSessionState()` — changelog v144
  (bug 1970055): "Added `GeckoSession.flushSessionState()` to immediately
  notify the registered `GeckoSession.ProgressDelegate` and
  `GeckoSession.HistoryDelegate` of the current session state." Call it
  before backgrounding/persisting instead of trusting the last pushed state.
  (Return type not legible in the extracted Javadoc text — treat as
  fire-and-forget; confirm against the AAR `api.txt` if the call site needs it.)
  Note `setActive(false)` also flushes state internally and triggers
  `onSessionStateChange`.
- **Restore:** `restoreState(@NonNull SessionState state): void` —
  "Restore a saved state to this GeckoSession; only data that is saved
  (history, scroll position, zoom, and form data) will be restored. …
  @param state A saved session state; this should originate from
  onSessionStateChange()." Pattern: create a new `GeckoSession`, `open(runtime)`,
  then `restoreState(state)`. After a content crash/kill, the documented
  recovery is `open(runtime)` then `load` or `restoreState`.
- **Tab-restore recipe for Phase 2:** per tab, keep the latest `SessionState`
  from `onSessionStateChange` (+ `flushSessionState()` on pause); persist the
  `toString()` JSON in the store; on relaunch, new session → `open()` →
  `restoreState(SessionState.fromString(json))`.
- **Since:** `restoreState` / `SessionState` / `onSessionStateChange` are
  long-standing — **≤ 85, exact introduction version not pinned in the
  changelog** (all present in the archived 85 Javadoc). `flushSessionState`
  is v**144**.

### PioneerStore durability note (contract addition, 2026-11-11)

The Phase 2 store worker added a synchronous save hook that the tab-restore
flow depends on:

```kotlin
// app/src/main/java/net/nspioneer/browser/data/PioneerStore.kt:545
fun flush()   // "Write every dirty file to disk NOW, on the calling thread."
```

Its KDoc: *"The shell calls this from `Activity.onPause()` — the debounced
save alone is not enough, because the process may die before the 500 ms timer
fires."* The GeckoView state delivered by `onSessionStateChange` is only
durable if the store flushes it — so the Phase 2 shell must call
`store.flush()` from `MainActivity.onPause()`.

**Status at research time:** the `MainActivity` in the tree is still the
Phase 1 spike (header: "THROWAWAY by design") with **no `PioneerStore`
reference at all**; its `onPause()` (lines 162–165) only deactivates the
session and does **not** call `flush()`. Wiring the store into the shell and
adding the `flush()` call is Phase 2 shell work, not done by this read-only
research task.

Links: [GeckoSession#restoreState](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.html) ·
[SessionState](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.SessionState.html) ·
[ProgressDelegate](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ProgressDelegate.html) ·
[GeckoSession.java `restoreState` (searchfox)](https://searchfox.org/mozilla-central/source/mobile/android/geckoview/src/main/java/org/mozilla/geckoview/GeckoSession.java)

---

## 4. Private sessions — YES, construction-time only

```kotlin
// Constructors — GeckoSession Javadoc index
public GeckoSession()                                    // default settings
public GeckoSession(GeckoSessionSettings settings)       // with settings

// GeckoSessionSettings.Builder
public GeckoSessionSettings.Builder usePrivateMode(boolean flag)  // default false
// GeckoSessionSettings
public boolean getUsePrivateMode()
```

```kotlin
val settings = GeckoSessionSettings.Builder()
    .usePrivateMode(true)
    .build()
val privateSession = GeckoSession(settings)
privateSession.open(runtime)
```

- Private mode is a **construction-time** setting. There is **no
  `setUsePrivateMode` on `GeckoSessionSettings`** (verified: the method list
  has only `getUsePrivateMode()`), so an existing/open session cannot be
  flipped private — create a new session with new settings instead.
- Related isolation knob: `Builder.contextId(String)` — "Setting a context ID
  partitions the cookie jars based on the provided [ID]"; default `null`
  removes isolation. Useful if private tabs should not share state with each
  other either.
- Do not confuse with `GeckoSessionSettings.setUseTrackingProtection(boolean)`
  (legacy per-session tracking-protection on/off, default false) — ETP level
  itself is runtime-level, see §7.
- **Since:** long-standing — **≤ 85, exact version not pinned** (present in
  the archived 85 Javadoc / Builder).

Links: [GeckoSessionSettings](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSessionSettings.html) ·
[GeckoSessionSettings.Builder](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSessionSettings.Builder.html)

---

## 5. User-agent override / "Desktop site" — YES, per-session, live-mutable

```kotlin
// GeckoSessionSettings — setters live on the session's own settings object
public void setUserAgentMode(int value)            // USER_AGENT_MODE_* flags
public int getUserAgentMode()
public void setUserAgentOverride(@Nullable String value)  // null = use mode's UA
public @Nullable String getUserAgentOverride()
public void setViewportMode(int value)             // VIEWPORT_MODE_* flags
public int getViewportMode()

// Builder equivalents (at session creation)
GeckoSessionSettings.Builder.userAgentMode(int mode)
GeckoSessionSettings.Builder.userAgentOverride(String agent)
GeckoSessionSettings.Builder.viewportMode(int mode)

// Constants
USER_AGENT_MODE_MOBILE / USER_AGENT_MODE_DESKTOP / USER_AGENT_MODE_VR
VIEWPORT_MODE_MOBILE   // <meta viewport> based
VIEWPORT_MODE_DESKTOP  // all pages rendered with a 980 CSS px viewport

// Read-back
@NonNull public GeckoResult<String> getUserAgent()   // GeckoSession
public static String getDefaultUserAgent()
```

Desktop-site toggle (exact pattern):

```kotlin
val settings = session.settings          // GeckoSession.getSettings(), @AnyThread
settings.userAgentMode = GeckoSessionSettings.USER_AGENT_MODE_DESKTOP
settings.viewportMode = GeckoSessionSettings.VIEWPORT_MODE_DESKTOP
session.reload()   // recommended; reload requirement is unverified in the docs, see below
// Back to mobile: USER_AGENT_MODE_MOBILE + VIEWPORT_MODE_MOBILE + reload()
// Raw string override instead of a mode: settings.userAgentOverride = "<full UA>"
//   (null restores mode-derived UA)
```

- **Set both mode and viewport.** Bug 1803740: setting only
  `VIEWPORT_MODE_DESKTOP` still redirects to the mobile site (`m.…`), because
  the default user-agent mode is mobile — desktop needs `USER_AGENT_MODE_DESKTOP`
  at the same time.
- The setters exist on `GeckoSessionSettings` itself (not builder-only), and
  `GeckoSession.getSettings()` returns the live object, so the toggle can be
  applied to an open session. Whether the change takes effect without a reload
  is **not stated in the docs** — Fenix reloads after toggling; do the same
  until verified on-device.
- **Since:** long-standing — **≤ 85, exact version not pinned** (present in the
  archived 85 Javadoc).

Links: [GeckoSessionSettings](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSessionSettings.html) ·
[bug 1803740](https://bugzilla.mozilla.org/show_bug.cgi?id=1803740)

---

## 6. Long-press / context menu — YES, `ContentDelegate.onContextMenu`

```kotlin
// GeckoSession.ContentDelegate — @UiThread, default method; set via session.setContentDelegate(...)
default void onContextMenu(
    @NonNull GeckoSession session,
    int screenX,
    int screenY,
    @NonNull ContextElement element)

// GeckoSession.ContentDelegate.ContextElement — all public final fields
public final @Nullable String baseUri;    // base URI of the element's document
public final @Nullable String linkUri;    // absolute link URI (href); null if not a link
public final @Nullable String title;      // title text
public final @Nullable String altText;    // alt text
public final int type;                    // TYPE_NONE / TYPE_IMAGE / TYPE_VIDEO / TYPE_AUDIO
public final @Nullable String srcUri;     // source URI (src) of the element
public final @Nullable String linkText;   // link text content (added v148)
```

- "A user has initiated the context menu via long-press. This event is fired
  on links, (nested) images and (nested) media elements."
- **Link detection is `element.linkUri != null`** (absolute href). There is no
  `TYPE_LINK` constant in the current API — `type` only distinguishes
  image / video / audio / none; a link wrapping an image has both `linkUri`
  and `srcUri` set. (The old `ELEMENT_TYPE_*` form was replaced by
  `ContextElement`.)
- Use `screenX`/`screenY` to anchor the menu; `srcUri` for "save/open image",
  `linkUri` for "open/copy link".
- **Since:** v**56** — bug 1381983, "Add context menu/long-press handler to
  GeckoView's content delegate" (mozilla 56). `linkText` added v**148**
  (changelog).

Links: [ContentDelegate](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ContentDelegate.html) ·
[ContextElement](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.ContentDelegate.ContextElement.html) ·
[bug 1381983](https://bugzilla.mozilla.org/show_bug.cgi?id=1381983)

---

## 7. Tracking protection (ETP) at runtime — YES, changeable after runtime creation

ETP is **not** fixed at `GeckoRuntime` creation. The level lives on the
mutable `ContentBlocking.Settings` object held by the runtime's settings:

```kotlin
// GeckoRuntimeSettings (live settings object)
public ContentBlocking.Settings getContentBlocking()   // Kotlin: runtime.settings.contentBlocking
// Builder form (at runtime creation):
GeckoRuntimeSettings.Builder.contentBlocking(ContentBlocking.Settings cb)

// ContentBlocking.Settings — setters return `this` (the live Settings instance)
@NonNull public ContentBlocking.Settings setEnhancedTrackingProtectionLevel(int level)
public int getEnhancedTrackingProtectionLevel()
@NonNull public ContentBlocking.Settings setEnhancedTrackingProtectionCategory(int category)
@NonNull public ContentBlocking.Settings setCookieBehavior(int behavior)
@NonNull public ContentBlocking.Settings setAntiTracking(int cat)

// Levels — ContentBlocking.EtpLevel
NONE     // Do not enable ETP at all.
DEFAULT  // Enable ETP for ads, analytic, and social tracking lists.
STRICT   // Enable ETP for all of the default lists as well as the content list.
```

```kotlin
runtime.settings.contentBlocking
    .setEnhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
// or .EtpLevel.DEFAULT / .EtpLevel.NONE
```

- **Level vs category** (Javadoc): "Level refers to whether content blocking
  should use the regular or the strict list, or should be disabled entirely.
  While 'category' refers to the preset of anti-tracking features under the
  ETP umbrella that should be enabled." The Phase 1 spike's "ETP Strict" is
  the **level**; the category is a separate knob.
- **Caveat — level only takes effect if** the cookie behavior is
  `ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS` or
  `ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS`. If the cookie behavior is e.g.
  `ACCEPT_ALL`, changing the level is a no-op — check
  `getCookieBehavior()` when wiring the toggle.
- **Immediate effect — UNVERIFIED:** the docs do not state whether a level
  change applies to already-loaded pages without a reload. The setters are on
  the live object (not builder-only), which is the documented pattern for
  runtime changes, but verify on-device; reloading the tab after toggling is
  the safe default.
- Do not use the legacy `GeckoSessionSettings.setUseTrackingProtection(boolean)`
  for this — it is a per-session on/off flag, not the ETP level. Note also
  `ContentBlockingController` is deprecated (v121): use `StorageController`,
  and per-site exceptions surface as a `PERMISSION_TRACKING` entry in the
  `onLocationChange` permissions list.
- **Since:** `setEnhancedTrackingProtectionLevel` v**71** (bug 1580854).

Links: [ContentBlocking.Settings](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/ContentBlocking.Settings.html) ·
[ContentBlocking.EtpLevel](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/ContentBlocking.EtpLevel.html) ·
[GeckoRuntimeSettings#getContentBlocking](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoRuntimeSettings.html)

---

## 8. `evaluateJavascript` — DOES NOT EXIST

**There is no `evaluateJavascript` (and no `evaluateJS`) on `GeckoSession`
in any GeckoView version up to mozilla-central 162.** Verified: the string
"evaluate" does not occur anywhere in the `GeckoSession` Javadoc, and
Mozilla's own contributor documentation states it outright:

> "As you might have noticed from an earlier section, the test rule allows
> tests to run arbitrary JavaScript code using `waitForJS`. **The GeckoView
> API, however, doesn't offer such an API.** … **The only supported way of
> accessing a web page for embedders is to write a built-in WebExtension and
> install it.** … GeckoView extends the WebExtension API to allow embedders
> to communicate to the extension by overloading the native messaging API
> (which is not normally implemented on mobile). Embedders can register
> themselves as a 'native app' and the built-in extension will be able to
> exchange messages and open ports with the embedder. … we have discussed
> internally the possibility to expose a simpler API to run one-off javascript
> snippets, similar to what Chromium's WebView offers, **but nothing has been
> developed so far**."
> — Firefox Source Docs, "JUnit Test Framework / How embedders run javascript"

- The `evaluateJS` / `waitForJS` seen in GeckoView tests belongs to the
  **test harness only** (`GeckoSessionTestRule`): it installs a support
  extension that `eval`s the code and returns the JSON-stringified result.
  It is not a shippable API.
- **Consequence for Phase 2:** every feature that needs to run JS in a page
  (per-site text zoom via CSS — see §2, reader-mode extraction, media
  probing, form fill) must go through a **built-in WebExtension with a
  content script + native-messaging port** back to the app. The Phase 1 spike
  already uses this pattern (`pioneer-probe` extension, `PROBE_APP =
  "pioneer"`). There is no supported `javascript:`-URL shortcut — do not
  build one on `loadUri("javascript:…")`.
- **Since:** never existed (as of mozilla-central 162).

Links: [Firefox Source Docs — JUnit Test Framework, "How embedders run javascript"](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/junit.html)

---

## 9. `onNewSession` (target=_blank) and committed top-level navigation vs subframes

```kotlin
// GeckoSession.NavigationDelegate — @UiThread, default methods
@Nullable default GeckoResult<GeckoSession> onNewSession(
    @NonNull GeckoSession session,
    @NonNull String uri)

default GeckoResult<AllowOrDeny> onLoadRequest(
    @NonNull GeckoSession session,
    @NonNull NavigationDelegate.LoadRequest request)

default GeckoResult<AllowOrDeny> onSubframeLoadRequest(
    @NonNull GeckoSession session,
    @NonNull NavigationDelegate.LoadRequest request)

default void onLocationChange(
    @NonNull GeckoSession session,
    @Nullable String url,
    @NonNull List<GeckoSession.PermissionDelegate.ContentPermission> perms,
    @NonNull Boolean hasUserGesture)

// LoadRequest fields (public final)
public final String uri;            // URI to be loaded
public final String triggerUri;     // URI of the origin page that triggered the load
public final int target;            // TARGET_WINDOW_NONE / TARGET_WINDOW_CURRENT / TARGET_WINDOW_NEW
public final boolean isRedirect;    // triggered by an HTTP redirect
public final boolean hasUserGesture;
public final boolean isDirectNavigation;  // initiated by a direct navigation from the app
```

- **`onNewSession` — target=_blank / `window.open()`.** "A request has been
  made to open a new session. The URI is provided only for informational
  purposes. **Do not call `GeckoSession.load` here.** Additionally, the
  returned `GeckoSession` must be a newly-created one." Return
  `GeckoResult.fromValue(newSession)` (opened on the runtime, delegates
  attached, and kept referenced by the tab list) — or return `null`, in which
  case "the request for a new window by web content will fail. e.g.,
  `window.open()` will return null. The implementation of `onNewSession` is
  responsible for maintaining a reference to the returned object, to prevent
  it from being garbage collected." `onLoadRequest`'s docs add that
  `TARGET_WINDOW_NEW` requests default to requesting a new session via
  `onNewSession`, so overriding `onLoadRequest` and returning `DENY` for
  `target == TARGET_WINDOW_NEW` would suppress that path.
- **Committed top-level navigation = `onLocationChange`.** Architecture doc:
  "`onLocationChange` is called whenever Gecko commits to a navigation and the
  URL can safely [be] displayed in the URL bar." That is the URL-bar signal.
  It fires for top-level commits only — subframe loads never produce it.
- **Telling top-level from subframes:**
  - `onLoadRequest` — "This is called before each **top-level** page load"
    (pre-commit; return `DENY` to abandon, `ALLOW`/null to continue).
  - `onSubframeLoadRequest` — "A request to load a URI in a **non-top-level**
    context." Same `LoadRequest` shape and same `AllowOrDeny` return.
    **Do not use `onLoadRequest` to observe subframes** — they arrive here.
  - `ProgressDelegate.onPageStart(url)` / `onPageStop(session, success)`
    bracket the top-level load.
  - Practical rule: URL bar ← `onLocationChange` only; interception of
    navigations ← `onLoadRequest` (top-level) + `onSubframeLoadRequest`
    (everything else); `LoadRequest.target` tells you whether the top-level
    load wants a new window.
- **Since:** `onNewSession` / `onLoadRequest` / `onLocationChange` are
  long-standing (**≤ 85**). `onSubframeLoadRequest` v**78** (bug 1622500,
  "to allow intercepting non-top-level navigations"). The current 4-argument
  `onLocationChange` (adds `perms` + `hasUserGesture`) dates from v**125**
  (`hasUserGesture`); the old 2-argument form was deprecated in v102 and
  removed in v128 — use the 4-argument form.

Links: [NavigationDelegate](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.NavigationDelegate.html) ·
[LoadRequest](https://mozilla.github.io/geckoview/javadoc/mozilla-central/org/mozilla/geckoview/GeckoSession.NavigationDelegate.LoadRequest.html) ·
[GeckoView Architecture (delegate call order)](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/contributor/geckoview-architecture.html)

---

## Phase 2 implications (design consequences)

| Need | Verdict |
|---|---|
| Find in page with "n of N" | `SessionFinder` works; **total may be `-1`** — design the bar to show "n" alone until a total is known. |
| Per-site text zoom | **No API.** Runtime-global `fontSizeFactor` only, or per-site CSS via the probe-style extension (§2 + §8). Decide before building the zoom UI. |
| Tab restore | `onSessionStateChange` + `flushSessionState()` (v144) + `restoreState()`; persist `SessionState.toString()`; **call `PioneerStore.flush()` from `MainActivity.onPause()`** or a killed process loses the last state (§3). |
| Private tabs | New `GeckoSession` per tab with `usePrivateMode(true)`; cannot convert an existing tab (§4). |
| Desktop site toggle | `userAgentMode = DESKTOP` **and** `viewportMode = DESKTOP`, then reload (§5). |
| Long-press menu | `onContextMenu` + `element.linkUri` / `srcUri` (§6). |
| ETP Standard/Strict/Off toggle | `runtime.settings.contentBlocking.setEnhancedTrackingProtectionLevel(...)`; check cookie behavior first; reload tab after toggling until verified (§7). |
| Any JS evaluation | **Does not exist.** All JS goes through a built-in WebExtension + native-messaging port (§8). |
| New tabs from links | `onNewSession` returning a fresh, referenced `GeckoSession` (§9). |
| URL bar updates | `onLocationChange` only; subframes arrive via `onSubframeLoadRequest`, never as location changes (§9). |

## Items NOT fully verified — on-device checks for the Phase 2 builder

1. `flushSessionState()` return type (not legible in the extracted Javadoc text;
   changelog confirms the method exists since v144).
2. Whether `setFontSizeFactor` changes apply without a session reload in 159
   (v85 docs said a reload was required; v162 docs no longer say so).
3. Whether an ETP level change applies to already-loaded pages without a reload.
4. Whether a user-agent/viewport mode change applies without a reload.
5. `SessionState` restore fidelity across a real process kill (history + scroll
   + form data round-trip via `toString()`/`fromString()`).
