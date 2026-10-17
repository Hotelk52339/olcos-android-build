package io.olcos.app.data

/**
 * The `name=value; name=value` line a web view's jar hands over
 * (`CookieManager.getCookie`), read without a parser, and the checks that
 * decide which of its pairs are fit to go out again in a `Cookie` header.
 */
object CookieLine {
    private const val NAME_SEPARATORS = "()<>@,;:\\\"/[]?={}"

    /** The pairs of a jar line in order, names and values trimmed; a part with no `=` or no name is not a pair. */
    fun pairs(line: String?): List<Pair<String, String>> {
        if (line.isNullOrBlank()) return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (part in line.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val name = part.substring(0, eq).trim()
            if (name.isEmpty()) continue
            out.add(name to part.substring(eq + 1).trim())
        }
        return out
    }

    /** A cookie name as RFC 6265 has it, a token: printable ASCII but for the separators `( ) < > @ , ; : \ " / [ ] ? = { }`. */
    fun safeName(name: String): Boolean =
        name.isNotEmpty() && name.all { it in '!'..'~' && it !in NAME_SEPARATORS }

    /**
     * A cookie value as RFC 6265 has it: printable ASCII but for `"`, `,`, `;`
     * and `\`. No control character and no space, so a value that passes
     * cannot break out of its header line.
     */
    fun safeValue(value: String): Boolean =
        value.all { it in '!'..'~' && it != '"' && it != ',' && it != ';' && it != '\\' }
}
