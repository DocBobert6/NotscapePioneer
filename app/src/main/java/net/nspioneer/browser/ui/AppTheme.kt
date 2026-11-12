package net.nspioneer.browser.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.nspioneer.browser.data.PioneerStore

/**
 * Mariner's theme, carried to Pioneer so Pioneer looks like
 * Mariner: the iOS-26 semantic colours, the SF type scale, the
 * concentric-radius shape ladder, the solid-frost recipe.
 * Adapted: Pioneer ships no bundled brand fonts (no res/font
 * in this app), so the wordmark faces are the platform default;
 * the interface-font setting does not exist in Phase 2, so the
 * UI font is always the platform default.
 */
val WordmarkFontFamily: FontFamily = FontFamily.Default
val SmallCapsFontFamily: FontFamily = FontFamily.Default

val IosInk = Color(0xFF1C1C1E)
val IosInkDark = Color(0xFFF2F2F7)
val IosRed = Color(0xFFFF3B30)
val IosRedDark = Color(0xFFFF453A)

fun notscapeLightColors(): ColorScheme = lightColorScheme(
    primary = IosInk,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E5EA),
    onPrimaryContainer = Color(0xFF1C1C1E),
    secondary = IosInk,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2F2F7),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = IosInk,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE5E5EA),
    onTertiaryContainer = Color(0xFF1C1C1E),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF3C3C43),
    surfaceDim = Color(0xFFE5E5EA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F2F7),
    surfaceContainer = Color(0xFFF2F2F7),
    surfaceContainerHigh = Color(0xFFE9E9EE),
    surfaceContainerHighest = Color(0xFFE5E5EA),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFE5E5EA),
    error = IosRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFE9E7),
    onErrorContainer = Color(0xFF7A1008),
    inverseSurface = Color(0xFF1C1C1E),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = Color(0xFFF2F2F7),
    scrim = Color.Black
)

fun notscapeDarkColors(): ColorScheme = darkColorScheme(
    primary = IosInkDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A3A3C),
    onPrimaryContainer = Color(0xFFF2F2F7),
    secondary = IosInkDark,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFF2F2F7),
    tertiary = IosInkDark,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF3A3A3C),
    onTertiaryContainer = Color(0xFFF2F2F7),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF98989F),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF2C2C2E),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF1C1C1E),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF3A3A3C),
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF48484A),
    error = IosRedDark,
    onError = Color.White,
    errorContainer = Color(0xFF4A1712),
    onErrorContainer = Color(0xFFFFDAD4),
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1C1C1E),
    inversePrimary = Color(0xFF1C1C1E),
    scrim = Color.Black
)

/** SF type scale (Dynamic Type) mapped to Material slots. */
private fun notscapeTypography(
    family: FontFamily? = null,
    scale: Float = 1f
): Typography {
    val fam = family ?: FontFamily.Default
    fun ts(size: Float, line: Float, weight: FontWeight? = null) =
        TextStyle(
            fontFamily = fam,
            fontSize = (size * scale).sp,
            lineHeight = (line * scale).sp,
            fontWeight = weight
        )
    return Typography(
        displayLarge = ts(34f, 41f, FontWeight.Bold),
        headlineLarge = ts(28f, 34f, FontWeight.Bold),
        headlineMedium = ts(22f, 28f, FontWeight.Bold),
        headlineSmall = ts(20f, 25f, FontWeight.SemiBold),
        titleLarge = ts(20f, 25f, FontWeight.SemiBold),
        titleMedium = ts(17f, 22f, FontWeight.SemiBold),
        titleSmall = ts(15f, 20f, FontWeight.SemiBold),
        bodyLarge = ts(17f, 22f),
        bodyMedium = ts(15f, 20f),
        bodySmall = ts(13f, 18f),
        labelLarge = ts(15f, 20f, FontWeight.Medium),
        labelMedium = ts(12f, 16f, FontWeight.Medium),
        labelSmall = ts(11f, 13f, FontWeight.Medium)
    )
}

/** Concentric-radius ladder: sections 20, sheets 28, small controls 12. */
private fun notscapeShapes(): Shapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
)

@Composable
fun NotscapeTheme(store: PioneerStore, content: @Composable () -> Unit) {
    val rev = store.revision
    val mode = store.setting("themeMode", "system")
    // Material You gained manual variants in Mariner: materialyou-light /
    // materialyou-dark pin the dynamic palette instead of following the
    // system. Pioneer keeps the same keys so a carried store reads the same.
    val dark = when (mode) {
        "dark", "materialyou-dark" -> true
        "light", "materialyou-light" -> false
        else -> isSystemInDarkTheme()
    }
    // Material You: only the COLOURS come from the phone's dynamic
    // palette — shapes, type and the frost recipe are untouched.
    // Android 12+; older devices fall back to the standard schemes.
    val context = LocalContext.current
    val scheme = when {
        mode.startsWith("materialyou") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> notscapeDarkColors()
        else -> notscapeLightColors()
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = notscapeTypography(),
        shapes = notscapeShapes()
    ) {
        // Material 3 does not seed LocalContentColor from the
        // colour scheme, so bare text and icons outside a Surface
        // fall back to its black default - invisible on dark
        // backgrounds. Seed it from the scheme so uncoloured
        // content always matches the app's own background.
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            content = content
        )
    }
}

/**
 * The shared screen header: a back button, a large title, optional
 * trailing actions, and a hairline below. Used by every sub-screen
 * so navigation looks and feels identical everywhere.
 */
@Composable
fun AppHeader(
    title: String,
    onBack: () -> Unit,
    contentColor: Color = Color.Unspecified,
    dividerColor: Color = Color.Unspecified,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val tint = if (contentColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else contentColor
    Column {
        CompositionLocalProvider(LocalContentColor provides tint) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                actions()
            }
        }
        HorizontalDivider(
            color = if (dividerColor == Color.Unspecified) {
                Frost.hairline()
            } else dividerColor
        )
    }
}
