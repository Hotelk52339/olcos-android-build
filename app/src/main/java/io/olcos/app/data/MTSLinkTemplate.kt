package io.olcos.app.data

import android.content.Context
import android.content.SharedPreferences
import io.olcos.app.core.AppLog
import io.olcos.app.core.MtsLearn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the app makes an MTS Link meeting, learned from the owner's own action
 * in the sign-in sheet (MtsLearn.Template): the request as the page sent it,
 * with each credential header kept as where its value comes from and every
 * value that was a cookie or storage item as a marker. It can still carry the
 * owner's own ids, so it lives encrypted at rest (SecurePrefs), like the
 * sessions. A newer lesson replaces it; "Forget what the app learned" drops
 * it. The first open also moves over, and drops, the older plain template: it
 * held a scrubbed body that would replay as blanks.
 */
class MTSLinkTemplate(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(SecurePrefs.open(context, NAME, KEY))

    init {
        // SecurePrefs carries a plain value over as it is; an older one (or any unreadable one) is not a lesson any more.
        if (prefs.getString(KEY, null) != null && MtsLearn.decode(prefs.getString(KEY, null)) == null) {
            prefs.edit().remove(KEY).apply()
            AppLog.i("olcos.mtslearn", "an older template was dropped (it cannot be replayed faithfully); a new lesson is needed")
        }
        // The lessons before the meeting check accepted quick meetings, so a template kept from then makes rooms the server cannot enter.
        val flagged = prefs.getBoolean(QUICK_DROP_FLAG, false)
        if (dropsOnOpen(prefs.getString(KEY, null) != null, flagged)) {
            prefs.edit().remove(KEY).apply()
            AppLog.i("olcos.mtslearn", "the saved template was dropped: it was learned before meetings were checked and makes quick meetings, " +
                "which the server cannot enter; the next \"Make a room\" runs the lesson again")
        }
        if (!flagged) prefs.edit().putBoolean(QUICK_DROP_FLAG, true).apply()
    }

    /** The learned template; null when nothing is learned. */
    val template: MtsLearn.Template? get() = MtsLearn.decode(prefs.getString(KEY, null))

    private val _learned = MutableStateFlow(template != null)
    /** Whether the app has learned how a meeting is made, for the screens that say so. */
    val learned: StateFlow<Boolean> = _learned

    fun save(template: MtsLearn.Template) {
        prefs.edit().putString(KEY, MtsLearn.encode(template)).apply()
        _learned.value = true
    }

    fun forget() {
        prefs.edit().remove(KEY).apply()
        _learned.value = false
    }

    companion object {
        private const val NAME = "olcos.mtstemplate"
        private const val KEY = "template"
        /** Set once the templates kept before the meeting check have been dropped. */
        const val QUICK_DROP_FLAG = "droppedQuickTemplates"

        /** PURE: whether opening the store drops the kept template: once, for a template kept before the flag existed. */
        fun dropsOnOpen(hasTemplate: Boolean, flagged: Boolean): Boolean = hasTemplate && !flagged
    }
}
