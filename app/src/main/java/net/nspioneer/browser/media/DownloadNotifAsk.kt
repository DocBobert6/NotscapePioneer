package net.nspioneer.browser.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import net.nspioneer.browser.data.PioneerStore

/**
 * Port of Mariner's one-time POST_NOTIFICATIONS ask
 * (media/DownloadNotifAsk.kt, 2026-10-26): worker downloads
 * post their "Download complete" notice as an ordinary
 * notification, which Android 13+ silently drops when
 * POST_NOTIFICATIONS is not granted — while the in-progress
 * notice, being a foreground-service notification, still
 * shows. So a user who was never asked sees progress but
 * never completion. The ask fires at most once from the
 * download path: the flag is set when the dialog is launched,
 * whether the user grants or denies, and a denial is never
 * re-asked. The Downloads page carries a recovery affordance
 * for users who denied (system app-notification settings).
 */
const val DOWNLOAD_NOTIF_ASKED_KEY = "downloadNotifAsked"

/** True when a plain (non-foreground) download notice can
 * reach the notification shade. Before Android 13 there
 * is no runtime notification permission, so this is true. */
fun downloadNotificationsEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

/** Pure gate for the one-time download-path ask, kept
 * apart from the Android calls so it is unit-testable. */
fun shouldAskDownloadNotif(
    sdkInt: Int, granted: Boolean, alreadyAsked: Boolean
): Boolean {
    return sdkInt >= 33 && !granted && !alreadyAsked
}

/** Opens the system app-notification settings page for
 * this app, so a user who denied the permission can
 * re-enable it without hunting through Settings. */
fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

/**
 * The one-time download-path ask, as a lambda to call
 * before starting a download. Owned by the calling
 * composable (Downloads.start itself is a plain object
 * and cannot launch a permission request). Calling it
 * when the permission is already granted, on an old
 * platform, or after the one ask is a no-op.
 */
@Composable
fun rememberAskDownloadNotifPermission(store: PioneerStore): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    return {
        if (shouldAskDownloadNotif(
                Build.VERSION.SDK_INT,
                downloadNotificationsEnabled(context),
                store.setting(DOWNLOAD_NOTIF_ASKED_KEY, "false") == "true"
            )
        ) {
            store.putSetting(DOWNLOAD_NOTIF_ASKED_KEY, "true")
            launcher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
