package net.nspioneer.browser.engine

import java.net.HttpURLConnection
import java.net.URL
import net.nspioneer.browser.BuildConfig
import net.nspioneer.browser.data.PioneerStore

/** Opt-in "a new Pioneer version is available" check (alpha.43,
 *  Chrome/Firefox recommendation #3: faster, quieter patch
 *  delivery).
 *
 *  There are no GitHub Releases for this repo and no tags — the
 *  builds ship as CI run artifacts — so there is no release
 *  metadata to fetch. The check instead reads the version out
 *  of the main branch's app/build.gradle.kts through the GitHub
 *  contents API: one small metadata fetch (raw file, no auth),
 *  versionCode the single source of truth for "newer". No APK
 *  is downloaded, nothing is auto-installed, no telemetry.
 *
 *  Off by default (setting [SETTING_ENABLED]); when on, the
 *  launch check runs at most once a day and a newer version is
 *  only a status line. A manual "Check now" in Settings →
 *  About always fetches. A failed check is silent on launch
 *  (no network is not news) and shown as "Last check failed"
 *  on the manual row. */
object UpdateCheck {
    /** Raw build.gradle.kts from main, via the GitHub API. */
    const val REMOTE_BUILD_GRADLE_URL =
        "https://api.github.com/repos/DocBobert6/NotscapePioneer" +
            "/contents/app/build.gradle.kts"

    /** Where the new build lives when one is found: the repo's
     *  Actions page — the latest green run's artifact is the
     *  install, exactly as Robert already installs alphas. */
    const val ACTIONS_URL =
        "https://github.com/DocBobert6/NotscapePioneer/actions"

    /** Setting keys (PioneerStore generic settings). */
    const val SETTING_ENABLED = "checkForUpdates"
    const val SETTING_LAST = "updateCheckLatest"

    /** Launch checks run at most this often. */
    const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    data class RemoteVersion(val versionName: String, val versionCode: Int)

    sealed interface Result {
        data class UpToDate(val remote: RemoteVersion) : Result
        data class Available(val remote: RemoteVersion) : Result
        data object Failed : Result
    }

    /** Last stored check: "versionCode|versionName|checkedAtMs". */
    data class LastCheck(val remote: RemoteVersion, val checkedAtMs: Long)

    /** Pure: the version out of a build.gradle.kts text. Null
     *  when either assignment is missing or unparseable. */
    fun parseRemoteVersion(text: String): RemoteVersion? {
        val code = Regex("""versionCode\s*=\s*(\d+)""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return null
        val name = Regex("""versionName\s*=\s*"([^"]+)"""")
            .find(text)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() } ?: return null
        return RemoteVersion(name, code)
    }

    /** Pure: newer remote code than [currentCode] is an update. */
    fun evaluate(remote: RemoteVersion?, currentCode: Int): Result =
        when {
            remote == null -> Result.Failed
            remote.versionCode > currentCode -> Result.Available(remote)
            else -> Result.UpToDate(remote)
        }

    /** Blocking network call — call from Dispatchers.IO. */
    fun check(currentCode: Int = BuildConfig.VERSION_CODE): Result {
        val text = runCatching {
            val conn =
                URL(REMOTE_BUILD_GRADLE_URL).openConnection()
                    as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty(
                "User-Agent", "Mozilla/5.0 (Android) NotscapePioneer"
            )
            conn.setRequestProperty(
                "Accept", "application/vnd.github.raw"
            )
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return Result.Failed
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }.getOrNull() ?: return Result.Failed
        return evaluate(parseRemoteVersion(text), currentCode)
    }

    /** Pure: the stored last check, or null when absent/malformed. */
    fun parseLastCheck(raw: String?): LastCheck? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 3) return null
        val code = parts[0].toIntOrNull() ?: return null
        val name = parts[1].takeIf { it.isNotBlank() } ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        return LastCheck(RemoteVersion(name, code), at)
    }

    fun loadLastCheck(store: PioneerStore): LastCheck? =
        parseLastCheck(store.setting(SETTING_LAST, ""))

    fun saveLastCheck(
        store: PioneerStore,
        remote: RemoteVersion,
        atMs: Long = System.currentTimeMillis()
    ) {
        store.putSetting(
            SETTING_LAST,
            "${remote.versionCode}|${remote.versionName}|$atMs"
        )
    }

    /** Pure: true when there is no stored check or it is at
     *  least [CHECK_INTERVAL_MS] old. */
    fun isStale(rawLast: String?, nowMs: Long): Boolean {
        val last = parseLastCheck(rawLast) ?: return true
        return nowMs - last.checkedAtMs >= CHECK_INTERVAL_MS
    }
}
