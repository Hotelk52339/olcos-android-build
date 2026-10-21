package io.olcos.app

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The English and the Russian string files carry the same keys, and a
 * string takes the same placeholders in both. A key missing from values-ru
 * shows English there without a word of warning, and a placeholder that
 * differs between the languages throws or prints wrongly in one of them.
 * The files are read as text, since no resource system runs in a JVM test;
 * where they cannot be found from the working directory, the checks skip.
 */
class StringsParityTest {
    private val keyPattern = Regex("""<(string|plurals)\s+name="([^"]+)"""")
    private val elementPattern = Regex("""<(string|plurals)\s+name="([^"]+)"[^>]*>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val placeholderPattern = Regex("""%(?:\d+\$)?[-#+0,(]*\d*(?:\.\d+)?[sdf]""")

    /** The text of a values folder's strings.xml, found from the module or the repository root; null when neither holds it. */
    private fun read(folder: String): String? =
        listOf("src/main/res/$folder/strings.xml", "app/src/main/res/$folder/strings.xml")
            .map(::File).firstOrNull { it.isFile }?.readText(Charsets.UTF_8)

    private fun keys(text: String): List<String> = keyPattern.findAll(text).map { it.groupValues[1] + ":" + it.groupValues[2] }.toList()

    /** key → its placeholders, sorted, so their kinds and positions count and the order the words put them in does not. */
    private fun placeholders(text: String): Map<String, List<String>> =
        elementPattern.findAll(text).associate { match ->
            val holders = placeholderPattern.findAll(match.groupValues[3]).map { it.value }.distinct().sorted().toList()
            (match.groupValues[1] + ":" + match.groupValues[2]) to holders
        }

    @Test fun bothLocalesHaveTheSameKeys() {
        val en = read("values")
        val ru = read("values-ru")
        assumeTrue("the string files are not reachable from here", en != null && ru != null)
        val enKeys = keys(en!!).toSet()
        val ruKeys = keys(ru!!).toSet()
        assertEquals("only in values", emptySet<String>(), enKeys - ruKeys)
        assertEquals("only in values-ru", emptySet<String>(), ruKeys - enKeys)
    }

    @Test fun noKeyIsDefinedTwiceInOneFile() {
        for (folder in listOf("values", "values-ru")) {
            val text = read(folder) ?: continue
            val twice = keys(text).groupBy { it }.filterValues { it.size > 1 }.keys
            assertEquals("$folder defines a key twice", emptySet<String>(), twice)
        }
    }

    @Test fun aStringTakesTheSamePlaceholdersInBothLocales() {
        val en = read("values")
        val ru = read("values-ru")
        assumeTrue("the string files are not reachable from here", en != null && ru != null)
        val ruHolders = placeholders(ru!!)
        val differing = placeholders(en!!).filter { (key, holders) -> ruHolders[key] != null && ruHolders[key] != holders }.keys
        assertEquals("placeholders differ between the locales", emptySet<String>(), differing)
    }
}
