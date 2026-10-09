package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.config
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SensitivePrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun unreadableCiphertextIsDistinctFromAMissingKey() {
        val prefs = context.getSharedPreferences("read-test", Context.MODE_PRIVATE)
        prefs.edit().putString("private", "enc:v1:broken").commit()
        assertEquals(SensitivePrefs.ReadResult.Missing, SensitivePrefs.readString(prefs, "missing"))
        assertEquals(SensitivePrefs.ReadResult.Unavailable, SensitivePrefs.readString(prefs, "private"))
        assertThrows(SensitivePrefs.UnavailableException::class.java) { SensitivePrefs.getString(prefs, "private") }
        assertEquals("enc:v1:broken", prefs.getString("private", null))
    }

    @Test fun failedEncryptionDoesNotWritePlaintextOrHalfAKeyPair() {
        val prefs = context.getSharedPreferences("write-test", Context.MODE_PRIVATE)
        prefs.edit().putString("public", "old-public").putString("private", "old-private").commit()
        assertThrows(SensitivePrefs.UnavailableException::class.java) {
            SensitivePrefs.putStrings(prefs, mapOf("public" to "new-public", "private" to "new-private")) {
                if (it == "new-public") "encrypted-public" else null
            }
        }
        assertEquals("old-public", prefs.getString("public", null))
        assertEquals("old-private", prefs.getString("private", null))
    }

    @Test fun openingMessagesDoesNotReplaceAnUnreadableIdentity() {
        val field = org.fossify.commons.helpers.BaseConfig::class.java.getDeclaredField("prefs").apply { isAccessible = true }
        val prefs = field.get(context.config) as android.content.SharedPreferences
        prefs.edit().putString(E2E_PUBLIC_KEY, "enc:v1:broken-public").putString(E2E_PRIVATE_KEY, "enc:v1:broken-private").commit()
        assertFalse(E2eManager.ensureKeyPair(context))
        assertEquals("enc:v1:broken-public", prefs.getString(E2E_PUBLIC_KEY, null))
        assertEquals("enc:v1:broken-private", prefs.getString(E2E_PRIVATE_KEY, null))
    }
}
