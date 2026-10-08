// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
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
import java.io.IOException

/** One destination device and what is staged for it -- the basket's unit
 * of display and of sending: sending a device sends its whole section. */
data class BasketSection(val device: AppDevice, val items: List<BasketItem>)

/** What a send did, for the message that follows it. `toThisPhone` is
 * what decides whether a sync of this phone is offered. */
data class SendOutcome(val device: AppDevice, val result: FanOutResult, val toThisPhone: Boolean,
                       val deviceNames: List<String> = listOf(device.name))

data class BasketUiState(
    val loading: Boolean = true,
    val sections: List<BasketSection> = emptyList(),
    val itemCount: Int = 0,
    val thisDeviceId: Long? = null,
    val error: String? = null,
    val lastSend: SendOutcome? = null,
)

/** The basket screen's state: the account's basket, grouped one section
 * per destination device in the server's device order, and the writes
 * the screen offers. The basket and the devices are the shared holder's;
 * every write reads the basket back from the server into it -- the basket
 * is server-side and shared with the browser, so what is shown, here and
 * on the badge, is always what the server holds, never a local guess. */
class BasketViewModel(private val api: AppApiClient, private val shared: AppState) : ViewModel() {
    private val _state = MutableStateFlow(BasketUiState())
    val state: StateFlow<BasketUiState> = _state

    init {
        viewModelScope.launch {
            shared.state.collect { sh ->
                val items = sh.basket ?: return@collect
                _state.update { it.copy(thisDeviceId = sh.thisDeviceId, itemCount = items.size, sections = sections(sh.devices, items)) }
            }
        }
        load()
    }

    /** Opening the basket reads it fresh: the browser may have changed it. */
    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val failure = shared.reload()
            _state.update { it.copy(loading = false, error = failure) }
        }
    }

    fun removeItem(itemId: Long) = write { api.removeBasketItem(itemId) }

    /** Takes one device off an item; the item goes with its last device. */
    fun unstage(itemId: Long, deviceId: Long) = write { api.unstageDevice(itemId, deviceId) }

    fun clear() = write { api.clearBasket() }

    /** Sends one device's whole section, then reloads; the outcome names
     * the device and whether it was this phone. */
    fun send(section: BasketSection) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { api.fanOut(listOf(section.device.id)) }
                _state.update {
                    it.copy(lastSend = SendOutcome(section.device, result, toThisPhone = section.device.id == it.thisDeviceId))
                }
                shared.reloadBasket()?.let { failure -> _state.update { it.copy(error = failure) } }
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun consumeSend() = _state.update { it.copy(lastSend = null) }
    fun consumeError() = _state.update { it.copy(error = null) }

    private fun write(action: () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                shared.reloadBasket()?.let { failure -> _state.update { it.copy(error = failure) } }
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    companion object {
        /** Devices in the server's order; a device with nothing staged has no section. */
        fun sections(devices: List<AppDevice>, items: List<BasketItem>): List<BasketSection> =
            devices.map { d -> BasketSection(d, items.filter { d.id in it.deviceIds }) }.filter { it.items.isNotEmpty() }

        fun factory(context: Context, pairing: Prefs.Pairing, shared: AppState): ViewModelProvider.Factory = viewModelFactory {
            initializer { BasketViewModel(AppApiClient(context.applicationContext, pairing.serverUrl, pairing.token), shared) }
        }
    }
}
