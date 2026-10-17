package io.olcos.app.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBackIos
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The iOS app's design system (App/UI/DesignSystem.swift) in Compose: one
// button, one card, one section header, one status vocabulary, one overflow
// menu, one segmented control, one chip picker, one metric. They read only
// from the palette and Metrics in Theme.kt.

/** Whether the system's "Remove animations" is on: the iOS Reduce Motion. */
@Composable
fun reduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/**
 * Haptics belong to the few moments that mark an outcome (a connect or a
 * disconnect committed, a verified success, a failure), never to every tap:
 * when every tap buzzes, the buzz stops being feedback.
 */
object Haptics {
    fun impact(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun success(view: View) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun error(view: View) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
}

/**
 * The press every tappable surface shares: a 0.96 dip, so a button feels
 * physical to press. No ripple: the dip is the feedback, as on iOS.
 */
fun Modifier.olcPressable(enabled: Boolean = true, role: Role? = Role.Button, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "press")
    Modifier
        .scale(scale)
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

// MARK: - 1. OlcButton

enum class OlcRole { PRIMARY, SECONDARY, DANGER, GHOST }

/**
 * The ONE button: four roles, 44dp tall, one corner radius. Primary is the
 * blue → indigo plate under white; secondary a tinted wash with a hairline
 * edge; danger the calm coral; ghost just the tinted label.
 */
@Composable
fun OlcButton(
    title: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    role: OlcRole = OlcRole.SECONDARY,
    busy: Boolean = false,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    compact: Boolean = false,
) {
    val p = OlcosTheme.palette
    val shape = RoundedCornerShape(Metrics.controlRadius)
    val foreground = when (role) {
        OlcRole.PRIMARY -> p.onAccent
        OlcRole.SECONDARY, OlcRole.GHOST -> p.signalMid
        OlcRole.DANGER -> p.destructive
    }
    val height = if (compact) 32.dp else Metrics.controlHeight
    val background: Modifier = when (role) {
        OlcRole.PRIMARY -> Modifier.background(p.actionGradient, shape)
        OlcRole.SECONDARY -> Modifier.background(p.fill, shape).border(1.dp, p.fillBorder, shape)
        OlcRole.DANGER -> Modifier.background(p.destructiveWeak, shape)
        OlcRole.GHOST -> Modifier
    }
    Row(
        modifier
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = height)
            .then(if (title == null) Modifier.widthIn(min = height) else Modifier)
            .olcPressable(enabled = enabled && !busy, onClick = onClick)
            .clip(shape)
            .then(background)
            .padding(horizontal = if (title == null) 0.dp else if (compact) 12.dp else Metrics.s4, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = if (compact) Type.captionStrong else Type.label
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), color = foreground, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = title, tint = foreground, modifier = Modifier.size(if (compact) 15.dp else 19.dp))
        }
        if (title != null) {
            Text(title, style = style, color = foreground, textAlign = TextAlign.Center)
        }
    }
}

/** A screen's full-width call to action: the primary plate, or the secondary wash beside it. */
@Composable
fun ActionButton(label: String, @Suppress("UNUSED_PARAMETER") p: OlcosPalette, primary: Boolean, onClick: () -> Unit) {
    OlcButton(label, onClick, role = if (primary) OlcRole.PRIMARY else OlcRole.SECONDARY, fillWidth = true)
}

// MARK: - 2. OlcCard

/** Rounded container: the card fill, a 20dp radius, 16dp padding, a 1dp hairline; a soft lift in Light only. */
@Composable
fun OlcCard(
    modifier: Modifier = Modifier,
    padding: Dp = Metrics.cardPadding,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = OlcosTheme.palette
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.olcPressable(onClick = onClick) else Modifier)
            .then(if (p.isDark) Modifier else Modifier.shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f)))
            .clip(shape)
            .background(p.card, shape)
            .border(Metrics.cardBorderWidth, p.cardBorder, shape)
            .padding(padding),
        content = content,
    )
}

// MARK: - 3. OlcSectionHeader

