package io.olcos.app.ui.hero

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// The hero picture's policy and state machine, the iOS app's
// (App/Views/SignalWaveform.swift), free of Android and Compose so every
// motion decision can be tested without a device, a tunnel or a frame clock.
//
// Motion follows REAL tunnel throughput (bytes/s in + out, smoothed). The
// continuous frame clock runs only while connected (traffic flows) or
// connecting (the beam pushes into the wall), and only on screen, resumed and
// with animations on. Idle and error settle on a short, finite schedule (the
// wall reassembles, the impact flash fades) and then hold a still frame. The
// picture is never a health verdict: its one number is the measured
// throughput, printed above the line only once the wall has given way.

/** What the hero asks the picture to show; the Home screen maps the tunnel state onto it. */
enum class FirewallBeamState { IDLE, CONNECTING, CONNECTED, ERROR }

object SignalMotionPolicy {
    const val FRAMES_PER_SECOND = 30.0

    /** Strands in the beam bundle and samples along each strand. */
    const val LINE_COUNT = 7
    const val SAMPLE_COUNT = 96

    /** Relative amplitude (a fraction of the picture's height) of the strand waver at intensity 0 / 1. */
    const val CALM_AMPLITUDE = 0.13
    const val PEAK_AMPLITUDE = 0.31

    /** Relative amplitude of the faint, still beam while not connected. */
    const val STATIC_AMPLITUDE = 0.055

    /** Phase advance in rad/s at intensity 0 / 1: an idle breath against a busy flow. */
    const val CALM_SPEED = PI / 5.0
    const val PEAK_SPEED = PI * 0.85

    /**
     * Easing rate (1/s) of the shown intensity toward the target: the samples
     * arrive once a second as steps, and the picture must breathe, not jump.
     */
    const val INTENSITY_EASING = 2.5

    /** Throughput that maps to full intensity: above it the flow is as busy as it gets. */
    const val FULL_INTENSITY_BYTES_PER_SECOND = 8_000_000.0

    /** Below this the connection counts as idle (keep-alive noise only). */
    const val IDLE_BYTES_PER_SECOND = 512.0

    fun allowsMotion(isConnected: Boolean, sceneIsActive: Boolean, reduceMotion: Boolean, isVisible: Boolean): Boolean =
        isConnected && sceneIsActive && !reduceMotion && isVisible

    /**
     * The states that keep a CONTINUOUS clock: connected (particles flow with
     * traffic) and connecting (the beam pushes and the cracks grow). Idle and
     * error run a finite settle schedule and then hold a still frame.
     */
    fun keepsClock(state: FirewallBeamState): Boolean =
        state == FirewallBeamState.CONNECTED || state == FirewallBeamState.CONNECTING

    fun allowsMotion(state: FirewallBeamState, sceneIsActive: Boolean, reduceMotion: Boolean, isVisible: Boolean): Boolean =
        allowsMotion(keepsClock(state), sceneIsActive, reduceMotion, isVisible)

    /**
     * 0…1 from a smoothed byte rate, log-scaled so that 20 KB/s and 2 MB/s read
     * as visibly different flows. Non-finite or negative input is idle.
     */
    fun intensity(bytesPerSecond: Double): Double {
        if (!bytesPerSecond.isFinite() || bytesPerSecond <= IDLE_BYTES_PER_SECOND) return 0.0
        val top = log10(1 + FULL_INTENSITY_BYTES_PER_SECOND / IDLE_BYTES_PER_SECOND)
        val value = log10(1 + bytesPerSecond / IDLE_BYTES_PER_SECOND) / top
        return min(1.0, max(0.0, value))
    }

    fun amplitude(intensity: Double, isConnected: Boolean): Double {
        if (!isConnected) return STATIC_AMPLITUDE
        val t = clampUnit(intensity)
        return CALM_AMPLITUDE + (PEAK_AMPLITUDE - CALM_AMPLITUDE) * t
    }

    fun speed(intensity: Double): Double {
        val t = clampUnit(intensity)
        return CALM_SPEED + (PEAK_SPEED - CALM_SPEED) * t
    }

    /** One easing step of the shown intensity toward [target] over [dt] seconds; it never overshoots. */
    fun eased(current: Double, target: Double, dt: Double): Double {
        if (!dt.isFinite() || dt <= 0) return current
        val k = min(1.0, dt * INTENSITY_EASING)
        return current + (target - current) * k
    }

