package net.nspioneer.browser.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The licences screen: what Pioneer is built from and under which
 * terms. Plain scrollable text — nothing here is an action, so
 * nothing here can be a dead row.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    SubScreen("Licences", onBack) {
        Column(
            modifier = Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            GroupTitle("Notscape Pioneer")
            SettingsCard {
                Text(
                    "Notscape Pioneer is free software, released under the " +
                        "GNU General Public License version 3 (GPL-3.0). You " +
                        "may redistribute it and modify it under the terms " +
                        "of that licence. The full licence text ships with " +
                        "the app's source repository (LICENSE).",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            GroupTitle("GeckoView")
            SettingsCard {
                Text(
                    "The web engine is Mozilla GeckoView, used under the " +
                        "Mozilla Public License 2.0 (MPL 2.0). GeckoView " +
                        "and its source are available from Mozilla. The " +
                        "engine is a separate work from Pioneer itself: " +
                        "Pioneer is GPL-3.0, GeckoView is MPL 2.0.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            GroupTitle("Bundled libraries")
            SettingsCard {
                Text(
                    "AndroidX (core, activity, lifecycle) — Apache License 2.0\n" +
                        "Jetpack Compose (UI, Material 3, foundation) — Apache License 2.0\n" +
                        "Google Play Services Cast framework — Google Terms of Service\n" +
                        "AndroidX MediaRouter — Apache License 2.0\n" +
                        "uBlock Origin (optional extension, installed from Mozilla " +
                        "Add-ons) — GNU General Public License version 3",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
