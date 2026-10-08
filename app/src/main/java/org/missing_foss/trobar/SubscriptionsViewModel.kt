// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

data class SubscriptionsUiState(
    val loading: Boolean = true,
    val subscriptions: List<PlaylistSubscription> = emptyList(),
    val url: String = "",
    val busy: Boolean = false,
    /** Why the last add failed: the server's own message for a link it
     * refuses, or null to say it the browser's way. */
    val addError: AddError? = null,
    val error: String? = null,
)

/** An add that did not go through: [message] is the server's reason when
 * it gave one (a link that is not a playlist); null means it failed some
 * other way, said as the browser says it. */
data class AddError(val message: String?)

/** The owner's playlist subscriptions: listed, added by link -- one pasted,
 * or shared from another app and handed in as [sharedUrl] -- refreshed and
 * removed. Each write answers with the subscription as the server holds it
 * afterwards, and the list is read again, so it is always the server's. */
class SubscriptionsViewModel(private val api: AppApiPlaylistClient, sharedUrl: String?) : ViewModel() {
    private val _state = MutableStateFlow(SubscriptionsUiState(url = sharedUrl.orEmpty()))
    val state: StateFlow<SubscriptionsUiState> = _state

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val failure = reread()
            _state.update { it.copy(loading = false, error = failure ?: it.error) }
        }
    }

    fun setUrl(url: String) = _state.update { it.copy(url = url, addError = null) }

    /** Subscribes and imports at once, as the server does. A link it
     * refuses keeps the field as typed, for a correction; one it takes
     * clears it. An import that ran but failed is still a subscription:
     * the list shows it, with why. */
    fun add() {
        val url = _state.value.url.trim()
        if (url.isEmpty() || _state.value.busy) return
        _state.update { it.copy(busy = true, addError = null) }
        viewModelScope.launch {
            val added = try {
                withContext(Dispatchers.IO) { api.subscribe(url) }
                true
            } catch (e: IOException) {
                _state.update { it.copy(addError = AddError(e.message)) }
                false
            } catch (e: JSONException) {
                // An answer that is not the server's shape: said the browser's
                // way, and the detail kept for the log.
                Log.w(TAG, "subscribe answered with something unexpected", e)
                _state.update { it.copy(addError = AddError(null)) }
                false
            }
            val failure = reread()
            _state.update { it.copy(busy = false, url = if (added) "" else it.url, error = failure ?: it.error) }
        }
    }

    fun refresh(id: Long) = write { api.refreshSubscription(id) }

    /** Unsubscribes; the server removes the playlist it produced too. */
    fun remove(id: Long) = write { api.unsubscribe(id) }

    fun consumeError() = _state.update { it.copy(error = null) }

    private fun write(call: () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            var failure = try {
                withContext(Dispatchers.IO) { call() }
                null
            } catch (e: IOException) {
                e.message ?: e.toString()
            } catch (e: JSONException) {
                e.message ?: e.toString()
            }
            failure = reread() ?: failure
            _state.update { it.copy(busy = false, error = failure ?: it.error) }
        }
    }

    /** Reads the list; returns the failure, null when fine. */
    private suspend fun reread(): String? = try {
        val subs = withContext(Dispatchers.IO) { api.subscriptions() }
        _state.update { it.copy(subscriptions = subs) }
        null
    } catch (e: IOException) {
        e.message ?: e.toString()
    } catch (e: JSONException) {
        e.message ?: e.toString()
    }

    companion object {
        private const val TAG = "Subscriptions"

        fun factory(context: Context, pairing: Prefs.Pairing, sharedUrl: String?): ViewModelProvider.Factory = viewModelFactory {
            initializer { SubscriptionsViewModel(AppApiPlaylistClient(context.applicationContext, pairing.serverUrl, pairing.token), sharedUrl) }
        }
    }
}