/** Sentence case in the body face: one header style across the app, no shouting capitals. */
@Composable
fun OlcSectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val p = OlcosTheme.palette
    Row(
        modifier.fillMaxWidth().padding(start = Metrics.s1, end = Metrics.s1, bottom = Metrics.s2),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = Type.bodyStrong, color = p.textPrimary, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

// MARK: - 4. Status

/** The ONE status vocabulary: unknown, progress, ok, warn, error. Each owns a shape, so it survives a grayscale screen. */
enum class OlcTone { UNKNOWN, PROGRESS, OK, WARN, ERROR }

@Composable
fun OlcTone.color(): Color {
    val p = OlcosTheme.palette
    return when (this) {
        OlcTone.UNKNOWN -> p.textTertiary
        OlcTone.PROGRESS -> p.amber
        OlcTone.OK -> p.green
        OlcTone.WARN -> p.orange
        OlcTone.ERROR -> p.red
    }
}

private fun OlcTone.icon(): ImageVector = when (this) {
    OlcTone.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
    OlcTone.PROGRESS -> Icons.Filled.Sync
    OlcTone.OK -> Icons.Filled.CheckCircle
    OlcTone.WARN -> Icons.Filled.Warning
    OlcTone.ERROR -> Icons.Filled.Dangerous
}

/** The status glyph; progress sends out a fading ring. Decoration: the word beside it carries the meaning. */
@Composable
fun OlcStatusDot(tone: OlcTone, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val color = tone.color()
    val still = reduceMotion()
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (tone == OlcTone.PROGRESS && !still) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ring")
            Box(
                Modifier
                    .size(size)
                    .drawBehind {
                        val r = (this.size.minDimension / 2f) * (1f + 1.4f * t)
                        drawCircle(color.copy(alpha = 0.6f * (1f - t)), radius = r, style = Stroke(width = 2.dp.toPx()))
                    },
            )
        }
        Icon(tone.icon(), contentDescription = null, tint = color, modifier = Modifier.size(size))
    }
}

/** A dot, a title and an optional subtitle that may wrap: the sentence that says what to do is never cut off. */
@Composable
fun OlcStatusPill(
    tone: OlcTone,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = OlcosTheme.palette
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OlcStatusDot(tone)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.label, color = p.textPrimary)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = Type.caption, color = p.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.let {
            Spacer(Modifier.width(Metrics.s2))
            it()
        }
    }
}

// MARK: - 5. OlcOverflowMenu

/** One entry of a row's or card's complete action set; a destructive one reads in coral. */
class OlcMenuItem(
    val title: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val dividerBefore: Boolean = false,
    val onClick: () -> Unit,
)

/** The ONE menu: a circled ellipsis that opens the full action set. */
@Composable
fun OlcOverflowMenu(items: List<OlcMenuItem>, modifier: Modifier = Modifier) {
    val p = OlcosTheme.palette
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .size(44.dp)
                .olcPressable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .border(1.6.dp, p.textSecondary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
        OlcDropdown(open, onDismiss = { open = false }, items = items)
    }
}

/** The menu surface itself: the card colour, a 14dp radius, rows in the body face. */
@Composable
fun OlcDropdown(open: Boolean, onDismiss: () -> Unit, items: List<OlcMenuItem>) {
    val p = OlcosTheme.palette
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(14.dp),
        containerColor = p.card,
        border = BorderStroke(1.dp, p.cardBorder),
    ) {
        for (item in items) {
            if (item.dividerBefore) HorizontalDivider(color = p.separator)
            val tint = if (item.destructive) p.destructive else p.textPrimary
            DropdownMenuItem(
                text = { Text(item.title, style = Type.body, color = tint) },
                trailingIcon = item.icon?.let { icon -> { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp)) } },
                onClick = {
                    onDismiss()
                    item.onClick()
                },
                colors = MenuDefaults.itemColors(textColor = tint),
            )
        }
    }
}

// MARK: - 6. OlcSegmented

/** One option of a segmented control or a chip picker. */
class OlcOption<T>(val value: T, val label: String, val disabled: Boolean = false)