    /**
     * The strand waver along the beam, not a microphone envelope. Normalised
     * inputs keep the touch bend and the draw cost strictly bounded; both ends
     * are at rest, so the bundle leaves the edge and meets the wall as one beam.
     */
    fun displacement(x: Double, line: Int, phase: Double, touchX: Double?, touchY: Double?): Double {
        val at = clampUnit(x)
        val strand = min(LINE_COUNT - 1, max(0, line)).toDouble()
        val envelope = sin(PI * at).pow(1.6)
        val carrier = sin(at * PI * 3.4 - phase + strand * 0.24)
        val overtone = sin(at * PI * 6.0 - phase * 0.65 + strand * 0.38) * 0.24
        var touch = 0.0
        if (touchX != null && touchY != null) {
            val dx = at - clampUnit(touchX)
            val strength = (0.5 - clampUnit(touchY)) * 0.8
            touch = exp(-dx * dx * 24) * strength
        }
        return (carrier * 0.65 + overtone + touch) * envelope
    }
}

/**
 * Integrates the strand phase over wall-clock time at a speed that may change
 * every frame, so a throughput change alters the pace without a visible jump.
 * Also eases the shown intensity toward the target.
 */
class SignalPhaseClock {
    var phase = 0.0
        private set
    var intensity = 0.0
        private set
    private var last: Double? = null

    /** [seconds] is any monotonic time base; only differences between calls matter. */
    fun advance(seconds: Double, target: Double) {
        val previous = last
        last = seconds
        if (previous == null) {
            // The first frame seeds time without invented travel, at the measured intensity.
            intensity = if (target.isFinite()) clampUnit(target) else 0.0
            return
        }
        val dt = min(0.25, max(0.0, seconds - previous))
        intensity = SignalMotionPolicy.eased(intensity, target, dt)
        phase += SignalMotionPolicy.speed(intensity) * dt
        if (phase > 2 * PI * 1_000) phase -= 2 * PI * 1_000
    }

    /** Forgets the last frame, so the time the picture was hidden is never integrated. */
    fun reset() {
        last = null
    }
}

/**
 * The hero picture: an incoming beam meets a stone masonry firewall wall.
 *  - idle: the wall is whole, the beam faint and ending at the wall;
 *  - connecting: sustained local pressure, no outgoing light;
 *  - connected: pressure, seep through the joints, the outgoing strands fuse,
 *    then the whole wall cracks and breaks (one-shot, [BREAK_DURATION]); then
 *    steady traffic flows at a pace set by the measured intensity, never replayed;
 *  - error: the pressure stretches, a failure tint arrives late, then the intact
 *    armour recoils; no outgoing light;
 *  - leaving connected: the dispersed armour reassembles ([REASSEMBLE_DURATION]).
 * An interrupted success cancels the outgoing light at once; dispersed fragments
 * may reverse into place before the new state's resting picture.
 *
 * This is the whole state machine; the canvas only reads it. Nothing here draws,
 * keeps time on its own or randomises: per-cell and per-particle variation comes
 * from [noise], a deterministic hash of the index.
 */
class FirewallBeamModel {
    enum class Phase {
        /** The wall is whole, the beam faint (idle). */
        INTACT,

        /** The beam pushes into the wall and the cracks grow (connecting). */
        CHARGING,

        /** One-shot: pressure, seep, fusion, then the armour's destruction (into [OPEN]). */
        BREAKING,

        /** The armour is gone; the outgoing beam carries measured traffic (connected). */
        OPEN,

        /** A pressure stretch, a late failure tint, the intact armour's recoil (error). */
        REJECTED,

        /** The break in reverse, into the resting phase of the current state. */
        REASSEMBLING,
    }

    /** A traffic particle; positions are in the unit square. */
    data class Particle(
        /** 0…1 across the whole picture. */
        val x: Double,
        /** -1…1 across the beam's thickness. */
        val lane: Double,
        /** 0…1 relative size. */
        val size: Double,
        /** 0…1 brightness. */
        val alpha: Double,
    )

    /** Stage boundaries as fractions of [BREAK_DURATION] (that is, of [breach]). */
    object Stage {
        /** The pressure peaks. */
        const val PRESSURE_END = 0.2

        /** Light starts to seep through the joints. */
        const val SEEP_START = 0.2
        const val SEEP_END = 0.42

