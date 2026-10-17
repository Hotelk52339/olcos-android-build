package io.olcos.app.model

import java.net.URI

/**
 * Which destinations skip the tunnel when Routing is set to the list: the
 * ready-made list of Russian sites (the country's own zones and the Russian
 * services on other zones) and the user's own lines, one site, link, address
 * or network per line. Everything reaches the engines as one text in the
 * olcrtc core's direct-list syntax (`domain:<name>` for a site and everything
 * under it, `full:<name>`, an address or a CIDR prefix), which the core, the
 * bond client and Xray all read. The same list and rules as the iOS DirectRoutes.
 */
object DirectRoutes {
    val russian: List<String> = listOf(
        // The zones: .ru, .su, .рф, .дети, .москва, .рус, .онлайн, .сайт, .орг
        "domain:ru", "domain:su", "domain:xn--p1ai", "domain:xn--d1acj3b", "domain:xn--80adxhks",
        "domain:xn--p1acf", "domain:xn--80asehdb", "domain:xn--80aswg", "domain:xn--c1avg",
        // Yandex
        "domain:yandex.com", "domain:yandex.net", "domain:yastatic.net", "domain:yandex.cloud",
        "domain:yandexcloud.net",
        // VK, Odnoklassniki, Mail
        "domain:vk.com", "domain:vk.me", "domain:vk.link", "domain:vk.cc", "domain:vkuser.net",
        "domain:vkuseraudio.net", "domain:vkuservideo.net", "domain:userapi.com", "domain:vk-cdn.net",
        "domain:vk-portal.net", "domain:mycdn.me", "domain:ok.me",
        // 2GIS, Wildberries, Avito
        "domain:2gis.com", "domain:wbstatic.net", "domain:avito.st",
    )

    data class Parsed(val rules: List<String>, val badLine: Int?)

    /** PURE. The text the engines take; empty when everything goes through the tunnel. */
    fun effective(listDirect: Boolean, useRussian: Boolean, own: String): String {
        if (!listDirect) return ""
        val rules = if (useRussian) russian.toMutableList() else mutableListOf()
        for (rule in parse(own).rules) if (rule !in rules) rules += rule
        return rules.joinToString("\n")
    }

    /** PURE. The user's own lines in the core's syntax; the first line that is neither a site nor an address. */
    fun parse(text: String): Parsed {
        val rules = mutableListOf<String>()
        var badLine: Int? = null
        text.lines().forEachIndexed { index, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val rule = rule(line)
            if (rule != null) rules += rule else if (badLine == null) badLine = index + 1
        }
        return Parsed(rules, badLine)
    }

    private fun rule(line: String): String? {
        val lower = line.lowercase()
        for (prefix in listOf("domain:", "full:")) {
            if (lower.startsWith(prefix)) {
                val name = lower.substring(prefix.length)
                return if (isName(name)) prefix + trimmedName(name) else null
            }
        }
        if (isAddressOrNetwork(lower)) return lower
        var name = lower
        if (lower.contains("://")) {
            runCatching { URI(lower) }.getOrNull()?.host?.let { name = it }
        }
        if (name.startsWith("*.")) name = name.substring(2)
        return if (isName(name)) "domain:" + trimmedName(name) else null
    }

    private fun trimmedName(name: String): String = name.removeSuffix(".")

    /** An ASCII host name: letters, digits, hyphens, no empty label. */
    private fun isName(raw: String): Boolean {
        val name = trimmedName(raw)
        if (name.isEmpty() || name.length > 253) return false
        return name.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 && !label.startsWith("-") && !label.endsWith("-") &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }

    private fun isAddressOrNetwork(text: String): Boolean {
        val parts = text.split('/')
        if (parts.size > 2 || parts[0].isEmpty()) return false
        val address = parts[0]
        val v4 = isIPv4(address)
        val v6 = !v4 && isIPv6(address)
        if (!v4 && !v6) return false
        if (parts.size == 1) return true
        val bits = parts[1].toIntOrNull() ?: return false
        return bits in 0..(if (v4) 32 else 128)
    }

    private fun isIPv4(text: String): Boolean {
        val octets = text.split('.')
        return octets.size == 4 && octets.all { o -> o.isNotEmpty() && o.length <= 3 && o.all { it.isDigit() } && o.toInt() <= 255 }
    }

    private fun isIPv6(text: String): Boolean {
        if (!text.contains(':')) return false
        if (!text.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return false
        val groups = text.split(':')
        return groups.size in 3..8 && text.count { it == ':' } <= 7 && groups.all { it.length <= 4 || it.contains('.') }
    }
}
