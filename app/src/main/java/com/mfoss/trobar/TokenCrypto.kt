// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the device pairing token in Android Keystore-backed AES-256-GCM before
 * it is persisted (gitea#54). The AES key is generated inside the AndroidKeyStore
 * and is non-exportable — hardware-backed (TEE/StrongBox) where the device
 * supports it — so the token is no longer recoverable from a raw dump of the
 * app's storage: extracting it requires live code execution *as this app* on the
 * device, not just file/forensic access.
 *
 * The key is deliberately NOT user-authentication-bound: background sync
 * (WorkManager) must be able to decrypt the token with no user present. The
 * platform Keystore is used directly rather than the (now-deprecated)
 * androidx.security:security-crypto library, so this adds no dependency.
 */
object TokenCrypto {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "trobar_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    /** Marks a value produced by [encrypt]; lets us tell it apart from a
     *  legacy plaintext token written before #54 (base64url tokens never
     *  contain a colon). */
    const val PREFIX = "v1:"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    /** Returns PREFIX + base64(iv ‖ ciphertext+tag). A fresh random IV per call
     *  (GCM must never reuse an IV under the same key). */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Reverses [encrypt]. Returns null on any failure (e.g. the Keystore key
     *  was lost); callers treat that as "no valid token" → re-pair. */
    fun decrypt(stored: String): String? = try {
        val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val iv = raw.copyOfRange(0, IV_LEN)
        val ct = raw.copyOfRange(IV_LEN, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
