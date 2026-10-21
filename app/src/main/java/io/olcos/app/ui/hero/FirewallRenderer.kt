package io.olcos.app.ui.hero

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import io.olcos.app.ui.OlcosPalette
import io.olcos.app.ui.hero.FirewallBeamModel.Stage
import io.olcos.app.ui.hero.FirewallGeometry.Face
import io.olcos.app.ui.hero.FirewallGeometry.Point
import io.olcos.app.ui.hero.FirewallGeometry.Pose
import io.olcos.app.ui.hero.FirewallGeometry.Scene
import io.olcos.app.ui.hero.FirewallGeometry.Vector
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val STRAND_COUNT = 7
private const val TRUNK_SEGMENTS = 10
private const val WALL_SCALE = FirewallGeometry.WALL_SCALE
private const val DEPTH = FirewallGeometry.DEPTH

/** Where each pressure ray bends: (share of its reach, change of heading). */
private val RAY_KINKS = listOf(0.38 to 0.2, 0.72 to -0.13, 1.0 to 0.0)

/** The fused core's three glow layers: (width, opacity), widest and faintest first. */
private val CORE_LAYERS = listOf(17.0 to 0.07, 9.0 to 0.16, 4.2 to 0.88)

/**
 * Paints the hero picture (the iOS app's FirewallArmorRenderer.swift): a small,
 * deterministic software scene. The entering beam and the fused core are behind
 * the stone wall; the crack web is clipped to the front skins so a nearer stone
 * still hides it, and the seeping rays leave real mortar joints on the lit front
 * face.
 *
 * One instance per picture. It keeps the last scene (the wall's geometry only
 * changes while the wall moves) and scratch paths and arrays, so the thirty
 * frames a second the traffic line needs allocate next to nothing. Reusing a
 * path between draw calls is safe: a recorded draw call keeps its own copy.
 */
class FirewallRenderer {
    private var cachedScene: Scene? = null
    private val sceneKey = DoubleArray(4)
    private val line = Path()
    private val surface = Path()
    private val mask = Path()
    private val strandsA = DoubleArray(STRAND_COUNT * 3)
    private val strandsB = DoubleArray(STRAND_COUNT * 3)
    private val strandOrder = IntArray(STRAND_COUNT)
    private val pointA = DoubleArray(3)
    private val pointB = DoubleArray(3)
    private val seepX = DoubleArray(61)
    private val seepY = DoubleArray(61)
    private val trunk = DoubleArray(2 * (TRUNK_SEGMENTS + 1))
    private val twig = DoubleArray(2 * 4)
    private val leaf = DoubleArray(2 * 3)

    /** The crack's three strokes: (width factor, opacity, colour), a wide glow under a bright hairline. */
    private class CrackLayer(val width: Double, val alpha: Double, val color: Color)

    private val redCrack = listOf(
        CrackLayer(3.2, 0.16, rgb(255, 148, 165)),
        CrackLayer(1.0, 0.88, rgb(255, 148, 165)),
        CrackLayer(0.42, 1.0, rgb(255, 220, 226)),
    )
    private val darkCrack = listOf(
        CrackLayer(3.2, 0.16, rgb(80, 194, 255)),
        CrackLayer(1.0, 0.88, rgb(119, 224, 255)),
        CrackLayer(0.42, 1.0, rgb(237, 254, 255)),
    )
    private val lightCrack = listOf(
        CrackLayer(3.2, 0.16, rgb(11, 122, 153)),
        CrackLayer(1.0, 0.88, rgb(20, 140, 180)),
        CrackLayer(0.42, 1.0, rgb(240, 252, 255)),
    )

    /**
     * Draws [model] into [scope]. [amplitude] is the strand waver in the
     * scope's pixels; [touchX] and [touchY] are a touch as a share of the
     * picture's width and height, or null.
     */
    fun draw(
        scope: DrawScope,
        model: FirewallBeamModel,
        phase: Double,
        amplitude: Double,
        touchX: Double?,
        touchY: Double?,
        dark: Boolean,
        palette: OlcosPalette,
    ) {
        val width = scope.size.width
        val height = scope.size.height
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return
        val frame = Frame(model, dark, palette, width.toDouble(), height.toDouble(), phase, amplitude, touchX, touchY)
        val scene = sceneFor(frame)
        scope.clipRect {
            withTransform({
                translate(left = width * 0.52f, top = height * 0.51f)
                scale(scaleX = frame.scale.toFloat(), scaleY = frame.scale.toFloat(), pivot = Offset.Zero)
            }) {
                // No independent time source: even the wave envelope travels with
                // the integrated model.flow, never time × the latest traffic speed.
                drawIncoming(frame)
                drawPressure(frame)
                drawFused(frame)
                drawArmor(frame, scene)
                drawCracks(frame, scene)
                drawSeep(frame, scene)
                drawDebris(frame)
            }
        }
    }

