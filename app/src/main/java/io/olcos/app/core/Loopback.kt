package io.olcos.app.core

import java.net.InetAddress

/**
 * 127.0.0.1, where every local listener of the app listens: the Go cores, the
 * bond client and the lane joiner. Android's InetAddress.getLoopbackAddress()
 * answers ::1, where none of them does, and a connection there is refused at
 * once: the traffic check failed that way on every room, a moment after the
 * room answered.
 */
object Loopback {
    val address: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
}