/** The track with the active segment as the indigo plate. */
@Composable
fun <T> OlcSegmented(selection: T, options: List<OlcOption<T>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = OlcosTheme.palette
    val track = RoundedCornerShape(Metrics.segmentedRadius)
    Row(
        modifier
            .fillMaxWidth()
            .clip(track)
            .background(p.fill, track)
            .border(1.dp, p.fillBorder, track)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (option in options) {
            val active = option.value == selection
            val plate by animateColorAsState(if (active) p.accentFill else Color.Transparent, tween(150), label = "segment")
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(plate)
                    .clickable(enabled = !option.disabled, role = Role.Tab) { onSelect(option.value) }
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label, style = Type.label, textAlign = TextAlign.Center,
                    color = if (active) p.onAccent else if (option.disabled) p.textTertiary else p.textSecondary,
                )
            }
        }
    }
}

// MARK: - 7. OlcChipPicker

/** Capsules that wrap to the next line; the chosen one is the indigo plate. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> OlcChipPicker(selection: T, options: List<OlcOption<T>>, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val p = OlcosTheme.palette
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Metrics.s2),
        verticalArrangement = Arrangement.spacedBy(Metrics.s2),
    ) {
        for (option in options) {
            val active = option.value == selection
            val fill = when {
                active -> p.accentFill
                option.disabled -> p.fill.copy(alpha = p.fill.alpha * 0.5f)
                else -> p.fill
            }
            Box(
                Modifier
                    .heightIn(min = Metrics.chipHeight)
                    .olcPressable(enabled = !option.disabled) { onSelect(option.value) }
                    .clip(CircleShape)
                    .background(fill, CircleShape)
                    .then(if (active && option.disabled) Modifier.border(1.5.dp, p.red, CircleShape) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label, style = Type.label,
                    color = if (active) p.onAccent else if (option.disabled) p.textTertiary else p.textSecondary,
                )
            }
        }
    }
}

// MARK: - 8. Progress, metric, empty state

@Composable
fun OlcProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val p = OlcosTheme.palette
    val shown by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(300), label = "progress")
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(p.fill)) {
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(p.amber))
    }
}

/** A measured number under its label; the number never wraps. */
@Composable
fun OlcMetric(label: String, value: String, modifier: Modifier = Modifier, tone: Color? = null, unit: String? = null) {
    val p = OlcosTheme.palette
    Column(modifier) {
        Text(label, style = Type.captionStrong, color = p.textSecondary)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = Type.metricValue, color = tone ?: p.textPrimary, maxLines = 1)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = Type.caption, color = p.textSecondary)
            }
        }
    }
}

@Composable
fun OlcEmptyState(
    icon: ImageVector,
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    ctaTitle: String? = null,
    ctaIcon: ImageVector? = null,
    onCta: (() -> Unit)? = null,
) {
    val p = OlcosTheme.palette
    Column(
        modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(p.fill), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(28.dp))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = Type.title, color = p.textPrimary, textAlign = TextAlign.Center)
            Text(hint, style = Type.body, color = p.textSecondary, textAlign = TextAlign.Center)
        }
        if (ctaTitle != null && onCta != null) {
            OlcButton(ctaTitle, onCta, icon = ctaIcon, role = OlcRole.PRIMARY, fillWidth = true, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// MARK: - 9. Grouped lists (the iOS inset-grouped Form)

/**
 * A section of rows on one card, with the header above and a note below, the
 * way the iOS Settings screens group their rows. Rows are separated by inset
 * hairlines (`OlcRowDivider`).
 */
@Composable
fun OlcGroup(
    modifier: Modifier = Modifier,
    header: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = OlcosTheme.palette
    Column(modifier.fillMaxWidth()) {
        if (header != null) OlcSectionHeader(header)
        OlcCard(padding = 0.dp, content = content)
        if (footer != null) {
            Text(footer, style = Type.caption, color = p.textSecondary, modifier = Modifier.padding(start = Metrics.s4, end = Metrics.s4, top = Metrics.s2))
        }
    }
}

/** The hairline between two rows of a group, inset from the leading edge like iOS. */
@Composable
fun OlcRowDivider(inset: Dp = Metrics.s4) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 0.5.dp, color = OlcosTheme.palette.separator)
}