    /** The wall only moves during a transition: a still wall reuses the scene of the frame before. */
    private fun sceneFor(f: Frame): Scene {
        val pressure = f.pressure
        val breach = f.model.breach
        val rejection = f.model.rejectionStrain
        val crack = clampUnit(f.model.crack)
        val cached = cachedScene
        if (cached != null && sceneKey[0] == pressure && sceneKey[1] == breach &&
            sceneKey[2] == rejection && sceneKey[3] == crack
        ) {
            return cached
        }
        val fresh = FirewallGeometry.scene(pressure = pressure, breach = breach, rejection = rejection, crack = crack)
        sceneKey[0] = pressure
        sceneKey[1] = breach
        sceneKey[2] = rejection
        sceneKey[3] = crack
        cachedScene = fresh
        return fresh
    }

    /** Everything one frame derives from the model, in stage units. */
    private class Frame(
        val model: FirewallBeamModel,
        val dark: Boolean,
        val p: OlcosPalette,
        canvasWidth: Double,
        canvasHeight: Double,
        rawPhase: Double,
        rawAmplitude: Double,
        rawTouchX: Double?,
        rawTouchY: Double?,
    ) {
        /** The continuous strand phase of the settled traffic line (rad). */
        val phase = if (rawPhase.isFinite()) rawPhase else 0.0
        val scale = min(canvasHeight / 440, canvasWidth / 700)
        val left = -canvasWidth * 0.52 / scale
        val right = canvasWidth * 0.48 / scale
        val height = canvasHeight / scale
        val span = right - left
        val amplitude = min(14.0, max(4.0, if (rawAmplitude.isFinite()) rawAmplitude / scale else 8.0))
        val touchX: Double?
        val touchY: Double?
        val touch: Point?

        init {
            if (rawTouchX != null && rawTouchY != null && rawTouchX.isFinite() && rawTouchY.isFinite()) {
                touchX = clampUnit(rawTouchX)
                touchY = clampUnit(rawTouchY)
                touch = Point(left + clampUnit(rawTouchX) * (right - left), (clampUnit(rawTouchY) - 0.51) * height)
            } else {
                touchX = null
                touchY = null
                touch = null
            }
        }

        val pressure = clampUnit(model.pressure)
        val destruction = clampUnit(model.destruction)
        val failure = clampUnit(model.failureTint)
        val success = model.state == FirewallBeamState.CONNECTED
        val fusion = if (success) clampUnit(model.fusion) else 0.0
        val seep = if (success) clampUnit(model.seep) else 0.0

        /** 0 = the thin released beam, 1 = the braided traffic line. */
        val settle = if (success) clampUnit(model.lineSettle) else 0.0
        val intensity = clampUnit(model.intensity)
        val inboundShare = clampUnit(model.inboundShare)
        val burstAge = clampUnit((model.breach - Stage.BURST) / (Stage.DESTRUCTION_END - Stage.BURST)) * 2.6
        val broken = success && destruction > 0
        val mergeX = min(167.0, right - 24)
        val emissionFade = 1 - smooth(destruction / 0.48)
        val flow = if (model.flow.isFinite()) model.flow else 0.0
        val crack = clampUnit(model.crack) * (1 - smooth(destruction / 0.16))
        val brightness = when (model.state) {
            FirewallBeamState.IDLE -> 0.19
            FirewallBeamState.ERROR -> 0.62 + pressure * 0.24
            FirewallBeamState.CONNECTING -> 0.62 + pressure * 0.3
            FirewallBeamState.CONNECTED -> 0.96
        }
        val highlight = if (dark) rgb(237, 254, 255) else rgb(241, 255, 255)

        /** The braided line's axis amplitude: the old volumetric Signal line, breathing with the measured intensity. */
        val braidAmplitude = height * (0.059 + 0.082 * intensity)
        val braidRadius = (7 + 9 * intensity) * settle

        /** The incoming beam's shading, and the fused core's. */
        val beam: Brush = Brush.linearGradient(
            0f to p.signalCyan,
            0.35f to p.signalViolet,
            0.55f to p.signalMid,
            1f to (if (success) p.green else p.signalCyan),
            start = Offset(left.toFloat(), 0f),
            end = Offset(right.toFloat(), 0f),
        )

        fun wave(x: Double, lane: Double = 0.0): Double {
            val travel = x - flow * span
            val primary = sin(travel * 0.025 + lane * 0.23) * amplitude
            val secondary = sin(travel * 0.012 + lane * 0.15) * amplitude * 0.375
            var y = primary + secondary + lane * 1.45
            val t = touch
            if (t != null) {
                val distance = (x - t.x) / 75
                // Touch bends light, never the wall or the direction of travel.
                y += min(28.0, max(-28.0, t.y)) * exp(-distance * distance)
            }
            return y
        }

        fun incomingY(x: Double, lane: Double): Double {
            val focus = if (broken) 1.0 else 1 - (1 - destruction) * 0.78 * exp(-x * x / 2200)
            return wave(x, lane) * focus
        }

        /** The braided line's axis: the strand shape from before the armour. */
        fun axis(x: Double, strand: Int = 3): Double =
            braidAmplitude * SignalMotionPolicy.displacement((x - left) / span, strand, phase, touchX, touchY)

        /** A thin lane while the wall stands, blended into the wide line as it settles. */
        fun laneY(x: Double, lane: Int): Double {
            val narrow = incomingY(x, lane.toDouble())
            if (!(settle > 0)) return narrow
            val wide = lane * 3.0 + axis(x, strand = min(6, max(0, lane + 3)))
            return narrow * (1 - settle) + wide * settle
        }

        /** Both ends of the line rest at the picture's edges. */
        fun edgeFade(x: Double): Double {
            val q = (x - left) / span
            return smooth(q / 0.2) * smooth((1 - q) / 0.2)
        }
    }

