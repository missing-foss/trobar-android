// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package com.mfoss.trobar

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
 * Wraps the device pairing token in Android Keystore-backed AES-256-GCM before
 * it is persisted. The AES key is generated inside the AndroidKeyStore
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
    private const val TAG = "TokenCrypto"

    // internal (not private): #101's regression test deletes this exact
    // alias from a real KeyStore.getInstance(KEYSTORE) to simulate an
    // unreadable key without mocking anything.
    internal const val KEYSTORE = "AndroidKeyStore"
    internal const val KEY_ALIAS = "trobar_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val KEY_SIZE_BITS = 256

    /** Marks a value produced by [encrypt]; lets us tell it apart from a
     * legacy plaintext token written by an older version (base64url tokens never
     *  contain a colon). */
    const val PREFIX = "v1:"

    /** #101: `getEntry()` failing (an unreadable/corrupt alias, not merely a
     * missing one) is wrapped separately from the `as?` cast below it, so a
     * real Keystore exception is distinguishable in logs from "no key yet" —
     * before this, both collapsed into the same `null` and were impossible
     * to tell apart from the outside. Never generates — see [getOrCreateKey]
     * for the only place that's allowed to. */
    private fun getExistingKeyOrNull(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val entry = try {
            ks.getEntry(KEY_ALIAS, null)
        } catch (e: Exception) {
            Log.w(TAG, "Keystore entry for $KEY_ALIAS unreadable (${e.javaClass.simpleName}) — " +
                "treating as unavailable, NOT regenerating (that would permanently destroy " +
                "whatever it could otherwise still decrypt)", e)
            return null
        }
        return (entry as? KeyStore.SecretKeyEntry)?.secretKey
    }

    /** Only [encrypt] may call this — pairing time is the one place a
     * missing key genuinely means "create one", never a read. */
    private fun getOrCreateKey(): SecretKey {
        getExistingKeyOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .build()
        )
        return generator.generateKey()
    }

    /** Returns PREFIX + base64(iv ‖ ciphertext+tag). A fresh random IV per call
     *  (GCM must never reuse an IV under the same key). */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + ct.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ct, 0, out, iv.size, ct.size)
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Reverses [encrypt]. Returns null on any failure (e.g. the Keystore key
     * was lost); callers treat that as "credentials unreadable", distinct
     * from "never paired" (see Prefs.PairingState).
     *
     * #101: this used to share [getOrCreateKey] with [encrypt] — a read
     * silently minting and persisting a brand new key the moment the old
     * one couldn't be loaded (a transient condition observed right after a
     * device reboot). That's not a retry-safe failure: the new key
     * permanently overwrites the alias, so the *next* attempt to decrypt
     * the (now merely stale, previously perfectly fine) stored ciphertext
     * fails too — forever, since the key that could read it is gone. Only
     * [getExistingKeyOrNull] is used here, specifically so an unreadable key
     * stays a transient failure the next launch can still recover from,
     * rather than an unrecoverable one this call just caused. */
    fun decrypt(stored: String): String? {
        val key = getExistingKeyOrNull() ?: run {
            Log.w(TAG, "No usable Keystore key for $KEY_ALIAS — cannot decrypt stored token")
            return null
        }
        return try {
            val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_LEN)
            val ct = raw.copyOfRange(IV_LEN, raw.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decrypt failed (${e.javaClass.simpleName}) — token stays unreadable", e)
            null
        }
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
