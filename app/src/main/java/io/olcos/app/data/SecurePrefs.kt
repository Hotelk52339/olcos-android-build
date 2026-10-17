package io.olcos.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.olcos.app.core.AppLog

/**
 * The preferences file a bearer credential lives in, encrypted at rest with
 * a key the Android keystore keeps (EncryptedSharedPreferences). The first
 * open moves a value the plain predecessor kept over and wipes that file,
 * so an update from an earlier install is invisible; if the encrypted store
 * cannot be opened at all (a backup restored onto another keystore), the
 * plain file stands in rather than the sign-in being lost.
 */
internal object SecurePrefs {
    private const val LOG_TAG = "olcos.sessions"

    fun open(context: Context, name: String, key: String): SharedPreferences {
        val plain = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val secure = runCatching {
            val master = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                context,
                name + ".secure",
                master,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            AppLog.w(LOG_TAG, "the encrypted store for $name could not be opened; the plain file stands in")
            return plain
        }
        // Once only: carry a plain value over, then wipe where it came from.
        val legacy = plain.getString(key, null)
        if (!legacy.isNullOrEmpty() && secure.getString(key, null) == null) {
            secure.edit().putString(key, legacy).commit()
            plain.edit().clear().commit()
        }
        return secure
    }
}