    // Light, always behind the solid stone.

    private fun DrawScope.drawIncoming(f: Frame) {
        // While the wall stands the beam presses into its front skin.
        val end = if (f.broken) f.right else (-38 + f.pressure * (12 + DEPTH) - f.failure * 4) * WALL_SCALE
        val settle = f.settle
        if (settle < 1) {
            for (lane in -5..5) {
                sampled(line, f.left, end) { x -> f.laneY(x, lane) }
                val alpha = f.brightness * (if (lane == 0) 0.96 else 0.38) * (1 - settle)
                val width = if (lane == 0) 2.0 else 1.0
                strokePath(line, f.beam, width, alpha * (1 - f.failure))
                strokePath(line, f.p.red, width, alpha * f.failure)
            }
        }
        if (settle > 0) drawBraid(f)
        val count = if (f.success) max(6, f.model.particleCount) else 12
        val particleAlpha = f.brightness * 0.8 * (1 - settle * 0.55)
        for (index in 0 until count) {
            val x = f.left + fraction(f.flow + random(index + 121)) * f.span
            if (!(x <= end)) continue
            val lane = index % 9 - 4
            val y = f.laneY(x, lane)
            val color = if (f.failure > 0.5) f.p.red else if (f.success && x > 25) f.p.green else f.highlight
            sampled(line, max(f.left, x - 4.8), x) { position -> f.laneY(position, lane) }
            strokePath(line, color, 1.8, particleAlpha)
            dot(x, y, 1.1, color, particleAlpha)
        }
    }

    // The braided traffic line.

    /**
     * Strand [k] of a real three-dimensional braid at stage x, written to slot
     * [slot] of [into] as x, y and depth z (-1 far … 1 near). Seven strands twist
     * around the wavy axis; depth drives brightness, width and paint order, so
     * near strands pass in front of far ones and the line reads as a tube.
     */
    private fun strand(k: Int, x: Double, axisY: Double, f: Frame, into: DoubleArray, slot: Int) {
        val radius = f.braidRadius
        val twist = (x - f.left) * 0.028 + f.phase * 1.35
        val angle = twist + k * PI * 2 / STRAND_COUNT
        val z = sin(angle)
        // A slight perspective: nearer strands drift right and up, as in the projection.
        into[3 * slot] = x + z * radius * 0.35
        into[3 * slot + 1] = axisY + cos(angle) * radius - z * radius * 0.12
        into[3 * slot + 2] = z
    }

    private fun DrawScope.drawBraid(f: Frame) {
        val settle = f.settle
        val charge = f.brightness
        val radius = f.braidRadius
        // Every slice is seven strokes, thirty times a second while connected:
        // 8 stage units apart reads as the same braid at half the work of 4.
        val step = 8.0
        // A soft body glow first, behind every strand.
        sampled(line, f.left, f.right, step = 6.0) { x -> f.axis(x) }
        val glow = Brush.linearGradient(
            0f to f.p.signalMid.withOpacity(0.0),
            0.3f to f.p.signalMid.withOpacity(0.16),
            0.7f to f.p.green.withOpacity(0.16),
            1f to f.p.green.withOpacity(0.0),
            start = Offset(f.left.toFloat(), 0f),
            end = Offset(f.right.toFloat(), 0f),
        )
        strokePath(line, glow, radius * 3.2 + 8, settle * charge)

        var previous = strandsA
        var current = strandsB
        val startAxis = f.axis(f.left)
        for (k in 0 until STRAND_COUNT) strand(k, f.left, startAxis, f, previous, k)
        var x = f.left + step
        while (x <= f.right + step / 2) {
            val at = min(x, f.right)
            val axisY = f.axis(at)
            for (k in 0 until STRAND_COUNT) strand(k, at, axisY, f, current, k)
            val edge = f.edgeFade((current[0] + previous[0]) / 2)
            // Back to front within each slice.
            sortByDepth(current, strandOrder)
            for (k in strandOrder) {
                val d = (current[3 * k + 2] + 1) / 2
                strokeLine(
                    previous[3 * k], previous[3 * k + 1], current[3 * k], current[3 * k + 1],
                    strandColor(k, d, f.dark), (1.1 + 2.1 * d) * (0.6 + 0.4 * settle), charge * settle * edge,
                )
            }
            val swap = previous
            previous = current
            current = swap
            x += step
        }
        drawPackets(f)
    }

