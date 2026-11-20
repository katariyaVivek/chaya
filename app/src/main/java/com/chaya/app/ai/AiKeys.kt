package com.chaya.app.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals and opens small secrets. On the phone, with a key that never leaves the Android Keystore ([KeystoreLock]). */
interface KeyLock {
    /** The secret sealed: the cipher's nonce, then the text. */
    fun seal(plain: ByteArray): ByteArray

    /** The secret, or null when it cannot be opened (the Keystore's key is gone, or it was changed). */
    fun open(sealed: ByteArray): ByteArray?
}

/** AES-GCM with a key made in the Android Keystore, which can be used but never read out, even by Chaya. */
class KeystoreLock(private val alias: String = ALIAS) : KeyLock {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray = gcmSeal(key(), plain)

    override fun open(sealed: ByteArray): ByteArray? = try {
        gcmOpen(key(), sealed)
    } catch (_: GeneralSecurityException) {
        null
    }

    companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "chaya_ai_keys"
    }
}

/** AES-GCM with [key]: a fresh 12-byte nonce, then the sealed text and its tag. */
internal fun gcmSeal(key: SecretKey, plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    val nonce = cipher.iv
    return nonce + cipher.doFinal(plain)
}

/** Opens what [gcmSeal] sealed; throws when it was changed or sealed with another key. */
internal fun gcmOpen(key: SecretKey, sealed: ByteArray): ByteArray? {
    if (sealed.size <= NONCE_BYTES) return null
    val cipher = Cipher.getInstance(TRANSFORMATION)
    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, NONCE_BYTES))
    return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
}

private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val NONCE_BYTES = 12

/**
 * Settings › AI model, kept: what the person chose, and each service's key, sealed by [lock]. Both live in their own
 * preferences file, which `backup_rules.xml` and `data_extraction_rules.xml` leave out of backups and transfers to a
 * new phone (the Keystore's key could not open them there anyway). A key is never kept in plain text.
 */
class AiKeys(private val prefs: SharedPreferences, private val lock: KeyLock) {

    var settings: AiSettings
        get() = AiSettings.fromJson(prefs.getString(SETTINGS, null))
        set(value) = prefs.edit { putString(SETTINGS, value.toJson()) }

    /** `service`'s key, or null when none is kept or it can no longer be opened. */
    fun key(service: String): String? {
        val sealed = prefs.getString(KEY_PREFIX + service, null) ?: return null
        val bytes = runCatching { Base64.decode(sealed, Base64.NO_WRAP) }.getOrNull() ?: return null
        return lock.open(bytes)?.toString(Charsets.UTF_8)
    }

    fun hasKey(service: String): Boolean = key(service) != null

    /** Keeps `key` for `service` in place of any before it; why not, in words, when it doesn't look like one. */
    fun setKey(service: String, key: String): String? {
        AiService.find(service)?.takeIf { it.key } ?: return "This service takes no key."
        val trimmed = key.trim()
        if (trimmed.isEmpty() || trimmed.length > 4096 || trimmed.any(Char::isWhitespace)) {
            return "That doesn't look like a key. Paste it again."
        }
        val sealed = try {
            lock.seal(trimmed.toByteArray(Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            return "The phone couldn't keep the key safely."
        }
        prefs.edit { putString(KEY_PREFIX + service, Base64.encodeToString(sealed, Base64.NO_WRAP)) }
        return null
    }

    fun forgetKey(service: String) = prefs.edit { remove(KEY_PREFIX + service) }

    /** The chosen service, with its key; or why it can't be reached. */
    fun endpoint(settings: AiSettings = this.settings): AiResult<Endpoint> =
        Endpoint.of(settings, settings.service?.let(::key))

    companion object {
        /** The preferences file, named in the backup rules. */
        const val FILE = "chaya_ai"
        private const val SETTINGS = "settings"
        private const val KEY_PREFIX = "key."

        fun from(context: Context) = AiKeys(context.getSharedPreferences(FILE, Context.MODE_PRIVATE), KeystoreLock())
    }
}
