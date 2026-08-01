// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
// #82: the plaintext->encrypted token migration path — installs paired by an
// older version stored the token unencrypted; migrateTokenIfNeeded() must
// upgrade it in place (once, idempotently) without breaking pairing() for
// callers that never think about encryption at all. Runs instrumented since
// this exercises the real DataStore file and the real Keystore underneath
// TokenCrypto (see TokenCryptoTest for that layer on its own).

package com.mfoss.trobar

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// Same key name/type Prefs.kt uses internally — DataStore matches preference
// keys by name+type, so this reads/writes the exact same underlying entry
// without needing Prefs' own (rightly encapsulated) TOKEN key constant.
private val TOKEN_KEY = stringPreferencesKey("token")

@RunWith(AndroidJUnit4::class)
class PrefsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearPairingState() {
        runBlocking { Prefs.clearPairing(context) }
    }

    @Test
    fun legacyPlaintextTokenIsEncryptedInPlaceAfterMigration() {
        runBlocking {
            context.dataStore.edit { it[TOKEN_KEY] = "legacy-plaintext-token" }

            Prefs.migrateTokenIfNeeded(context)

            val stored = context.dataStore.data.first()[TOKEN_KEY]
            assertNotNull(stored)
            assertTrue(TokenCrypto.isEncrypted(stored!!))
            assertEquals("legacy-plaintext-token", TokenCrypto.decrypt(stored))
        }
    }

    @Test
    fun pairingStillResolvesTheTokenBeforeMigrationRuns() {
        // pairing() itself transparently decrypts-or-passes-through (see its
        // own doc comment) — a legacy plaintext token must keep working even
        // if migrateTokenIfNeeded() is never called (e.g. this app run).
        runBlocking {
            context.dataStore.edit {
                it[stringPreferencesKey("server_url")] = "https://example.test"
                it[TOKEN_KEY] = "legacy-plaintext-token"
            }

            val state = Prefs.pairing(context).first()

            assertTrue(state is Prefs.PairingState.Paired)
            assertEquals("legacy-plaintext-token", (state as Prefs.PairingState.Paired).pairing.token)
        }
    }

    @Test
    fun migrationIsANoOpOnceTheTokenIsAlreadyEncrypted() {
        runBlocking {
            Prefs.setPairing(context, "https://example.test", "already-set-token")
            val beforeMigration = context.dataStore.data.first()[TOKEN_KEY]

            Prefs.migrateTokenIfNeeded(context)

            val afterMigration = context.dataStore.data.first()[TOKEN_KEY]
            assertEquals(beforeMigration, afterMigration)
            val state = Prefs.pairing(context).first()
            assertEquals("already-set-token", (state as Prefs.PairingState.Paired).pairing.token)
        }
    }

    @Test
    fun migrationWithNoStoredTokenIsANoOp() {
        runBlocking {
            Prefs.migrateTokenIfNeeded(context) // must not throw with nothing paired
            assertEquals(null, context.dataStore.data.first()[TOKEN_KEY])
        }
    }

    // #101: pairing() must tell "never paired" apart from "paired, but the
    // token can't be decrypted" — before the fix both collapsed into a bare
    // null, which is what made a decrypt failure indistinguishable from a
    // clean slate.
    @Test
    fun pairingWithNothingStoredIsNotPaired() {
        runBlocking {
            assertEquals(Prefs.PairingState.NotPaired, Prefs.pairing(context).first())
        }
    }

    @Test
    fun pairingWithAnUndecryptableTokenReportsTokenUnreadableWithTheStoredUrl() {
        runBlocking {
            // A real, valid pairing, then corrupt only the stored ciphertext
            // (still v1:-prefixed, so isEncrypted() is true) so decrypt()
            // genuinely fails without needing to touch the Keystore itself.
            Prefs.setPairing(context, "https://example.test", "some-token")
            context.dataStore.edit { it[TOKEN_KEY] = TokenCrypto.PREFIX + "not-valid-base64!!!" }

            val state = Prefs.pairing(context).first()

            assertTrue(state is Prefs.PairingState.TokenUnreadable)
            assertEquals("https://example.test", (state as Prefs.PairingState.TokenUnreadable).serverUrl)
        }
    }

    @Test
    fun pairingWithValidCredentialsIsPaired() {
        runBlocking {
            Prefs.setPairing(context, "https://example.test", "real-token")

            val state = Prefs.pairing(context).first()

            assertTrue(state is Prefs.PairingState.Paired)
            assertEquals("real-token", (state as Prefs.PairingState.Paired).pairing.token)
        }
    }
}