    /**
     * Internet traffic, not a decoration: bursty packet trains ride the strands.
     * Uploads run outward (left to right), downloads come back the other way in
     * a second tint; the split follows the measured ↓/↑ share, the count and the
     * length follow the throughput, and the pace is the integrated flow.
     */
    private fun DrawScope.drawPackets(f: Frame) {
        val settle = f.settle
        val charge = f.brightness
        val trains = 6 + roundHalfAway(f.intensity * 10).toInt()
        val inbound = roundHalfAway(trains * f.inboundShare).toInt()
        val outward = outboundTrainColor(f.dark)
        val back = inboundTrainColor(f.dark)
        for (m in 0 until trains) {
            val isBack = m < inbound
            val k = min(STRAND_COUNT - 1, (random(m + 211) * STRAND_COUNT).toInt())
            val pace = (0.55 + random(m + 401) * 0.45) * (if (isBack) 0.8 else 1.0)
            val length = 30 + random(m + 501) * 70
            val period = f.span + 220 + random(m + 601) * 300
            val head = fraction(f.flow * f.span * pace / period + random(m + 701)) * period
            val x0 = if (isBack) f.right - head else f.left + head
            val direction = if (isBack) 1.0 else -1.0
            val color = if (isBack) back else outward
            var q = 0.0
            while (q < length) {
                val x = x0 + direction * q
                val fadeTail = 1 - q / length
                q += 3
                if (!(x >= f.left && x <= f.right)) continue
                strand(k, x, f.axis(x), f, pointA, 0)
                val next = min(f.right, max(f.left, x + direction * 3))
                strand(k, next, f.axis(next), f, pointB, 0)
                val d = (pointA[2] + 1) / 2
                strokeLine(
                    pointA[0], pointA[1], pointB[0], pointB[1], color, 2 + 3.2 * d,
                    charge * settle * f.edgeFade(x) * (0.3 + 0.7 * d) * fadeTail.pow(0.6),
                )
            }
            if (x0 > f.left && x0 < f.right) {
                strand(k, x0, f.axis(x0), f, pointA, 0)
                val glow = if (isBack) back else if (f.dark) rgb(200, 255, 235) else rgb(6, 44, 62)
                radial(pointA[0], pointA[1], 16.0, glow, charge * settle * f.edgeFade(x0) * 0.7 * ((pointA[2] + 1) / 2))
            }
        }
    }

    private fun DrawScope.drawPressure(f: Frame) {
        val power = f.pressure * (1 - smooth(f.destruction / 0.4))
        if (!(power > 0)) return
        val s = WALL_SCALE
        val color = if (f.failure > 0.5) f.p.red else f.p.signalCyan
        radial(7 * s, -2 * s, (35 + power * 84) * s, color, power * (if (f.dark) 0.31 else 0.18))
        radial(7 * s, -2 * s, (8 + power * 17) * s, f.highlight, power * 0.72)
        line.reset()
        for (index in 0 until 9) {
            val angle = index * PI * 2 / 9 + 0.15
            val radius = power * (36 + random(index + 3) * 55) * s
            line.moveTo((7 * s).toFloat(), 0f)
            for ((reach, bend) in RAY_KINKS) {
                val x = 7 * s + cos(angle + bend) * radius * reach
                val y = sin(angle + bend) * radius * reach
                line.lineTo(x.toFloat(), y.toFloat())
            }
        }
        strokePath(line, color, 1.25, power * 0.83)
        if (f.failure > 0) radial(-100 * s, 0.0, 30 * s, f.p.red, f.failure * power * 0.4)
    }