        /** The seeping strands merge into one core. */
        const val FUSION_START = 0.42
        const val FUSION_END = 0.6

        /** Stones start to fly; the wall is gone entirely at [DESTRUCTION_END]. */
        const val BURST = 0.62
        const val DESTRUCTION_END = 0.88

        /** The thin released beam thickens into the braided line. */
        const val SETTLE_START = 0.8
        const val SETTLE_END = 1.0
    }

    var state = FirewallBeamState.IDLE
        private set
    var phase = Phase.INTACT
        private set

    /** 0…1 inside the current phase (1 = settled for intact and open). */
    var progress = 1.0
        private set
    var phaseStart = 0.0
        private set

    /** The latest time seen by [advance]; it never decreases. */
    var time = 0.0
        private set

    /** Shown throughput intensity 0…1, already eased by the caller; non-finite reads as idle. */
    var intensity = 0.0
        set(value) {
            field = if (value.isFinite()) clampUnit(value) else 0.0
        }

    /**
     * Integrated particle travel in picture widths: the speed may change every
     * frame without the particles jumping.
     */
    var flow = 0.0
        private set

    /**
     * Which part of the traffic line's packet trains runs back toward the
     * device (↓). An undirected measurement, or none, keeps the even 0.5 split.
     */
    var inboundShare = 0.5
        set(value) {
            field = if (value.isFinite()) clampUnit(value) else 0.5
        }

    private var entryPressure = 0.0

    // Derived picture.

    /** 0 = the wall whole, 1 = the gap fully open. Linear in time; the renderer eases. */
    val breach: Double
        get() = when (phase) {
            Phase.INTACT, Phase.CHARGING, Phase.REJECTED -> 0.0
            Phase.OPEN -> 1.0
            Phase.BREAKING -> progress
            Phase.REASSEMBLING -> 1 - progress
        }

    /** 0…1 crack growth on the wall's face. */
    val crack: Double
        get() = when (phase) {
            Phase.CHARGING -> progress
            Phase.BREAKING -> pressure
            Phase.REASSEMBLING -> 1 - progress
            Phase.REJECTED -> pressure
            Phase.INTACT, Phase.OPEN -> 0.0
        }

    /** Local deformation, not proof that the connection succeeded. */
    val pressure: Double
        get() = when (phase) {
            Phase.CHARGING -> smooth(progress)
            Phase.BREAKING ->
                (entryPressure + (1 - entryPressure) * smooth(progress / Stage.PRESSURE_END)) * (1 - destruction)
            Phase.REJECTED -> {
                val rise = entryPressure + (1 - entryPressure) * smooth(progress / 0.32)
                rise * (1 - smooth((progress - 0.55) / 0.45))
            }
            Phase.REASSEMBLING -> breach * (1 - destruction)
            Phase.INTACT, Phase.OPEN -> 0.0
        }

    /** No outgoing light until the authoritative state is connected. */
    val seep: Double
        get() {
            if (state != FirewallBeamState.CONNECTED) return 0.0
            return smooth((breach - Stage.SEEP_START) / (Stage.SEEP_END - Stage.SEEP_START))
        }

    val fusion: Double
        get() {
            if (state != FirewallBeamState.CONNECTED) return 0.0
            return smooth((breach - Stage.FUSION_START) / (Stage.FUSION_END - Stage.FUSION_START))
        }

    /** Every stone disperses, the top and bottom courses included. */
    val destruction: Double
        get() = smooth((breach - Stage.BURST) / (Stage.DESTRUCTION_END - Stage.BURST))

    /**
     * 0 = the thin released beam, 1 = the full braided traffic line. Like the
     * other outputs it exists only while the authoritative state is connected.
     */
    val lineSettle: Double
        get() {
            if (state != FirewallBeamState.CONNECTED) return 0.0
            return smooth((breach - Stage.SETTLE_START) / (Stage.SETTLE_END - Stage.SETTLE_START))
        }

    val failureTint: Double
        get() = if (!isRed) 0.0 else if (phase == Phase.REJECTED) smooth((progress - 0.32) / 0.16) else 1.0

    /** The rejection strain grows continuously, without a jump in gain at entry. */
    val rejectionStrain: Double
        get() = if (phase == Phase.REJECTED) smooth(progress / 0.32) else 0.0

    /** 0…1 red impact flash (error only), fading over [FLASH_DURATION]. */
    val flash: Double
        get() = if (phase == Phase.REJECTED) max(0.0, 1 - progress) else 0.0

