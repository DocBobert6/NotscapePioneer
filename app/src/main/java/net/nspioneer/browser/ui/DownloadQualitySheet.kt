package net.nspioneer.browser.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.nspioneer.browser.media.DetectedMedia
import net.nspioneer.browser.media.Downloads

/** A request to show the download-quality sheet (alpha.46):
 *  the detected media plus the variants fetched for it.
 *  [formatLabel] names the stream kind, e.g. "HLS • 3 qualities". */
data class QualitySheetRequest(
    val media: DetectedMedia,
    val variants: List<Downloads.DownloadVariant>,
    val formatLabel: String,
)

/** Download-quality picker (alpha.46): a bottom sheet in the
 *  app's own Frost style — not a port of anyone else's sheet.
 *  One row per rendition, highest first, defaulting to the
 *  highest; a choice that downloads separate video+audio
 *  tracks says so on its row and notes the merge below the
 *  list. Download hands the chosen variant back; Cancel and
 *  back just dismiss. */
@Composable
fun DownloadQualitySheet(
    request: QualitySheetRequest,
    onDownload: (Downloads.DownloadVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(0) }
    FrostSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 28.dp)
        ) {
            GroupedCard {
                Column(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "Download quality",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                    Text(
                        request.formatLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 2.dp)
                    )
                    request.variants.forEachIndexed { i, variant ->
                        TextButton(
                            onClick = { selected = i },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                (if (selected == i) "✓ " else "") + variant.label,
                                textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    if (request.variants.getOrNull(selected)?.needsMux == true) {
                        Text(
                            "Separate video and audio tracks are merged into one MP4.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = {
                    request.variants.getOrNull(selected)?.let(onDownload)
                }) { Text("Download") }
            }
        }
    }
}