/**
 * One row of a group: an optional glyph, the title (and a second line), a
 * value or a trailing control, and a chevron when it opens something.
 */
@Composable
fun OlcRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    value: String? = null,
    titleColor: Color? = null,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val p = OlcosTheme.palette
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = Metrics.controlHeight + 8.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Metrics.s4, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            // The iOS Label: a plain glyph in a fixed column, the words beside it.
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                Icon(icon, contentDescription = null, tint = iconTint ?: p.textSecondary, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(Metrics.s3))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.body, color = titleColor ?: p.textPrimary)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = Type.caption, color = p.textSecondary)
        }
        if (value != null) {
            Spacer(Modifier.width(Metrics.s2))
            Text(value, style = Type.body, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(Metrics.s2))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(Metrics.s1))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = p.textTertiary, modifier = Modifier.size(22.dp))
        }
    }
}

/** The iOS switch: green when on, a quiet track when off. */
@Composable
fun OlcSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val p = OlcosTheme.palette
    Switch(
        checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = if (p.isDark) Color(0xFF30D158) else Color(0xFF34C759),
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = p.fill, uncheckedBorderColor = p.fillBorder,
        ),
    )
}

/** A row with a switch: the whole row toggles. */
@Composable
fun OlcToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null, icon: ImageVector? = null, iconTint: Color? = null) {
    OlcRow(
        title = title, subtitle = subtitle, icon = icon, iconTint = iconTint,
        onClick = { onCheckedChange(!checked) },
        trailing = { OlcSwitch(checked, onCheckedChange) },
    )
}

// MARK: - 10. Screen chrome

/** A tab's title: the iOS large title, the biggest words on the screen after the state. */
@Composable
fun OlcLargeTitle(title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = Metrics.s5, end = Metrics.s3, top = Metrics.s3, bottom = Metrics.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Type.answer, color = OlcosTheme.palette.textPrimary, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/**
 * A pushed screen's bar: the back chevron with its word in the accent colour,
 * the title in the middle, actions on the right; under the status bar.
 */
@Composable
fun OlcTopBar(title: String, onBack: () -> Unit, backLabel: String, actions: (@Composable RowScope.() -> Unit)? = null) {
    val p = OlcosTheme.palette
    Box(
        Modifier
            .fillMaxWidth()
            .background(p.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(52.dp),
    ) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .heightIn(min = 44.dp)
                .clickable(onClick = onBack, role = Role.Button)
                .padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBackIos, contentDescription = null, tint = p.accentTint, modifier = Modifier.size(20.dp))
            Text(backLabel, style = Type.body, color = p.accentTint, maxLines = 1)
        }
        Text(
            title, style = Type.bodyStrong, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
        )
        if (actions != null) {
            Row(Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** One tab of the bar at the bottom. */
class OlcTab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/**
 * The iOS tab bar: icon over a short word, the chosen tab in the accent
 * colour, no pill behind it; a hairline on top of the bar's own fill.
 */
@Composable
fun OlcTabBar(tabs: List<OlcTab>, selected: Int, onSelect: (Int) -> Unit) {
    val p = OlcosTheme.palette
    Column(Modifier.fillMaxWidth().background(if (p.isDark) Color(0xF2121316) else Color(0xF7F9F9F9))) {
        HorizontalDivider(thickness = 0.5.dp, color = p.separator)
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(56.dp),
        ) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                val tint by animateColorAsState(if (active) p.accentTint else p.textSecondary, tween(150), label = "tab")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (active) tab.selectedIcon else tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(2.dp))
                    Text(tab.label, color = tint, style = Type.captionStrong.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                }
            }
        }
    }
}

/** A linear sweep for things that run until done, in the accent colour. */
@Composable
fun OlcSpinner(modifier: Modifier = Modifier, size: Dp = 20.dp) {
    CircularProgressIndicator(modifier.size(size), color = OlcosTheme.palette.accentTint, strokeWidth = 2.dp, trackColor = Color.Transparent)
}
