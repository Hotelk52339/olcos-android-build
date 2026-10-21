package io.olcos.app.core

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.Socket

/** The client side of a SOCKS5 CONNECT by name, with the login the listener may ask for (RFC 1928, 1929). */
object Socks5 {
    fun connect(socket: Socket, host: String, port: Int, user: String = "", pass: String = "") {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        val withLogin = user.isNotEmpty()
        out.write(if (withLogin) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        val greeting = readFully(input, 2)
        if (greeting[0] != 5.toByte()) throw IOException("not a SOCKS5 listener")
        when (greeting[1].toInt()) {
            0 -> {}
            2 -> {
                if (!withLogin) throw IOException("the proxy asks for a login")
                val u = user.toByteArray(Charsets.UTF_8)
                val p = pass.toByteArray(Charsets.UTF_8)
                out.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(p.size.toByte()) + p)
                out.flush()
                val auth = readFully(input, 2)
                if (auth[1] != 0.toByte()) throw IOException("the proxy refused the login")
            }
            else -> throw IOException("SOCKS refused")
        }
        val name = host.toByteArray(Charsets.US_ASCII)
        out.write(byteArrayOf(5, 1, 0, 3, name.size.toByte()) + name + byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte()))
        out.flush()
        val reply = readFully(input, 4)
        if (reply[1] != 0.toByte()) throw IOException("SOCKS connect failed (${reply[1]})")
        when (reply[3].toInt()) {
            1 -> readFully(input, 6)
            3 -> readFully(input, (readFully(input, 1)[0].toInt() and 0xFF) + 2)
            4 -> readFully(input, 18)
            else -> throw IOException("SOCKS reply malformed")
        }
    }

    fun readFully(input: InputStream, n: Int): ByteArray {
        val buffer = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buffer, off, n - off)
            if (r < 0) throw EOFException()
            off += r
        }
        return buffer
    }
}