    /** 0…1 push of the beam into the wall while connecting (a slow pulse). */
    val pulse: Double
        get() {
            if (phase != Phase.CHARGING && phase != Phase.BREAKING) return 0.0
            return 0.5 + 0.5 * sin(2 * PI * PULSE_HERTZ * time)
        }

    /** How green the beam is: exactly how open the wall is. */
    val greenMix: Double get() = breach
    val isRed: Boolean get() = state == FirewallBeamState.ERROR
    val isSignal: Boolean get() = state == FirewallBeamState.CONNECTING

    /**
     * True while something changes on its own: transitions, the connecting
     * pulse, the error flash and the particles flowing through an open gap.
     */
    val isAnimating: Boolean
        get() = when (phase) {
            Phase.INTACT -> false
            Phase.CHARGING, Phase.BREAKING, Phase.OPEN, Phase.REASSEMBLING -> true
            Phase.REJECTED -> progress < 1
        }

    /**
     * Seconds until the current one-shot transition (and the flash that may
     * follow it) has settled; 0 when nothing finite is pending.
     */
    val transientRemaining: Double
        get() = when (phase) {
            Phase.BREAKING -> (1 - progress) * BREAK_DURATION
            Phase.REASSEMBLING -> {
                val rest = (1 - progress) * REASSEMBLE_DURATION
                rest + (if (state == FirewallBeamState.ERROR) FLASH_DURATION else 0.0)
            }
            Phase.REJECTED -> (1 - progress) * FLASH_DURATION
            Phase.INTACT, Phase.OPEN, Phase.CHARGING -> 0.0
        }

    /** How many particles to draw through the gap, from the intensity. */
    val particleCount: Int
        get() {
            if (breach <= 0) return 0
            val extra = (MAX_PARTICLES - MIN_PARTICLES).toDouble() * intensity
            return min(MAX_PARTICLES, MIN_PARTICLES + roundHalfAway(extra).toInt())
        }

    // Mutation.

    /**
     * Moves the clock to [now] (never backwards) and completes one-shot
     * transitions. Safe from any phase, at any cadence.
     */
    fun advance(now: Double) {
        if (!now.isFinite()) return
        val dt = min(0.25, max(0.0, now - time))
        time = max(time, now)
        flow += flowSpeed(intensity) * dt
        if (flow > 1_000.0) flow -= 1_000.0
        val elapsed = max(0.0, time - phaseStart)
        when (phase) {
            Phase.INTACT, Phase.OPEN -> progress = 1.0
            Phase.CHARGING -> progress = min(1.0, elapsed / CRACK_DURATION)
            Phase.REJECTED -> progress = min(1.0, elapsed / FLASH_DURATION)
            Phase.BREAKING -> {
                progress = min(1.0, elapsed / BREAK_DURATION)
                if (progress >= 1) enter(Phase.OPEN)
            }
            Phase.REASSEMBLING -> {
                progress = min(1.0, elapsed / REASSEMBLE_DURATION)
                if (progress >= 1) {
                    val completedAt = phaseStart + REASSEMBLE_DURATION
                    enter(restingPhase(state))
                    // Keep the original deadline when a frame arrives late: a
                    // finite schedule has no spare frames to catch up with.
                    phaseStart = completedAt
                    val carried = max(0.0, time - completedAt)
                    if (phase == Phase.REJECTED) {
                        progress = min(1.0, carried / FLASH_DURATION)
                    } else if (phase == Phase.CHARGING) {
                        progress = min(1.0, carried / CRACK_DURATION)
                    }
                }
            }
        }
    }

    /**
     * Switches the target state. A change into connected breaks the wall from
     * however open it already is; any other change first reassembles whatever
     * is open, then settles into that state's resting phase.
     */
    fun applyState(new: FirewallBeamState) {
        if (new == state) return
        val previousPressure = pressure
        state = new
        entryPressure = previousPressure
        val openness = breach
        when (new) {
            FirewallBeamState.CONNECTED -> {
                if (phase == Phase.OPEN) return
                if (openness >= 1) {
                    enter(Phase.OPEN)
                } else {
                    phase = Phase.BREAKING
                    progress = openness
                    phaseStart = time - openness * BREAK_DURATION
                }
            }
            FirewallBeamState.IDLE, FirewallBeamState.CONNECTING, FirewallBeamState.ERROR -> {
                if (openness > 0) {
                    phase = Phase.REASSEMBLING
                    progress = 1 - openness
                    phaseStart = time - progress * REASSEMBLE_DURATION
                } else {
                    enter(restingPhase(new))
                }
            }
        }
    }

