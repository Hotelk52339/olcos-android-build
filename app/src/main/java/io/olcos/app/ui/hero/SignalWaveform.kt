package io.olcos.app.ui.hero

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.olcos.app.ui.OlcosPalette
import io.olcos.app.ui.OlcosTheme
import io.olcos.app.ui.Type
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max

/** The picture's height, the iOS `Theme.Signal.waveHeight`. */
private val WaveHeight = 220.dp

/**
 * Where the readout sits inside the picture. The braided line swings about
 * ±0.2 of the height around its axis (0.51), so a block that starts at 0.12
 * stays clear of it even at full traffic.
 */
private const val READOUT_TOP_FRACTION = 0.12f
private val ReadoutTrailingInset = 6.dp
private val ReadoutLineGap = 2.dp

/**
 * The readout lives inside a fixed-height picture: past this size (the iOS
 * Dynamic Type cap, xxLarge) the figures would cross the line.
 */
private val ReadoutMaxSize = 19.dp

/** The faint halo that lifts the figures off the glow behind them (dark theme). */
private val ReadoutHalo = 5.dp

private val FRAME_INTERVAL_NANOS = (1_000_000_000L / SignalMotionPolicy.FRAMES_PER_SECOND).toLong()

/** A frame may come this much early and still count, so a 60 Hz display really gives every other frame. */
private const val FRAME_SLACK_NANOS = 3_000_000L

/**
 * Signal's central mark, the iOS app's SignalWaveform: a beam meeting a stone
 * firewall wall. [intensity] (0…1, from [SignalMotionPolicy.intensity]) sets how
 * dense and fast the traffic flows; it comes from measured tunnel counters, never
 * from an invented signal, and is eased here, so samples that arrive once a
 * second breathe instead of jumping. [inboundShare] splits the traffic line's
 * packet trains between the two directions (0.5 when the measurement cannot be
 * split). [throughput] is printed top right once the wall has broken; null
 * prints nothing.
 *
 * The picture owns no timer: its only clock is a frame loop that runs while
 * connected or connecting, or for the few frames an idle or error transition
 * needs, and only while resumed with animations on. Otherwise it holds the
 * settled frame for the state. The first state is adopted settled: opening the
 * app while connected never replays the break.
 */
@Composable
fun SignalWaveform(
    state: FirewallBeamState,
    intensity: Double,
    modifier: Modifier = Modifier,
    inboundShare: Double = 0.5,
    throughput: ThroughputReading? = null,
) {
    val palette = OlcosTheme.palette
    val dark = OlcosTheme.isDark
    val density = LocalDensity.current
    val locale = appLocale(LocalConfiguration.current.locales)
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val reduceMotion = rememberAnimationsRemoved()

    val motionPermitted = !reduceMotion && resumed
    // The continuous clock: connected traffic or the connecting push.
    val moves = SignalMotionPolicy.allowsMotion(state, sceneIsActive = resumed, reduceMotion = reduceMotion, isVisible = true)
    val wave = remember { WaveDriver(state, intensity, moves, motionPermitted) }
    // Applied while composing rather than in an effect, so the very frame that
    // shows a new state already draws its transition, never one settled frame first.
    wave.sync(state, intensity, moves, motionPermitted)
    // The finite idle/error clock.
    val settling = wave.settling && motionPermitted
    val live = moves || settling
    val settleToken = wave.settleToken

    val renderer = remember { FirewallRenderer() }
    val textMeasurer = rememberTextMeasurer()
    val frameTick = remember { mutableLongStateOf(0L) }
    val targetIntensity by rememberUpdatedState(intensity)
    val targetShare by rememberUpdatedState(inboundShare)
    val touchFollows by rememberUpdatedState(moves)

    LaunchedEffect(moves, settling, settleToken) {
        if (moves) {
            var last = 0L
            while (true) {
                val now = awaitPacedFrame(last)
                last = now
                val seconds = now / 1e9
                wave.clock.advance(seconds, targetIntensity)
                wave.advanceModel(seconds, targetShare)
                frameTick.longValue = now
            }
        } else if (settling) {
            // A finite schedule: a frame at a time until the wall has reassembled or
            // the impact flash has faded, then it stops on the settled frame.
            val deadline = wave.settleStart + wave.settleRemaining + 1 / SignalMotionPolicy.FRAMES_PER_SECOND
            var last = 0L
            while (true) {
                val now = awaitPacedFrame(last)
                last = now
                val seconds = now / 1e9
                wave.advanceModel(seconds, targetShare)
                frameTick.longValue = now
                if (wave.model.transientRemaining <= 0 || seconds >= deadline) break
            }
        }
    }

    val readout = remember(throughput, locale, dark, palette) { readoutLines(throughput, locale, dark, palette) }
    val readoutType = remember(density) { readoutStyle(density) }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(WaveHeight)
            // Read on the initial pass and never consumed: a vertical swipe that
            // starts on the beam still scrolls the screen.
            .pointerInput(wave) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val held = event.changes.firstOrNull { it.pressed }
                        if (held == null) {
                            wave.touch = null
                        } else if (touchFollows) {
                            wave.touch = held.position
                        }
                    }
                }
            },
    ) {
        // The frame clock's tick: reading it here re-runs this draw, never the composition.
        frameTick.longValue
        val model = if (live) {
            wave.model
        } else {
            // Still: the settled frame for the state (animations off, not resumed, idle).
            FirewallBeamModel.settled(state, intensity).also { it.inboundShare = inboundShare }
        }
        val touch = if (moves) wave.touch else null
        val phase = if (moves) wave.clock.phase else 0.0
        val relative = if (moves) {
            SignalMotionPolicy.amplitude(wave.clock.intensity, isConnected = true)
        } else {
            SignalMotionPolicy.amplitude(0.0, isConnected = state == FirewallBeamState.CONNECTED)
        }
        // The strands waver at a quarter of the wave amplitude: a beam, not a wave.
        val amplitude = size.height * relative * 0.28
        renderer.draw(
            this, model, phase, amplitude,
            touchX = touch?.let { (it.x / max(1f, size.width)).toDouble() },
            touchY = touch?.let { (it.y / max(1f, size.height)).toDouble() },
            dark = dark,
            palette = palette,
        )
        drawReadout(model, readout, textMeasurer, readoutType, dark, palette)
    }
}