    /**
     * Eleven rays leave real mortar joints on the lit front face, each at its
     * own moment and pace, wander, and gather into one beam on the right.
     */
    private fun DrawScope.drawSeep(f: Frame, scene: Scene) {
        if (!(f.seep > 0) || !(f.emissionFade > 0)) return
        // Stragglers keep arriving while the core is already fusing.
        val clock = f.seep * 0.7 + f.fusion * 0.3
        val endX = f.mergeX
        val endY = f.wave(f.mergeX) * 0.25
        for (seam in FirewallGeometry.seams(scene)) {
            val k = seam.index
            val ox = seam.point.x
            val oy = seam.point.y
            if (!(ox < endX - 4)) continue
            val delay = random(k + 77) * 0.42
            val pace = 0.3 + random(k + 91) * 0.28
            val progress = smooth((clock - delay) / pace)
            if (!(progress > 0)) continue
            val jitter = sin(f.flow * 14 * (1.7 + random(k) * 2.5) + k) * 9 * (1 - progress * 0.6)
            val c1x = ox + 40 + random(k + 3) * 60
            val c1y = oy + (random(k + 9) - 0.5) * 120 + jitter
            val c2x = endX - 20 - random(k + 13) * 70
            val c2y = endY + (random(k + 17) - 0.5) * 90 - jitter
            line.reset()
            for (index in 0..60) {
                // A cubic Bézier from the joint to the merge point, grown to `progress`.
                val t = index / 60.0 * progress
                val m = 1 - t
                val aa = m * m * m
                val bb = 3 * m * m * t
                val cc = 3 * m * t * t
                val dd = t * t * t
                seepX[index] = ox * aa + c1x * bb + c2x * cc + endX * dd
                seepY[index] = oy * aa + c1y * bb + c2y * cc + endY * dd
                if (index == 0) {
                    line.moveTo(seepX[index].toFloat(), seepY[index].toFloat())
                } else {
                    line.lineTo(seepX[index].toFloat(), seepY[index].toFloat())
                }
            }
            val shading = Brush.linearGradient(
                listOf(if (k % 2 == 0) f.p.signalCyan else f.p.signalViolet, f.p.green),
                start = Offset(ox.toFloat(), oy.toFloat()),
                end = Offset(endX.toFloat(), endY.toFloat()),
            )
            strokePath(line, shading, 4.5, f.emissionFade * 0.13)
            strokePath(line, shading, 1.1, f.emissionFade * 0.85)
            radial(ox, oy, 7.0, f.p.signalCyan, 0.4 * progress * f.emissionFade)
            for (index in 0 until 3) {
                val q = fraction(f.flow * 6 * (0.5 + random(k + index) * 0.5) + index * 0.31 + k * 0.05)
                if (!(q <= progress)) continue
                val position = q / max(progress, 0.0001) * 60
                val lower = min(59, position.toInt())
                val along = position - lower
                dot(
                    seepX[lower] + (seepX[lower + 1] - seepX[lower]) * along,
                    seepY[lower] + (seepY[lower + 1] - seepY[lower]) * along,
                    1.25, f.highlight, f.emissionFade,
                )
            }
        }
    }

    private fun DrawScope.drawFused(f: Frame) {
        // The fused core hands over to the braided line as it settles.
        val joined = f.fusion * (1 - f.settle)
        if (!(joined > 0)) return
        // Once the explosion starts the same unbroken core spans the whole
        // picture; flying solids, not a fake central hole, hide it.
        val start = if (f.model.breach >= Stage.BURST) f.left else f.mergeX
        sampled(line, start, f.right) { x -> f.wave(x) * 0.25 }
        for ((width, alpha) in CORE_LAYERS) strokePath(line, f.beam, width * joined, alpha * joined)
        strokePath(line, if (f.dark) rgb(225, 255, 244) else f.p.green, 1.4 * joined, 0.95 * joined)
        val count = max(6, f.model.particleCount / 2)
        val streak = if (f.dark) rgb(239, 255, 248) else f.p.green
        for (index in 0 until count) {
            val x = f.left + fraction(f.flow + random(index + 251)) * f.span
            if (!(x >= start)) continue
            sampled(line, max(start, x - 11), x) { position -> f.wave(position) * 0.25 }
            strokePath(line, streak, 2.5, joined * 0.95)
        }
        radial(f.mergeX, f.wave(f.mergeX) * 0.25, 18.0, f.p.green, joined * 0.4 * (1 - f.destruction))
    }

    private fun DrawScope.drawDebris(f: Frame) {
        val age = f.burstAge
        if (!(age > 0 && age < 2 && f.model.breach < 1)) return
        val s = WALL_SCALE
        radial(15 * s, -3 * s, (80 + age * 70) * s, f.p.signalCyan, max(0.0, 0.45 - age * 0.8))
        for (index in 0 until 85) {
            val angle = random(index + 345) * PI * 2
            val speed = 40 + random(index + 84) * 155
            val x = (cos(angle) * age * speed * 0.8 + age * 45) * s
            val y = sin(angle) * age * speed * s
            val alpha = (1 - smooth(age / 2)) * (0.25 + random(index + 71) * 0.6)
            strokeLine(
                x - cos(angle) * 7, y - sin(angle) * 7, x, y,
                if (index % 3 == 0) f.p.signalViolet else f.p.signalCyan,
                if (index % 4 == 0) 1.8 else 0.7, alpha,
            )
        }
        val ringAlpha = (1 - smooth(age / 1.25)) * 0.6
        if (ringAlpha > 0) {
            drawOval(
                f.p.signalCyan,
                topLeft = Offset(((4 - age * 51) * s).toFloat(), ((-15 - age * 133) * s).toFloat()),
                size = Size(((16 + age * 150) * s).toFloat(), ((30 + age * 266) * s).toFloat()),
                alpha = clampUnit(ringAlpha).toFloat(),
                style = roundStroke(1.0),
            )
        }
    }

    // Opaque stone and its mortar.

