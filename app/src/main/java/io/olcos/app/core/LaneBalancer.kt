package io.olcos.app.core

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Which lane takes the next connection: round robin over the lanes that are
 * up and not benched; when none qualifies, over the ones that are up; when
 * none is up, still in turn. A lane is benched for a while after it twice in
 * a row returned a connection that died with almost nothing sent back: a room
 * that lost its server still accepts SOCKS, but every CONNECT through it fails.
 */
class LanePolicy(count: Int) {
    val count: Int = count.coerceAtLeast(1)
    private var next = 0
    private val failures = IntArray(this.count)
    private val benchedUntil = LongArray(this.count)

    @Synchronized
    fun pick(isUp: (Int) -> Boolean, now: Long): Int {
        val order = (0 until count).map { (next + it) % count }
        val rested = order.firstOrNull { isUp(it) && benchedUntil[it] <= now }
        val lane = rested ?: order.firstOrNull(isUp) ?: order[0]
        next = (lane + 1) % count
        return lane
    }

    @Synchronized
    fun record(lane: Int, dead: Boolean, now: Long) {
        if (lane !in 0 until count) return
        if (dead) {
            failures[lane]++
            if (failures[lane] >= 2) {
                benchedUntil[lane] = now + BENCH_MS
                failures[lane] = 0
            }
        } else {
            failures[lane] = 0
            benchedUntil[lane] = 0
        }
    }

    companion object {
        const val BENCH_MS = 20_000L

        /** Closed within a few seconds with no more than a SOCKS reply's worth of bytes back. */
        fun looksDead(bytesBack: Long, durationMs: Long): Boolean = bytesBack <= 32 && durationMs < 5_000
    }
}

/**
 * Spreads one tunnel's connections over several rooms (lanes) when the server
 * runs no bond endpoint. Each lane is its own core runtime with its own
 * loopback SOCKS listener; this listens on the port apps use and hands every
 * new connection to the next lane that is up, byte for byte, so the SOCKS
 * handshake itself goes through to the lane. The same policy as the iOS
 * LaneBalancer.
 */
class LaneBalancer(
    private val port: Int,
    private val lanePorts: List<Int>,
    private val isUp: (Int) -> Boolean,
) {
    private val policy = LanePolicy(lanePorts.size)
    @Volatile private var server: ServerSocket? = null

    /** Throws when the port is taken. */
    fun start() {
        val listener = ServerSocket(port, 64, Loopback.address)
        server = listener
        thread(name = "olcos-balancer", isDaemon = true) {
            while (true) {
                val client = try {
                    listener.accept()
                } catch (e: Exception) {
                    break
                }
                thread(name = "olcos-lane-conn", isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(client: Socket) {
        val lane = policy.pick(isUp, System.currentTimeMillis())
        val started = System.currentTimeMillis()
        val upstream = try {
            Socket(Loopback.address, lanePorts[lane])
        } catch (e: Exception) {
            runCatching { client.close() }
            policy.record(lane, true, System.currentTimeMillis())
            return
        }
        runCatching { client.tcpNoDelay = true }
        runCatching { upstream.tcpNoDelay = true }
        var back = 0L
        val down = thread(name = "olcos-lane-down", isDaemon = true) {
            back = pump(upstream.getInputStream(), client.getOutputStream())
            runCatching { client.shutdownOutput() }
        }
        pump(client.getInputStream(), upstream.getOutputStream())
        runCatching { upstream.shutdownOutput() }
        down.join()
        runCatching { client.close() }
        runCatching { upstream.close() }
        val now = System.currentTimeMillis()
        policy.record(lane, LanePolicy.looksDead(back, now - started), now)
    }

    private fun pump(from: InputStream, to: OutputStream): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        try {
            while (true) {
                val n = from.read(buffer)
                if (n < 0) break
                to.write(buffer, 0, n)
                to.flush()
                total += n
            }
        } catch (e: Exception) {
            // The other side went away; the caller closes both sockets.
        }
        return total
    }
}
