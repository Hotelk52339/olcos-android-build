package io.olcos.app.ui.hero

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The hero picture's scene geometry, the iOS app's (FirewallArmorGeometry.swift),
 * pure so it can be tested without a canvas. Units are those of the 440-point
 * reference stage; layout only scales the projection.
 *
 * The wall is stone stretcher-bond masonry: eleven courses of blocks with
 * half-block closers on the odd courses and three merlons on top. Every block
 * is a full box (front, rear and four sides), so a flying stone never shows a
 * missing face.
 */
object FirewallGeometry {
    data class Point(val x: Double, val y: Double)

    data class Vector(val x: Double, val y: Double, val z: Double) {
        val projected: Point get() = Point(x * 0.78 + z * 0.82, y + x * 0.22 - z * 0.25)
        val depth: Double get() = z * 0.78 - x * 0.6 - y * 0.08

        fun rotated(angles: Vector): Vector {
            val yy = y * cos(angles.x) - z * sin(angles.x)
            val zz = y * sin(angles.x) + z * cos(angles.x)
            val xx = x * cos(angles.y) + zz * sin(angles.y)
            val zzz = -x * sin(angles.y) + zz * cos(angles.y)
            return Vector(
                x = xx * cos(angles.z) - yy * sin(angles.z),
                y = xx * sin(angles.z) + yy * cos(angles.z),
                z = zzz,
            )
        }
    }

    data class Cell(
        val id: Int,
        /** Course index, -5…5; the merlons sit on the virtual course 6. */
        val row: Int,
        val u: Double,
        val v: Double,
        val width: Double,
        val height: Double,
        val isMerlon: Boolean,
        val radius: Double,
        val seed: Double,
    ) {
        /** Mortar joint midpoints on the front skin: right, left, top, bottom. */
        val seams: List<Point>
            get() = listOf(Point(width / 2, 0.0), Point(-width / 2, 0.0), Point(0.0, height / 2), Point(0.0, -height / 2))
    }

    /** Nominal block size, and the half depth of every stone. */
    const val BLOCK_WIDTH = 40.0
    const val BLOCK_HEIGHT = 21.0
    const val DEPTH = 22.0

    /**
     * The whole wall (and only the wall) is drawn this much larger than the
     * stage units, so the beam keeps its reference proportions.
     */
    const val WALL_SCALE = 1.15

    /** A crack length that reaches beyond the farthest block. */
    const val CRACK_REACH = 205.0
    const val SEAM_COUNT = 11

    /**
     * Stretcher bond: even courses hold seven blocks, odd courses six plus two
     * closers; three merlons crown the top. 35 + 48 + 3 = 86 stones.
     */
    val cells: List<Cell> = buildCells()

    /** Face 0 = rear, 1 = front skin, 2…5 = the sides (bottom, right, top, left). */
    val faceIndices: List<IntArray> = buildList {
        add(intArrayOf(3, 2, 1, 0))
        add(intArrayOf(4, 5, 6, 7))
        for (index in 0 until 4) add(intArrayOf(index, (index + 1) % 4, (index + 1) % 4 + 4, index + 4))
    }

    private fun buildCells(): List<Cell> {
        val result = ArrayList<Cell>(86)
        fun add(row: Int, u: Double, v: Double, width: Double, height: Double, merlon: Boolean) {
            result.add(
                Cell(
                    id = result.size, row = row, u = u, v = v, width = width, height = height,
                    isMerlon = merlon, radius = hypot(u * 0.82, v), seed = random(result.size + 1),
                ),
            )
        }
        for (row in -5..5) {
            val v = row * BLOCK_HEIGHT
            if (abs(row) % 2 == 0) {
                for (column in -3..3) {
                    add(row, column * BLOCK_WIDTH, v, BLOCK_WIDTH, BLOCK_HEIGHT, merlon = false)
                }
            } else {
                for (column in -3..2) {
                    add(row, column * BLOCK_WIDTH + BLOCK_WIDTH / 2, v, BLOCK_WIDTH, BLOCK_HEIGHT, merlon = false)
                }
                val closer = 3 * BLOCK_WIDTH + BLOCK_WIDTH / 4
                add(row, -closer, v, BLOCK_WIDTH / 2, BLOCK_HEIGHT, merlon = false)
                add(row, closer, v, BLOCK_WIDTH / 2, BLOCK_HEIGHT, merlon = false)
            }
        }
        val merlonHeight = BLOCK_HEIGHT * 1.15
        val merlonV = -6 * BLOCK_HEIGHT + (BLOCK_HEIGHT - merlonHeight) / 2
        for (column in intArrayOf(-2, 0, 2)) {
            add(6, column * BLOCK_WIDTH, merlonV, BLOCK_WIDTH * 0.8, merlonHeight, merlon = true)
        }
        return result
    }

    /**
     * The eight corners of a stone: the rear ring 0…3, the front ring 4…7, inset
     * by 0.6 so the mortar joints separate neighbours.
     */
    fun vertices(cell: Cell): List<Vector> {
        val w = cell.width / 2 - 0.6
        val h = cell.height / 2 - 0.6
        val result = ArrayList<Vector>(8)
        for (z in doubleArrayOf(-DEPTH, DEPTH)) {
            result.add(Vector(-w, -h, z))
            result.add(Vector(w, -h, z))
            result.add(Vector(w, h, z))
            result.add(Vector(-w, h, z))
        }
        return result
    }