    /**
     * Weathered granite: a per-stone tone, a warmer lit front, cooler shaded
     * sides. Heat (the pressure, the crack front, flight) tints toward the beam.
     */
    private fun material(face: Face, pose: Pose, f: Frame): Color {
        val hot = pose.hot
        val red = f.failure * hot
        val side = face.kind.toDouble()
        if (f.dark) {
            val tone = 84 + pose.cell.seed * 30 - (if (pose.cell.isMerlon) 6 else 0)
            return when (face.kind) {
                1 -> rgb(
                    tone * 1.04 + hot * 70 + red * 80,
                    tone * 1.0 + hot * 85 - red * 40,
                    tone * 0.92 + hot * 95 - red * 55,
                )
                0 -> rgb(tone * 0.34, tone * 0.35, tone * 0.38)
                else -> rgb(
                    tone * (0.58 + side * 0.04) + hot * 25 + red * 45,
                    tone * (0.58 + side * 0.04) + hot * 35 - red * 15,
                    tone * (0.6 + side * 0.04) + hot * 45 - red * 20,
                )
            }
        }
        // Light theme: pale sandstone on the grouped ground; heat deepens toward
        // the dark cyan of the light-theme beam.
        val tone = 150 + pose.cell.seed * 35 - (if (pose.cell.isMerlon) 6 else 0)
        return when (face.kind) {
            1 -> rgb(
                tone * 1.02 - hot * 60 + red * 30,
                tone * 1.0 - hot * 15 - red * 55,
                tone * 0.95 + hot * 5 - red * 65,
            )
            0 -> rgb(tone * 0.5, tone * 0.5, tone * 0.52)
            else -> rgb(
                tone * (0.66 + side * 0.04) - hot * 30 + red * 30,
                tone * (0.66 + side * 0.04) - hot * 5 - red * 30,
                tone * (0.68 + side * 0.04) + hot * 10 - red * 40,
            )
        }
    }

    private fun DrawScope.drawArmor(f: Frame, scene: Scene) {
        val mortarFront = if (f.dark) rgb(38, 34, 30) else rgb(70, 64, 58)
        val mortarSide = if (f.dark) rgb(30, 28, 26) else rgb(60, 56, 52)
        val heat = if (f.dark) rgb(135, 225, 255) else rgb(11, 122, 153)
        val ember = if (f.dark) rgb(255, 125, 150) else rgb(192, 48, 42)
        val grain = if (f.dark) Color.Black else rgb(40, 32, 24)
        val chip = if (f.dark) rgb(255, 250, 240) else Color.White
        for (face in scene.faces) {
            val pose = scene.poses[face.pose]
            surface.reset()
            surface.addPolygon(face.points)
            // Solid stone at full strength: it never takes an effect layer's opacity.
            drawPath(surface, material(face, pose, f))
            val hot = pose.hot
            val red = f.failure * hot
            val front = face.kind == 1
            val width = if (front) 1.6 + hot * 0.7 else 0.65
            when {
                red > 0.1 -> strokePath(surface, ember, width, 0.24 + red * 0.66)
                hot > 0.2 -> strokePath(surface, heat, width, 0.24 + hot * 0.66)
                else -> strokePath(surface, if (front) mortarFront else mortarSide, width, if (front) 0.9 else 0.8)
            }
            if (!front || face.points.size != 4) continue
            // Stone grain: deterministic speckles and a chipped highlight edge.
            val p = face.points
            val cx = (0.0 + p[0].x + p[1].x + p[2].x + p[3].x) / 4
            val cy = (0.0 + p[0].y + p[1].y + p[2].y + p[3].y) / 4
            val w = abs(p[1].x - p[0].x)
            val h = abs(p[2].y - p[1].y)
            val seed = pose.cell.id * 31
            for (g in 0 until 6) {
                val gx = cx + (random(seed * 997 + g) - 0.5) * w * 0.8
                val gy = cy + (random(seed * 613 + g) - 0.5) * h * 0.7
                val rx = 1.2 + random(g + seed * 31) * 2.4
                val ry = 0.8 + random(g * 3 + seed) * 1.3
                drawOval(
                    grain,
                    topLeft = Offset((gx - rx).toFloat(), (gy - ry).toFloat()),
                    size = Size((rx * 2).toFloat(), (ry * 2).toFloat()),
                    alpha = (0.10 + random(seed * 77 + g) * 0.12).toFloat(),
                )
            }
            strokeLine(p[0].x, p[0].y, p[1].x, p[1].y, chip, 0.9, 0.14 + hot * 0.2)
            strokeLine(p[3].x, p[3].y, p[0].x, p[0].y, chip, 0.7, 0.08 + hot * 0.1)
        }
    }

    // The crack web across the whole front skin.

