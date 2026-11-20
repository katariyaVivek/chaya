package com.chaya.app.ai

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** The AI model's key sealed by a key in the real Android Keystore, which Robolectric does not have. */
@RunWith(AndroidJUnit4::class)
class KeystoreLockTest {
    private val alias = "chaya_ai_keys_test"
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @After
    fun tearDown() {
        KeyStore.getInstance(KeystoreLock.KEYSTORE).apply { load(null) }.deleteEntry(alias)
        context.deleteSharedPreferences("chaya_ai_test")
    }

    @Test
    fun aKeyIsSealedByTheKeystoreAndOpenedAgain() {
        val lock = KeystoreLock(alias)
        val sealed = lock.seal("sk-device-test".toByteArray())
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("sk-device-test"))
        assertEquals("sk-device-test", lock.open(sealed)?.toString(Charsets.UTF_8))
        // A second lock with the same alias uses the same Keystore key, as the app does after a restart.
        assertEquals("sk-device-test", KeystoreLock(alias).open(sealed)?.toString(Charsets.UTF_8))

        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(lock.open(tampered))

        val keys = AiKeys(context.getSharedPreferences("chaya_ai_test", Context.MODE_PRIVATE), lock)
        assertNull(keys.setKey("openai", "sk-kept"))
        assertEquals("sk-kept", keys.key("openai"))
    }
}
