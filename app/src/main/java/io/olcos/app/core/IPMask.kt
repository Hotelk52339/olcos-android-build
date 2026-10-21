package io.olcos.app.core

/**
 * Display-only masking of IP addresses, for screenshots (Settings → Hide IP
 * addresses), as the iOS app's IPMask: the last part stays as a hint and the
 * rest becomes bullets, `203.0.113.12` → `•••.•••.•••.12`; an IPv6 address
 * keeps its last group. Anything that is not an address passes unchanged, and
 * stored values, copies and logs stay real.
 */
object IPMask {
    private const val DOT = "•••"

    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
    private val ipv6 = Regex("""(?<![\w:])(?:[0-9A-Fa-f]{0,4}:){2,7}[0-9A-Fa-f]{1,4}(?![\w:])""")

    /** [ip] masked when it is an IPv4 or IPv6 literal; otherwise as it is. */
    fun mask(ip: String): String {
        val s = ip.trim()
        if (s.isEmpty()) return ip
        if (':' in s) {
            val groups = s.split(':')
            val last = groups.last()
            if (groups.size < 3 || last.isEmpty()) return ip
            return "$DOT:$last"
        }
        val octets = s.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || !it.all { c -> c in '0'..'9' } }) return ip
        return "$DOT.$DOT.$DOT.${octets[3]}"
    }

    fun display(ip: String, masked: Boolean): String = if (masked) mask(ip) else ip

    /** Every address inside [text] masked, when [masked]: the exit line "Amsterdam, NL · 203.0.113.12". */
    fun displayAll(text: String, masked: Boolean): String {
        if (!masked) return text
        val v4 = ipv4.replace(text) { mask(it.value) }
        return ipv6.replace(v4) { mask(it.value) }
    }
}
