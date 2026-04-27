package app.kumacheck.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM envelope encryption for the persisted JWT token.
 *
 * The key lives in AndroidKeyStore (hardware-backed where the device offers
 * a TEE/StrongBox) and never leaves it. Each `encrypt` call uses a fresh
 * provider-chosen IV — we prefix the envelope with `enc1:` so legacy
 * plaintext tokens written by older builds can be detected and migrated.
 *
 * Failure modes are best-effort: if the keystore is unavailable or a
 * decrypt fails (key rotated, ciphertext tampered), the caller falls back
 * to treating the token as missing, which forces re-login. We never throw
 * out of [encrypt] / [decrypt] — they return null on failure.
 */
class TokenCrypto {

    private val keyStore: KeyStore? = runCatching {
        KeyStore.getInstance(ANDROID_KS).apply { load(null) }
    }.onFailure { Log.w(TAG, "keystore unavailable; tokens will be stored as plaintext", it) }
        .getOrNull()

    @Synchronized
    private fun getOrCreateKey(): SecretKey? {
        val ks = keyStore ?: return null
        return runCatching {
            (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
                val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KS)
                gen.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                gen.generateKey()
            }
        }.onFailure { Log.w(TAG, "keystore key access failed", it) }.getOrNull()
    }

    /** Encrypts [plaintext] and returns an `enc1:`-prefixed envelope, or null on failure. */
    fun encrypt(plaintext: String): String? {
        val key = getOrCreateKey() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(1 + iv.size + ct.size)
            packed[0] = iv.size.toByte()
            System.arraycopy(iv, 0, packed, 1, iv.size)
            System.arraycopy(ct, 0, packed, 1 + iv.size, ct.size)
            PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        }.onFailure { Log.w(TAG, "encrypt failed", it) }.getOrNull()
    }

    /** Decrypts an `enc1:` envelope, or returns null on failure. */
    fun decrypt(envelope: String): String? {
        if (!isEnvelope(envelope)) return null
        val key = getOrCreateKey() ?: return null
        return runCatching {
            val raw = Base64.decode(envelope.substring(PREFIX.length), Base64.NO_WRAP)
            val ivLen = raw[0].toInt() and 0xff
            require(ivLen in 1..raw.size - 1) { "bad iv length" }
            val iv = raw.copyOfRange(1, 1 + ivLen)
            val ct = raw.copyOfRange(1 + ivLen, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct).toString(Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "decrypt failed", it) }.getOrNull()
    }

    companion object {
        const val PREFIX = "enc1:"
        private const val TAG = "TokenCrypto"
        private const val ANDROID_KS = "AndroidKeyStore"
        private const val KEY_ALIAS = "kumacheck_token_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        fun isEnvelope(s: String?): Boolean = s != null && s.startsWith(PREFIX)
    }
}