    fun restingPhase(state: FirewallBeamState): Phase = when (state) {
        FirewallBeamState.IDLE -> Phase.INTACT
        FirewallBeamState.CONNECTING -> Phase.CHARGING
        FirewallBeamState.CONNECTED -> Phase.OPEN
        FirewallBeamState.ERROR -> Phase.REJECTED
    }

    private fun enter(next: Phase) {
        phase = next
        phaseStart = time
        progress = when (next) {
            Phase.INTACT, Phase.OPEN -> 1.0
            Phase.CHARGING, Phase.BREAKING, Phase.REJECTED, Phase.REASSEMBLING -> 0.0
        }
    }

    /**
     * The i-th traffic particle. Its x wraps across the width from the
     * integrated [flow], so a change of pace never teleports a particle.
     */
    fun particle(index: Int): Particle {
        val n0 = noise(index, 11)
        val n1 = noise(index, 12)
        val n2 = noise(index, 13)
        val n3 = noise(index, 14)
        val speedFactor = 0.75 + 0.6 * n0
        val travel = flow * speedFactor + n1
        val x = travel - floor(travel)
        return Particle(
            x = x,
            lane = (n2 - 0.5) * 2,
            size = 0.35 + 0.65 * n3,
            alpha = min(1.0, breach) * (0.55 + 0.45 * n0),
        )
    }

    companion object {
        /** The ordered success sequence: pressure, seep, merge, destruction, then the braided line settles. */
        const val BREAK_DURATION = 3.6
        const val REASSEMBLE_DURATION = 0.5

        /** Time for the cracks to reach full length while connecting. */
        const val CRACK_DURATION = 1.8
        const val FLASH_DURATION = 1.8
        const val PULSE_HERTZ = 1.4

        const val MAX_PARTICLES = 40
        const val MIN_PARTICLES = 6

        private const val TWO_POW_53 = 9_007_199_254_740_992.0

        /**
         * The frame everything settles into for [state]: animations off, a first
         * appearance and the still picture render this instead of animating.
         */
        fun settled(state: FirewallBeamState, intensity: Double = 0.0): FirewallBeamModel {
            val model = FirewallBeamModel()
            model.state = state
            model.intensity = intensity
            model.enter(model.restingPhase(state))
            model.progress = 1.0
            return model
        }

        fun smooth(value: Double): Double {
            val t = clampUnit(value)
            return t * t * (3 - 2 * t)
        }

        /**
         * Particle travel in picture widths per second at a given intensity: a
         * slow drift with no traffic, about one width per second when busy.
         */
        fun flowSpeed(intensity: Double): Double {
            val t = if (intensity.isFinite()) clampUnit(intensity) else 0.0
            return 0.14 + 0.76 * t
        }

        /**
         * Deterministic 0…1 hash of (index, salt): the same cell or particle always
         * gets the same "random" speed, angle and offset; nothing is random per
         * frame, so the picture is identical in tests and on the phone. Unsigned
         * 64-bit wrapping arithmetic, bit for bit the iOS app's.
         */
        fun noise(index: Int, salt: Int): Double {
            var h = index.toLong().toULong() * 0x9E3779B97F4A7C15uL
            h += salt.toLong().toULong() * 0xBF58476D1CE4E5B9uL
            h = h xor (h shr 31)
            h *= 0x94D049BB133111EBuL
            h = h xor (h shr 29)
            return (h shr 11).toDouble() / TWO_POW_53
        }
    }
}

/**
 * Swift's `min(1, max(0, value))`: NaN comes out as 0 rather than NaN, the way
 * the iOS arithmetic this picture follows treats it.
 */
internal fun clampUnit(value: Double): Double = if (value >= 0.0) (if (value <= 1.0) value else 1.0) else 0.0

/** Rounds half away from zero (Swift's `rounded()`), not half to even like `kotlin.math.round`. */
internal fun roundHalfAway(value: Double): Double {
    if (!value.isFinite()) return value
    val magnitude = abs(value)
    val whole = floor(magnitude)
    val rounded = if (magnitude - whole >= 0.5) whole + 1 else whole
    return if (value < 0) -rounded else rounded
}