    data class Pose(
        val cell: Cell,
        val center: Vector,
        val angles: Vector,
        val scale: Double,
        val hot: Double,
    ) {
        /** Model space to stage space (the wall's scale is applied here). */
        fun transform(point: Vector): Vector {
            val rotated = point.rotated(angles)
            return Vector(
                x = (center.x + rotated.x * scale) * FirewallGeometry.WALL_SCALE,
                y = (center.y + rotated.y * scale) * FirewallGeometry.WALL_SCALE,
                z = (center.z + rotated.z * scale) * FirewallGeometry.WALL_SCALE,
            )
        }
    }

    data class Face(
        val points: List<Point>,
        val depth: Double,
        /** 0 = rear, 1 = front skin, 2…5 = the sides. */
        val kind: Int,
        val pose: Int,
        val order: Int,
    )

    class Scene(val poses: List<Pose>, val faces: List<Face>)

    /**
     * Passing the same breach backwards exactly reverses every flight pose.
     * Rejection changes the elastic strain only; the breach stays zero on
     * failure. [crack] lights the stones the crack front has already reached.
     */
    fun scene(pressure: Double, breach: Double, rejection: Double = 0.0, crack: Double = 0.0): Scene {
        val load = clamp(pressure)
        val open = clamp(breach)
        val strain = clamp(rejection)
        val burstAge = clamp(
            (open - FirewallBeamModel.Stage.BURST) /
                (FirewallBeamModel.Stage.DESTRUCTION_END - FirewallBeamModel.Stage.BURST),
        ) * 2.6
        val front = crackFront(crack)
        // The crack glow lets go as soon as the stones start to fly.
        val calm = 1 - smooth((burstAge - 0.25) / 1)
        if (!(open < 1)) return Scene(emptyList(), emptyList())
        val poses = ArrayList<Pose>(cells.size)
        val faces = ArrayList<Face>(cells.size * 6)
        for (cell in cells) {
            val weight = exp(-cell.radius * cell.radius / 8000)
            val age = max(0.0, burstAge - cell.radius / 480 - cell.seed * 0.08)
            val fading = 1 - smooth((age - 0.9) / 1.2)
            if (!(fading > 0.002)) continue
            // Erode flying solids instead of blending single faces, which would
            // reveal rear edges and light through stones that are still there.
            val stretch = 1 + load * weight * (0.055 + 0.105 * strain)
            val displacement = load * weight * (30 + 44 * strain)
            val flight = 1 - exp(-age * 1.9)
            val velocity = 80 + random(cell.id + 52) * 75
            val center = Vector(
                x = cell.u * stretch + flight * cell.u * 0.9,
                y = cell.v * stretch + flight * cell.v * 0.72 + age * age * 12,
                z = displacement + flight * velocity + age * 33,
            )
            val angles = Vector(
                x = age * (random(cell.id + 23) - 0.5) * 3,
                y = age * (random(cell.id + 32) - 0.5) * 3,
                z = age * (random(cell.id + 44) - 0.5) * 2.6,
            )
            val reach = smooth((front - cell.radius) / 34) * calm
            val hot = maxOf(load * weight * calm, reach * 0.42, if (age > 0) max(0.0, 1 - age) * 0.75 else 0.0)
            val pose = Pose(cell, center, angles, scale = fading, hot = hot)
            val poseIndex = poses.size
            poses.add(pose)
            val transformed = vertices(cell).map { pose.transform(it) }
            for ((kind, indices) in faceIndices.withIndex()) {
                val corners = indices.map { transformed[it] }
                faces.add(
                    Face(
                        points = corners.map { it.projected },
                        depth = corners.sumOf { it.depth } / corners.size,
                        kind = kind,
                        pose = poseIndex,
                        order = cell.id * 6 + kind,
                    ),
                )
            }
        }
        // One painter's list for ALL faces, not one sort per block.
        faces.sortWith(
            Comparator<Face> { a, b ->
                when {
                    a.depth == b.depth -> a.order.compareTo(b.order)
                    a.depth < b.depth -> -1
                    else -> 1
                }
            },
        )
        return Scene(poses, faces)
    }

    /** Radius (model units) the crack web has reached for a 0…1 crack growth. */
    fun crackFront(crack: Double): Double = smooth(crack) * CRACK_REACH

    /**
     * Eleven mortar joints on the front skin of the right half of the wall,
     * where light seeps through. Chosen deterministically but scattered, so the
     * leaks look accidental; every anchor lies on a stone that is there.
     */
    data class Seam(val index: Int, val cell: Int, val point: Point)

    fun seams(scene: Scene): List<Seam> {
        val candidates = scene.poses.indices.filter { index ->
            val cell = scene.poses[index].cell
            cell.u > 0 && abs(cell.v) <= 120 && !cell.isMerlon
        }
        if (candidates.isEmpty()) return emptyList()
        return (0 until SEAM_COUNT).map { index ->
            val pick = candidates[min(candidates.size - 1, (random(index * 7 + 3) * candidates.size).toInt())]
            val pose = scene.poses[pick]
            val seam = pose.cell.seams[min(3, (random(index + 41) * 4).toInt())]
            val point = pose.transform(Vector(seam.x, seam.y, DEPTH)).projected
            Seam(index = index, cell = pick, point = point)
        }
    }

    fun clamp(value: Double): Double = if (value.isFinite()) min(1.0, max(0.0, value)) else 0.0

    fun smooth(value: Double): Double {
        val t = clamp(value)
        return t * t * (3 - 2 * t)
    }

    fun random(index: Int): Double {
        val value = sin(index.toDouble() * 127.1 + 311.7) * 43758.5453
        return value - floor(value)
    }
}