    /**
     * Nine trunk cracks run from the impact to the wall's edges; each spawns
     * jagged branches and hairline twigs as the front passes, so the whole wall
     * is webbed. Clipped to the standing front skins, so a crack never hangs in
     * the air where a stone has already left.
     */
    private fun DrawScope.drawCracks(f: Frame, scene: Scene) {
        val growth = f.crack
        if (!(growth > 0)) return
        mask.reset()
        var standing = false
        for (face in scene.faces) {
            if (face.kind != 1 || !(scene.poses[face.pose].scale > 0.5)) continue
            mask.addPolygon(face.points)
            standing = true
        }
        if (!standing) return
        val front = FirewallGeometry.crackFront(clampUnit(f.model.crack))
        val lift = DEPTH + f.pressure * (30 + 44 * f.model.rejectionStrain)
        val impact = Vector(0.0, 0.0, lift).projected
        val cx = impact.x * WALL_SCALE
        val cy = impact.y * WALL_SCALE
        val layers = when {
            f.failure > 0.5 -> redCrack
            f.dark -> darkCrack
            else -> lightCrack
        }
        clipPath(mask) {
            for (k in 0 until 9) {
                val angle = k * PI * 2 / 9 + 0.26 + (random(k + 61) - 0.5) * 0.3
                val full = 170 + random(k + 22) * 50
                val length = min(full, front * (0.85 + random(k + 5) * 0.3))
                if (!(length > 0)) continue
                jagged(trunk, 0.0, 0.0, angle, length, seed = k * 97, segments = TRUNK_SEGMENTS, wobble = 0.85)
                drawCrackLine(trunk, TRUNK_SEGMENTS + 1, cx, cy, layers, growth, alphaScale = 1.0, width = 1.5)
                for (branch in 1..4) {
                    val at = min(TRUNK_SEGMENTS, 1 + branch)
                    val grown = smooth((length / full * 7 - at) / 1.5)
                    if (!(grown > 0)) continue
                    val a = angle + (if (branch % 2 == 1) 0.75 else -0.8) + (random(k * 13 + branch) - 0.5) * 0.5
                    jagged(
                        twig, trunk[2 * at], trunk[2 * at + 1], a, (26 + random(k + branch * 31) * 38) * grown,
                        seed = k * 131 + branch * 17, segments = 3, wobble = 0.7,
                    )
                    drawCrackLine(twig, 4, cx, cy, layers, growth, alphaScale = 0.9, width = 0.85)
                    if (grown > 0.6) {
                        jagged(
                            leaf, twig[4], twig[5], a + (if (branch % 2 == 1) -0.9 else 0.95),
                            (10 + random(k + branch * 7) * 14) * (grown - 0.6) / 0.4,
                            seed = k * 7 + branch * 53, segments = 2, wobble = 0.6,
                        )
                        drawCrackLine(leaf, 3, cx, cy, layers, growth, alphaScale = 0.7, width = 0.5)
                    }
                }
            }
        }
    }

