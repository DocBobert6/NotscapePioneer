package net.nspioneer.browser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card

import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The solid-frost design kit, carried from Mariner (Phase C) so
 * Pioneer looks like Mariner. Same recipe: floating bars and
 * panels wear a near-solid vertical gradient, a hairline and a
 * real shadow — no blur anywhere. Sheets are solid
 * surfaceContainerLow with 28dp top corners. Radii follow the
 * theme's concentric ladder: cards 20, sheets 28, pills full.
 */
object Frost {
    val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val CardShape = RoundedCornerShape(20.dp)
    val PillShape = RoundedCornerShape(50)
    val DialogShape = RoundedCornerShape(28.dp)

    /** The floating-bar gradient: near-solid, lighter at the top. */
    @Composable
    fun brush(): Brush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
        )
    )

    @Composable
    fun hairline(): Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)

    /** The solid sheet colour — never an alpha colour. */
    @Composable
    fun sheetColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

    /** The grouped-card colour used on sheets and grouped lists. */
    @Composable
    fun cardColor(): Color = MaterialTheme.colorScheme.surfaceBright
}

/**
 * The floating frost material as a modifier: shadow, clip, gradient,
 * hairline — in that order.
 */
@Composable
fun Modifier.frostBar(shape: Shape = Frost.PillShape, elevation: Dp = 6.dp): Modifier =
    this.shadow(elevation, shape)
        .clip(shape)
        .background(Frost.brush())
        .border(0.5.dp, Frost.hairline(), shape)

/** A grouped card in the kit's card material and radius. */
@Composable
fun GroupedCard(
    modifier: Modifier = Modifier,
    shape: Shape = Frost.CardShape,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Frost.cardColor()),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/**
 * A panel card in the kit's panel material: the tinted container
 * colour at the card radius, for cards sitting directly on the
 * plain page background — one step off the backdrop.
 */
@Composable
fun PanelCard(
    modifier: Modifier = Modifier,
    shape: Shape = Frost.CardShape,
    containerColor: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = if (containerColor == Color.Unspecified) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            )
        } else {
            CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColorFor(containerColor)
            )
        },
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/** A bottom sheet in the kit's sheet material and shape. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrostSheet(
    onDismiss: () -> Unit,
    draggable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = draggable,
        containerColor = Frost.sheetColor(),
        shape = Frost.SheetShape,
        content = content
    )
}

/** A dialog in the kit's material: the sheets' 28dp shape and
 * sheet colour, so dialogs, sheets and cards all read as one
 * family. Same slots as AlertDialog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FrostDialog(
    onDismissRequest: () -> Unit,
    title: @Composable (() -> Unit)? = null,
    text: @Composable (() -> Unit)? = null,
    icon: @Composable (() -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    properties: DialogProperties = DialogProperties()
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = title,
        text = text,
        icon = icon,
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        properties = properties,
        shape = Frost.DialogShape,
        containerColor = Frost.sheetColor()
    )
}
