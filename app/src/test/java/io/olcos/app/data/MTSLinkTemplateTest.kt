package io.olcos.app.data

import android.content.SharedPreferences
import io.olcos.app.core.MtsLearn
import io.olcos.app.core.MtsRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The learned template is kept through a SharedPreferences it is handed:
 * the encrypted file in the app (SecurePrefs), an in-memory one here. What it
 * writes there is the encoded text, which the encrypted store then keeps
 * encrypted at rest; the encryption itself is the platform's and has no JVM
 * seam.
 */
class MTSLinkTemplateTest {
    private val template = MtsLearn.Template(
        "POST", "https://gw.mts-link.ru/api/events/6f9619ff-8b86-d011-b42d-00c04fc964ff", "application/json",
        MtsRequest.Body.Text("{\"owner\":\"@cookie(user_hash)\"}", true),
        listOf(MtsLearn.Header("Authorization", MtsLearn.Source.Cookie("access", "Bearer "))),
        MtsLearn.Kind.EVENT, listOf(MtsLearn.Seg.Key("data"), MtsLearn.Seg.Key("id")), "100200300", 7L,
    )

    @Test fun aSavedTemplateComesBackFromTheSameStore() {
        val prefs = MemoryPrefs()
        val store = MTSLinkTemplate(prefs)
        assertFalse(store.learned.value)
        assertNull(store.template)
        store.save(template)
        assertTrue(store.learned.value)
        assertEquals(template, store.template)
        // A new process over the same file.
        val again = MTSLinkTemplate(prefs)
        assertTrue(again.learned.value)
        assertEquals(template, again.template)
    }

    @Test fun whatIsWrittenIsTheEncodedTextAndNoCookieOrTokenValue() {
        val prefs = MemoryPrefs()
        MTSLinkTemplate(prefs).save(template)
        val kept = prefs.values["template"] as String
        assertEquals(MtsLearn.encode(template), kept)
        assertTrue(kept.contains("@cookie(user_hash)"))
        // Besides the template, only the one-time flag is kept.
        assertEquals(setOf("template", MTSLinkTemplate.QUICK_DROP_FLAG), prefs.values.keys)
    }

    @Test fun forgettingDropsIt() {
        val prefs = MemoryPrefs()
        val store = MTSLinkTemplate(prefs)
        store.save(template)
        store.forget()
        assertFalse(store.learned.value)
        assertNull(store.template)
        assertFalse(prefs.values.containsKey("template"))
        assertNull(MTSLinkTemplate(prefs).template)
    }

    @Test fun anOlderTemplateCarriedOverFromThePlainFileIsDropped() {
        val old = "{\"v\":1,\"method\":\"POST\",\"url\":\"https://gw.mts-link.ru/api/events\",\"contentType\":\"application/json\"," +
            "\"body\":\"{}\",\"headers\":[],\"result\":\"event\",\"path\":[\"data\",\"id\"],\"owner\":\"1\",\"learnedAt\":1}"
        val prefs = MemoryPrefs().apply { values["template"] = old }
        val store = MTSLinkTemplate(prefs)
        assertFalse(store.learned.value)
        assertNull(store.template)
        assertFalse(prefs.values.containsKey("template"))
    }

    @Test fun aTemplateKeptBeforeThisVersionIsDroppedOnceItWasLearnedFromAQuickMeeting() {
        // A readable template of the current format, kept by a build that never checked the meeting's kind.
        val prefs = MemoryPrefs().apply { values["template"] = MtsLearn.encode(template) }
        val store = MTSLinkTemplate(prefs)
        assertFalse(store.learned.value)
        assertNull(store.template)
        assertFalse(prefs.values.containsKey("template"))
        assertEquals(true, prefs.values[MTSLinkTemplate.QUICK_DROP_FLAG])
    }

    @Test fun theDropHappensOnceSoANewLessonIsKept() {
        val prefs = MemoryPrefs().apply { values["template"] = MtsLearn.encode(template) }
        MTSLinkTemplate(prefs).save(template)
        // The next process over the same file keeps what the new lesson saved.
        val again = MTSLinkTemplate(prefs)
        assertTrue(again.learned.value)
        assertEquals(template, again.template)
    }

    @Test fun aFreshInstallSetsTheFlagWithNothingToDrop() {
        val prefs = MemoryPrefs()
        val store = MTSLinkTemplate(prefs)
        assertNull(store.template)
        assertEquals(true, prefs.values[MTSLinkTemplate.QUICK_DROP_FLAG])
    }

    @Test fun theDecisionToDrop() {
        assertTrue(MTSLinkTemplate.dropsOnOpen(hasTemplate = true, flagged = false))
        assertFalse(MTSLinkTemplate.dropsOnOpen(hasTemplate = true, flagged = true))
        assertFalse(MTSLinkTemplate.dropsOnOpen(hasTemplate = false, flagged = false))
        assertFalse(MTSLinkTemplate.dropsOnOpen(hasTemplate = false, flagged = true))
    }

    @Test fun aNewerLessonReplacesTheOlderOne() {
        val prefs = MemoryPrefs()
        val store = MTSLinkTemplate(prefs)
        store.save(template)
        val newer = template.copy(learnedAt = 99L, url = "https://gw.mts-link.ru/api/v2/events")
        store.save(newer)
        assertEquals(newer, MTSLinkTemplate(prefs).template)
    }
}

/** A SharedPreferences in memory: what the store needs of one, and no more. */
class MemoryPrefs : SharedPreferences {
    val values = LinkedHashMap<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val removed = HashSet<String>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
