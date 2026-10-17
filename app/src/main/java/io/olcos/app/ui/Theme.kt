package io.olcos.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.olcos.app.R

/**
 * The iOS app's design tokens (App/UI/Theme.swift), one set per appearance:
 * the same hex values, the same names, so a screen reads the same on both
 * phones. Three families, each with a job: ACCENT (blue → indigo: links,
 * tints, the selected chip, the primary action), SIGNAL (cyan → blue →
 * violet: the picture of the signal, never a button fill) and STATUS (the one
 * vocabulary: unknown grey, progress amber, ok green, warn orange, error red),
 * plus a calm coral for stop and remove.
 */
@Immutable
data class OlcosPalette(
    val isDark: Boolean,
    /** The screen's ground: graphite in Dark, the grouped grey in Light. */
    val background: Color,
    val card: Color,
    /** The layer that says "this is tappable": secondary buttons, chips, the segmented track. */
    val fill: Color,
    /** The hairline that gives a `fill` plate an edge. */
    val fillBorder: Color,
    /** A card's hairline: an edge, not a stroke. */
    val cardBorder: Color,
    val separator: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Links, tints, toggles; the colour of a secondary button's label. */
    val accentTint: Color,
    /** The solid plate under white text: the selected chip, the active segment. */
    val accentFill: Color,
    val onAccent: Color,
    val actionStart: Color,
    val actionEnd: Color,
    val green: Color,
    val orange: Color,
    val red: Color,
    val amber: Color,
    /** Stop, disconnect, remove: serious, not an alarm. */
    val destructive: Color,
    val destructiveFill: Color,
    val star: Color,
    val starWeak: Color,
    val signalCyan: Color,
    val signalMid: Color,
    val signalViolet: Color,
) {
    /** The primary action's plate: blue → indigo → violet-indigo, white legible at every stop. */
    val actionGradient: Brush get() = Brush.horizontalGradient(listOf(actionStart, accentFill, actionEnd))

    /** The signal's own sweep, for the picture and the verdict mark only. */
    val signalGradient: Brush get() = Brush.horizontalGradient(listOf(signalCyan, signalMid, signalViolet))

    /** A destructive control's wash. */
    val destructiveWeak: Color get() = destructive.copy(alpha = 0.16f)
}

val DarkPalette = OlcosPalette(
    isDark = true,
    background = Color(0xFF0B0C0F),
    card = Color(0xFF17191D),
    fill = Color.White.copy(alpha = 0.10f),
    fillBorder = Color.White.copy(alpha = 0.14f),
    cardBorder = Color.White.copy(alpha = 0.10f),
    separator = Color(0x99545458),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0x99EBEBF5),
    textTertiary = Color(0x4DEBEBF5),
    accentTint = Color(0xFF6E9BFF),
    accentFill = Color(0xFF4F5BD5),
    onAccent = Color(0xFFFFFFFF),
    actionStart = Color(0xFF3A57D9),
    actionEnd = Color(0xFF5F55DC),
    green = Color(0xFF30D158),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF5F57),
    amber = Color(0xFFFFD60A),
    destructive = Color(0xFFE8776F),
    destructiveFill = Color(0xFFB8433D),
    star = Color(0xFFFFD60A),
    starWeak = Color(0xFFFFD60A).copy(alpha = 0.16f),
    signalCyan = Color(0xFF36D8F5),
    signalMid = Color(0xFF5EAEFF),
    signalViolet = Color(0xFF8B7BFF),
)

val LightPalette = OlcosPalette(
    isDark = false,
    background = Color(0xFFF2F2F7),
    card = Color(0xFFFFFFFF),
    fill = Color.Black.copy(alpha = 0.05f),
    fillBorder = Color.Black.copy(alpha = 0.14f),
    cardBorder = Color.Black.copy(alpha = 0.08f),
    separator = Color(0x4A3C3C43),
    textPrimary = Color(0xFF000000),
    textSecondary = Color(0x993C3C43),
    textTertiary = Color(0x4D3C3C43),
    accentTint = Color(0xFF3B4BC8),
    accentFill = Color(0xFF3B4BC8),
    onAccent = Color(0xFFFFFFFF),
    actionStart = Color(0xFF2F55D4),
    actionEnd = Color(0xFF4B3FB8),
    green = Color(0xFF1B7A34),
    orange = Color(0xFF9A5B00),
    red = Color(0xFFC0302A),
    amber = Color(0xFF9A6A00),
    destructive = Color(0xFFB8433D),
    destructiveFill = Color(0xFFA83C36),
    star = Color(0xFF8A6100),
    starWeak = Color(0xFF8A6100).copy(alpha = 0.12f),
    signalCyan = Color(0xFF0B7A99),
    signalMid = Color(0xFF2A62C9),
    signalViolet = Color(0xFF5B4BD6),
)