/**
 * The locale the readout speaks: the first of the user's languages the app has
 * words for (English, Russian), as the resources choose; otherwise the first.
 */
private fun appLocale(locales: LocaleList): Locale {
    for (i in 0 until locales.size()) {
        val candidate = locales[i]
        if (candidate.language == "en" || candidate.language == "ru") return candidate
    }
    return if (locales.size() > 0) locales[0] else Locale.getDefault()
}

/**
 * Holds the model across frames outside Compose state: the frame loop and the
 * draw read and advance it directly, and only the frame tick asks for a redraw.
 */
private class WaveDriver(
    state: FirewallBeamState,
    intensity: Double,
    private var lastMoves: Boolean,
    private var lastPermitted: Boolean,
) {
    /** The first state is adopted settled: opening the app while connected must not replay the break. */
    var model = FirewallBeamModel.settled(state, intensity)
        private set
    val clock = SignalPhaseClock()

    /** The finger on the picture, in its pixels, while one is down and the picture moves. */
    var touch: Offset? = null
    private var appliedState = state

    /** A finite idle/error settle schedule exists for the current state. */
    var settling = false
        private set
    var settleToken = 0
        private set
    var settleStart = 0.0
        private set
    var settleRemaining = 0.0
        private set

    fun sync(state: FirewallBeamState, intensity: Double, moves: Boolean, motionPermitted: Boolean) {
        if (moves != lastMoves) {
            lastMoves = moves
            clock.reset()
        }
        if (motionPermitted != lastPermitted) {
            lastPermitted = motionPermitted
            if (!motionPermitted) {
                // Never replay an interrupted transition on returning to the screen.
                model = FirewallBeamModel.settled(state, intensity)
                settling = false
                clock.reset()
            }
        }
        if (state != appliedState) {
            appliedState = state
            adopt(state, intensity, motionPermitted)
        }
    }

    /** Every change after the first animates from wherever the picture is now. */
    private fun adopt(new: FirewallBeamState, intensity: Double, motionPermitted: Boolean) {
        val now = System.nanoTime() / 1e9
        model.advance(now)
        model.applyState(new)
        val remaining = model.transientRemaining
        when {
            !motionPermitted -> {
                model = FirewallBeamModel.settled(new, intensity)
                settling = false
            }
            SignalMotionPolicy.keepsClock(new) -> settling = false
            remaining <= 0 -> {
                // Nothing to animate: hold the settled frame.
                model = FirewallBeamModel.settled(new, intensity)
                settling = false
            }
            else -> {
                settling = true
                settleStart = now
                settleRemaining = remaining
                settleToken++
            }
        }
    }

    fun advanceModel(seconds: Double, inboundShare: Double) {
        model.intensity = clock.intensity
        model.inboundShare = inboundShare
        model.advance(seconds)
    }
}

/**
 * The next frame, at most [SignalMotionPolicy.FRAMES_PER_SECOND] times a second
 * on any display rate (the iOS clock's minimum interval), sleeping through the
 * frames in between instead of waking for each.
 */
