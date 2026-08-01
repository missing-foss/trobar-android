// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #82: TokenCrypto wraps the real AndroidKeyStore (non-exportable, hardware-
// backed where available), so this has to run instrumented — a plain JVM
// test has no Keystore to talk to.

package com.mfoss.trobar

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TokenCryptoTest {
    @Test
    fun encryptThenDecryptReturnsTheOriginalPlaintext() {
        val token = "abc123-real-device-token"
        val encrypted = TokenCrypto.encrypt(token)
        assertEquals(token, TokenCrypto.decrypt(encrypted))
    }

    @Test
    fun encryptedValuesAreMarkedAndPlaintextIsNot() {
        val encrypted = TokenCrypto.encrypt("some-token")
        assertTrue(TokenCrypto.isEncrypted(encrypted))
        assertFalse(TokenCrypto.isEncrypted("some-token"))
    }

    @Test
    fun encryptingTheSameTokenTwiceProducesDifferentCiphertext() {
        // GCM must never reuse an IV under the same key — a fresh random IV
        // per call means the same plaintext never round-trips to the same
        // stored string twice.
        val token = "same-token-both-times"
        val first = TokenCrypto.encrypt(token)
        val second = TokenCrypto.encrypt(token)
        assertTrue(first != second)
        assertEquals(token, TokenCrypto.decrypt(first))
        assertEquals(token, TokenCrypto.decrypt(second))
    }

    @Test
    fun decryptOfCorruptedCiphertextReturnsNullNotThrows() {
        val encrypted = TokenCrypto.encrypt("a-token")
        // Flip the stored payload so the GCM tag no longer matches — this
        // must surface as "no valid token" (null), never a crash.
        val corrupted = TokenCrypto.PREFIX + encrypted.removePrefix(TokenCrypto.PREFIX).reversed()
        assertNull(TokenCrypto.decrypt(corrupted))
    }

    @Test
    fun decryptOfGarbageInputReturnsNull() {
        assertNull(TokenCrypto.decrypt("v1:not-valid-base64!!!"))
        assertNull(TokenCrypto.decrypt("not-even-prefixed"))
    }
}