val LocalOlcosPalette = staticCompositionLocalOf { DarkPalette }

/** Spacing and shape, the iOS `Theme.Metrics`: one grid, nothing off it. */
object Metrics {
    val controlHeight = 44.dp
    val controlRadius = 12.dp
    val cardRadius = 20.dp
    val cardPadding = 16.dp
    val cardBorderWidth = 1.dp
    val rowMinHeight = 52.dp
    val segmentedRadius = 10.dp
    val chipHeight = 34.dp
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s7 = 32.dp
    val s8 = 40.dp
}

/**
 * The rounded face the iOS app sets everything in (SF Rounded there; Nunito,
 * its closest open twin, here): one variable font, every weight from it.
 */
@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(
    R.font.nunito, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Rounded = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800))

/**
 * The iOS type scale: SIX steps and nothing else. answer (34, bold), title
 * (20, semibold), body (17), label (15, semibold), caption (15) and mono (13).
 * Nothing is smaller than 15 except addresses and log lines.
 */
object Type {
    val answer = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp)
    val title = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp)
    val answerSupport = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 25.sp)
    val body = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp)
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)
    val label = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp)
    val caption = TextStyle(fontFamily = Rounded, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
    val captionStrong = caption.copy(fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
    val metricValue = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    /** Digits that line up while they change (a timer, a rate). */
    val captionDigits = caption.copy(fontFeatureSettings = "tnum")
}

object OlcosTheme {
    val palette: OlcosPalette
        @Composable get() = LocalOlcosPalette.current

    /** Whether the dark set is in use: for drawing that has its own light and dark colours. */
    val isDark: Boolean
        @Composable get() = LocalOlcosPalette.current.isDark
}

/** Material's own controls (fields, dialogs, menus, switches) dressed in the same type and colours. */
private fun materialTypography(): Typography {
    val base = Typography()
    return Typography(
        displayLarge = base.displayLarge.copy(fontFamily = Rounded),
        displayMedium = base.displayMedium.copy(fontFamily = Rounded),
        displaySmall = base.displaySmall.copy(fontFamily = Rounded),
        headlineLarge = Type.answer,
        headlineMedium = Type.answer.copy(fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = Type.title.copy(fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = Type.title,
        titleMedium = Type.bodyStrong,
        titleSmall = Type.label,
        bodyLarge = Type.body,
        bodyMedium = Type.caption,
        bodySmall = Type.caption,
        labelLarge = Type.label,
        labelMedium = Type.label,
        labelSmall = Type.captionStrong.copy(fontSize = 13.sp, lineHeight = 18.sp),
    )
}

private val materialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(Metrics.controlRadius),
    medium = RoundedCornerShape(Metrics.controlRadius),
    large = RoundedCornerShape(Metrics.cardRadius),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun OlcosTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accentTint, onPrimary = p.onAccent,
            primaryContainer = p.accentFill, onPrimaryContainer = p.onAccent,
            secondary = p.accentTint, onSecondary = p.onAccent,
            secondaryContainer = p.fill, onSecondaryContainer = p.textPrimary,
            background = p.background, onBackground = p.textPrimary,
            surface = p.background, onSurface = p.textPrimary,
            surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.card,
            surfaceContainerLow = p.card, surfaceContainerLowest = p.background, surfaceVariant = p.card,
            onSurfaceVariant = p.textSecondary, outline = p.fillBorder, outlineVariant = p.separator,
            error = p.red, onError = Color.White,
        )
    } else {
        lightColorScheme(
            primary = p.accentTint, onPrimary = p.onAccent,
            primaryContainer = p.accentFill, onPrimaryContainer = p.onAccent,
            secondary = p.accentTint, onSecondary = p.onAccent,
            secondaryContainer = p.fill, onSecondaryContainer = p.textPrimary,
            background = p.background, onBackground = p.textPrimary,
            surface = p.background, onSurface = p.textPrimary,
            surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.card,
            surfaceContainerLow = p.card, surfaceContainerLowest = p.background, surfaceVariant = p.card,
            onSurfaceVariant = p.textSecondary, outline = p.fillBorder, outlineVariant = p.separator,
            error = p.red, onError = Color.White,
        )
    }
    CompositionLocalProvider(LocalOlcosPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = materialTypography(), shapes = materialShapes, content = content)
    }
}