private suspend fun awaitPacedFrame(previous: Long): Long {
    if (previous == 0L) return withFrameNanos { it }
    val earliest = previous + FRAME_INTERVAL_NANOS - FRAME_SLACK_NANOS
    val wait = earliest - System.nanoTime()
    if (wait > 1_000_000L) delay(wait / 1_000_000L)
    var now = withFrameNanos { it }
    while (now < earliest) now = withFrameNanos { it }
    return now
}

/**
 * Whether the system's "Remove animations" is on, the iOS Reduce Motion. It is
 * watched while the picture is shown, so turning it on mid-transition settles
 * the picture at once, as Reduce Motion does on iOS.
 */
@Composable
private fun rememberAnimationsRemoved(): Boolean {
    val context = LocalContext.current
    val removed = remember(context) { mutableStateOf(animationsRemoved(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                removed.value = animationsRemoved(context)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return removed.value
}

private fun animationsRemoved(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

// The speed readout.

/** One printed rate: the arrow in the tint of its trains, the figure in the text colour. */
private class ReadoutLine(val arrow: String, val value: String, val tint: Color)

/**
 * ↓ wears the tint of the trains coming back and ↑ the tint of the trains going
 * out, so the numbers also explain the two colours of the flow. An estimate has
 * no direction and prints one approximate figure.
 */
private fun readoutLines(
    throughput: ThroughputReading?,
    locale: Locale,
    dark: Boolean,
    palette: OlcosPalette,
): List<ReadoutLine>? =
    when (throughput) {
        null -> null
        is ThroughputReading.Exact -> listOf(
            ReadoutLine(
                "↓", ThroughputFormat.bits(throughput.inBytesPerSecond, locale),
                FirewallRenderer.inboundTrainColor(dark),
            ),
            ReadoutLine(
                "↑", ThroughputFormat.bits(throughput.outBytesPerSecond, locale),
                FirewallRenderer.outboundTrainColor(dark),
            ),
        )
        is ThroughputReading.Estimate -> listOf(
            ReadoutLine("≈", ThroughputFormat.bits(throughput.totalBytesPerSecond, locale), palette.signalCyan),
        )
    }

/**
 * The readout's type: the caption step, semibold, with digits that keep their
 * width while they change, no larger than [ReadoutMaxSize].
 */
private fun readoutStyle(density: Density): TextStyle {
    val base = Type.captionStrong.copy(fontFeatureSettings = "tnum")
    if (!base.fontSize.isSp) return base
    return with(density) {
        val shown = base.fontSize.toDp()
        if (shown <= ReadoutMaxSize) {
            base
        } else {
            val shrink = ReadoutMaxSize / shown
            base.copy(
                fontSize = ReadoutMaxSize.toSp(),
                lineHeight = if (base.lineHeight.isSp) (base.lineHeight.toDp() * shrink).toSp() else base.lineHeight,
            )
        }
    }
}

/**
 * The measured rates, top right: above the line and past the wall's place, so
 * they belong to the open path. They fade in with the line itself
 * ([FirewallBeamModel.lineSettle]), that is only once the wall has broken.
 */
private fun DrawScope.drawReadout(
    model: FirewallBeamModel,
    lines: List<ReadoutLine>?,
    measurer: TextMeasurer,
    style: TextStyle,
    dark: Boolean,
    palette: OlcosPalette,
) {
    if (model.state != FirewallBeamState.CONNECTED || lines == null) return
    val reveal = clampUnit(model.lineSettle).toFloat()
    if (!(reveal > 0f)) return
    val halo = if (dark) {
        Shadow(color = palette.signalCyan.copy(alpha = 0.45f * reveal), blurRadius = ReadoutHalo.toPx())
    } else {
        null
    }
    val trailing = size.width - ReadoutTrailingInset.toPx()
    var y = size.height * READOUT_TOP_FRACTION
    for (line in lines) {
        // The fade rides in the colours: a laid-out text's own alpha would fade only its base colour.
        val text = buildAnnotatedString {
            withStyle(SpanStyle(color = line.tint.copy(alpha = line.tint.alpha * reveal), fontWeight = FontWeight.Bold)) {
                append(line.arrow + " ")
            }
            withStyle(SpanStyle(color = palette.textPrimary.copy(alpha = palette.textPrimary.alpha * reveal))) {
                append(line.value)
            }
        }
        val layout = measurer.measure(text, style = style, softWrap = false, maxLines = 1)
        drawText(layout, topLeft = Offset(trailing - layout.size.width, y), shadow = halo)
        y += layout.size.height + ReadoutLineGap.toPx()
    }
}
