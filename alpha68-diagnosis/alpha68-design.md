# alpha.68 design — fix the m3u8 download 404

**Device evidence:** Robert's Downloads row shows a `.ts` record with HTTP 404
("Ts 404"). The m3u8 plays (browser session / ExoPlayer) but the download fails.

## Which fetch 404s, and why

From code, three fetches produce an identical "HTTP 404" label, so the device
evidence alone cannot separate them — the fix covers all three and names the
stage in the error label so the next failure is immediately diagnosable:

1. `runHlsJob`'s manifest fetch (`fetchText(rec.url)`).
2. `downloadHlsMedia`'s media-playlist re-fetch (`fetchText(mediaUrl)`).
3. A segment fetch (`checkedBytes(openGet(seg.url ...))`, retried once).

The structural cause, verified in code: the same manifest URL is fetched up to
**five times** before one segment downloads — sheet-open resolve (alpha.58) →
tap-time variant fetch → dialog re-fetch (`LaunchedEffect(pendingDownload)`) →
`runHlsJob` manifest fetch → `downloadHlsMedia` media re-fetch. For a signed
URL with a short TTL or a single-use token, every pre-fetch burns lifetime and
the job's fetch is the one that finds the URL dead. Playback fetches once,
early, through the browser session — and works.

The "full session context" gap is NOT the story: the alpha.64 cookie wiring
(`JobCookieJar`) is already in every fetch on this path and is proven working
on device (both cookie experiments accepted). The fix is therefore about the
URL being valid when fetched, not about adding headers.

## The fix (all HLS-only, general across sites)

- **F1 — fetch once at tap time, hand the text to the job.** `beginQualityDownload`
  fetches the manifest text once (it already fetched it for variants and threw
  it away). The text rides `PendingDownload` → `Downloads.start` → a
  `manifest.txt` file in the job staging dir (same lifecycle as the segment
  journal: persists across retries/resume, deleted on success). `runHlsJob`
  uses it instead of re-fetching `rec.url`; `downloadHlsMedia` uses it instead
  of re-fetching the media playlist when the media URL is the record URL. A
  missing/blank file falls back to fetching fresh — strictly additive.
  Master case: the tap-time *master* text picks the variant; the variant media
  playlist is still fetched fresh at job time (desirable). Merge jobs and DASH
  are untouched (no text is passed for them).
- **F2 — segment 403/404 refreshes the playlist once and remaps by index.**
  A 4xx is not transient, so retrying the same dead URL is pointless. On a
  segment 403/404, `downloadHlsMedia` re-fetches the media playlist once,
  and only when the original playlist is VOD (`#EXT-X-ENDLIST` present) with
  an unchanged segment count and the same duration at that index does it retry
  with the refreshed segment URL (and its refreshed key). Anything else fails
  with the original error. One refresh per media download, so a systematically
  dead playlist fails fast.
- **F3 — stage-named error labels.** Manifest / media-playlist / segment
  failures are tagged ("Manifest: HTTP 404", "Segment 12: HTTP 404") so the
  Downloads row names the failing fetch. `HttpStatusException(code)` carries
  the status through `checkStatus` (same message, same `IOException`
  supertype — every existing catch and `errorLabel` rendering is unchanged).
- **F4 — fail fast at tap time on HTTP 404 only.** `fetchDownloadVariants`
  swallows every failure into an empty list. The new `fetchManifestForDownload`
  also reports `deadLink` (404 only — a 403 stays ambiguous, timeouts are
  transient). On a dead link, `beginQualityDownload` shows a plain toast
  ("This video link no longer works — reload the page and try again") and
  starts no job, instead of creating a doomed one that fails invisibly.

## Commits (small, each CI-green)

1. Pure helpers + tests (`HttpStatusException`, `stageFetchError`,
   `isDeadLinkError`, `remappedSegment`; `parseHlsMedia` + `HlsSegment` /
   `HlsMedia` made `internal` for testability). No behaviour change.
2. F1 — tap-time text handoff.
3. F2 — segment refresh-remap.
4. F4 — tap-time 404 fail-fast (F3 labels land inside commits 2–3 at the
   sites they touch).
5. Version bump to 68 (0.1.0-alpha.68) after all green.

## Not in scope

InnerTube / any YouTube path, the Error 153 Open action, coverage-check items,
DASH job paths, the sheet-open resolve (alpha.58) behaviour, the fMP4
`#EXT-X-MAP` gap (separate proven defect — a complete-but-unplayable `.ts`,
not a 404). Cookie values stay memory-only; nothing new is persisted except
the manifest *text* (no credentials in it — the text is the playlist body).