    /** One crack polyline ([count] points of [points]) around the impact centre, in its three strokes. */
    private fun DrawScope.drawCrackLine(
        points: DoubleArray,
        count: Int,
        cx: Double,
        cy: Double,
        layers: List<CrackLayer>,
        growth: Double,
        alphaScale: Double,
        width: Double,
    ) {
        line.reset()
        for (i in 0 until count) {
            val x = (cx + points[2 * i] * WALL_SCALE).toFloat()
            val y = (cy + points[2 * i + 1] * WALL_SCALE).toFloat()
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        for (layer in layers) {
            strokePath(line, layer.color, width * layer.width, min(1.0, growth * layer.alpha * alphaScale))
        }
    }

    companion object {
        /**
         * The tint of the packet trains coming back to the device (downloads);
         * the readout's ↓ wears it too, so the numbers name the colours.
         */
        fun inboundTrainColor(dark: Boolean): Color = if (dark) rgb(255, 207, 147) else rgb(176, 84, 0)

        /** The tint of the trains going out (uploads), and of the readout's ↑. */
        fun outboundTrainColor(dark: Boolean): Color = if (dark) Color.White else rgb(6, 44, 62)
    }
}

/** Strand colours: the ground blended toward a cyan or a mint hue by nearness. */
private fun strandColor(k: Int, nearness: Double, dark: Boolean): Color {
    val odd = k % 2 == 1
    val r0 = if (dark) 11.0 else 242.0
    val g0 = if (dark) 12.0 else 242.0
    val b0 = if (dark) 16.0 else 247.0
    val r1 = if (dark) (if (odd) 160.0 else 150.0) else (if (odd) 11.0 else 27.0)
    val g1 = if (dark) (if (odd) 230.0 else 255.0) else 122.0
    val b1 = if (dark) (if (odd) 255.0 else 215.0) else (if (odd) 153.0 else 52.0)
    val bright = 0.18 + 0.66 * nearness
    return rgb(r0 + (r1 - r0) * bright, g0 + (g1 - g0) * bright, b0 + (b1 - b0) * bright)
}

/** Orders the strand slots of [points] (x, y, z triples) far to near; equal depths keep their order. */
private fun sortByDepth(points: DoubleArray, order: IntArray) {
    for (i in order.indices) order[i] = i
    for (i in 1 until order.size) {
        val key = order[i]
        val z = points[3 * key + 2]
        var j = i - 1
        while (j >= 0 && points[3 * order[j] + 2] > z) {
            order[j + 1] = order[j]
            j--
        }
        order[j + 1] = key
    }
}

/** A jagged polyline into [into] as x, y pairs: the heading wobbles and the step length varies per segment. */
private fun jagged(
    into: DoubleArray,
    startX: Double,
    startY: Double,
    angle: Double,
    length: Double,
    seed: Int,
    segments: Int,
    wobble: Double,
) {
    into[0] = startX
    into[1] = startY
    var heading = angle
    var x = startX
    var y = startY
    for (j in 1..segments) {
        heading += (random(seed + j * 7) - 0.5) * wobble
        val r = length / segments * (0.7 + random(seed + j * 3) * 0.6)
        x += cos(heading) * r * 0.78
        y += sin(heading) * r
        into[2 * j] = x
        into[2 * j + 1] = y
    }
}

// Drawing primitives.

private fun smooth(value: Double): Double {
    val t = clampUnit(value)
    return t * t * (3 - 2 * t)
}

private fun fraction(value: Double): Double = value - floor(value)

private fun random(index: Int): Double = fraction(sin(index.toDouble() * 127.1 + 311.7) * 43758.5453)

private fun rgb(r: Double, g: Double, b: Double): Color =
    Color(clampUnit(r / 255).toFloat(), clampUnit(g / 255).toFloat(), clampUnit(b / 255).toFloat())

private fun rgb(r: Int, g: Int, b: Int): Color = rgb(r.toDouble(), g.toDouble(), b.toDouble())

/** The colour at [opacity] times its own alpha, clamped as SwiftUI's `opacity` is. */
private fun Color.withOpacity(opacity: Double): Color = copy(alpha = (alpha * clampUnit(opacity)).toFloat())

private fun roundStroke(width: Double): Stroke =
    Stroke(width = width.toFloat(), cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun Path.addPolygon(points: List<Point>) {
    if (points.isEmpty()) return
    moveTo(points[0].x.toFloat(), points[0].y.toFloat())
    for (i in 1 until points.size) lineTo(points[i].x.toFloat(), points[i].y.toFloat())
    close()
}

/**
 * Rebuilds [path] as y(x) sampled from [start] to [end], the exact end included
 * even when the span is not a whole number of steps; empty for an empty span.
 */
private inline fun sampled(path: Path, start: Double, end: Double, step: Double = 2.0, y: (Double) -> Double) {
    path.reset()
    if (!(end > start)) return
    val count = min(600, max(1, ceil((end - start) / step).toInt()))
    for (index in 0..count) {
        val x = start + (end - start) * index / count
        if (index == 0) path.moveTo(x.toFloat(), y(x).toFloat()) else path.lineTo(x.toFloat(), y(x).toFloat())
    }
}

private fun DrawScope.strokePath(path: Path, color: Color, width: Double, alpha: Double) {
    if (!(alpha > 0) || !(width > 0)) return
    drawPath(path, color, alpha = clampUnit(alpha).toFloat(), style = roundStroke(width))
}

private fun DrawScope.strokePath(path: Path, brush: Brush, width: Double, alpha: Double) {
    if (!(alpha > 0) || !(width > 0)) return
    drawPath(path, brush, alpha = clampUnit(alpha).toFloat(), style = roundStroke(width))
}

/** A two-point polyline with round caps: a line, without building a path. */
private fun DrawScope.strokeLine(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    color: Color,
    width: Double,
    alpha: Double,
) {
    if (!(alpha > 0) || !(width > 0)) return
    drawLine(
        color,
        start = Offset(x0.toFloat(), y0.toFloat()),
        end = Offset(x1.toFloat(), y1.toFloat()),
        strokeWidth = width.toFloat(),
        cap = StrokeCap.Round,
        alpha = clampUnit(alpha).toFloat(),
    )
}

private fun DrawScope.dot(x: Double, y: Double, radius: Double, color: Color, alpha: Double) {
    drawCircle(color, radius = radius.toFloat(), center = Offset(x.toFloat(), y.toFloat()), alpha = clampUnit(alpha).toFloat())
}

/** A soft glow: [color] at [alpha] in the centre, half of it a quarter of the way out, gone at [radius]. */
private fun DrawScope.radial(x: Double, y: Double, radius: Double, color: Color, alpha: Double) {
    if (!(alpha > 0) || !(radius > 0)) return
    val middle = Offset(x.toFloat(), y.toFloat())
    val reach = radius.toFloat()
    val brush = Brush.radialGradient(
        0f to color.withOpacity(alpha),
        0.25f to color.withOpacity(alpha * 0.5),
        1f to color.withOpacity(0.0),
        center = middle,
        radius = reach,
    )
    drawCircle(brush, radius = reach, center = middle)
}
