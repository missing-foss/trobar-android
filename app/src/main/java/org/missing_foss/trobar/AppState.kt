// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException

/** What the tabs share: who this phone is, the devices the owner may stage
 * to, the basket, and the Home widget preferences. [basket] is null until
 * first read, so a badge stays off rather than claiming an empty basket;
 * [widgetPrefs] likewise, and is read only by the screens that show
 * widgets. */
data class SharedState(
    val devices: List<AppDevice> = emptyList(),
    val thisDeviceId: Long? = null,
    val isAdmin: Boolean = false,
    val basket: List<BasketItem>? = null,
    val widgetPrefs: WidgetPrefs? = null,
    val chosenDeviceId: Long? = null,
    /** Where another tab asked the Library to open, until it has. */
    val libraryTarget: LibraryTarget? = null,
    /** The playlist another tab asked the Playlists tab to open, until it has. */
    val playlistTarget: Long? = null,
    /** Goes up each time this phone's selections change from the app: a send
     * to it, or a removal from it. Home reads its album grids again then. */
    val thisPhoneSelections: Int = 0,
) {
    /** The device the header names and staging goes to: the one
     * chosen, while it is still a device the owner may use, else this
     * phone. Null only until the devices are read. */
    val destination: AppDevice?
        get() = devices.firstOrNull { it.id == chosenDeviceId } ?: devices.firstOrNull { it.id == thisDeviceId }

    /** What staging from a screen needs to know. */
    val staging: StagingContext get() = StagingContext(thisDeviceId, basket.orEmpty(), destination)
}

/** An artist page for the Library to open, with [album] scrolled to if set. */
data class LibraryTarget(val artist: String, val album: String? = null, val details: Boolean = false)

/** The one holder of [SharedState], held by the activity and created
 * once in the shell. Screens' view models read it and write through it: a
 * stage on one tab, a removal in the basket, a send, a change on the widget
 * preferences screen -- each is one write here, and every screen and the
 * badge see it at once, with no second read. What belongs to one screen
 * (the artist list, the widget data) stays in that screen's view model. */
class AppState(
    private val context: Context,
    private val app: AppApiClient,
    private val dashboard: AppApiDashboardClient,
) : ViewModel() {
    private val _state = MutableStateFlow(SharedState())
    val state: StateFlow<SharedState> = _state
    private val loadLock = Mutex()
    private val prefsLock = Mutex()
    // Widget-preference writes reach the server one at a time, in the order
    // they were made: an Undo sent while its swipe's write is still out
    // must not land first.
    private val prefsWriteLock = Mutex()
    private var loaded = false

    /** Reads who this phone is, the devices and the basket, once: a
     * second caller -- the other tab -- gets what is held. Returns the
     * failure, for the caller to show; null when fine. */
    suspend fun ensureLoaded(): String? = loadLock.withLock { if (loaded) null else readLocked() }

    /** Reads them again: pull-to-refresh, and after the basket screen's writes. */
    suspend fun reload(): String? = loadLock.withLock { readLocked() }

    private suspend fun readLocked(): String? = try {
        withContext(Dispatchers.IO) {
            coroutineScope {
                val me = async { app.whoAmI() }
                val devices = async { app.devices() }
                val basket = async { app.basket() }
                val chosen = async { Prefs.headerDevice(context).first() }
                val read = SharedState(
                    devices = devices.await(), thisDeviceId = me.await().deviceId, isAdmin = me.await().isAdmin,
                    basket = basket.await(), chosenDeviceId = chosen.await(),
                )
                // A choice made this session wins over the stored one, which
                // chooseDevice writes a moment later: a refresh in between
                // must not bring back the old choice.
                _state.update {
                    read.copy(
                        widgetPrefs = it.widgetPrefs, libraryTarget = it.libraryTarget, playlistTarget = it.playlistTarget,
                        chosenDeviceId = it.chosenDeviceId ?: read.chosenDeviceId,
                    )
                }
            }
        }
        loaded = true
        null
    } catch (e: IOException) {
        e.message ?: e.toString()
    } catch (e: JSONException) {
        e.message ?: e.toString()
    } catch (e: IllegalArgumentException) {
        // OkHttp's answer to a stored address it cannot build a request
        // from: the address is the user's, so this is an expected input
        // shown as a failure, never a crash.
        e.message ?: e.toString()
    }

    /** The widget preferences, read once and then held; throws on failure,
     * for the dashboard to report. */
    suspend fun ensureWidgetPrefs(): WidgetPrefs = prefsLock.withLock {
        _state.value.widgetPrefs ?: withContext(Dispatchers.IO) { dashboard.widgetPrefs() }.also { setWidgetPrefs(it) }
    }

    /** Reads the widget preferences again; throws on failure. */
    suspend fun reloadWidgetPrefs(): WidgetPrefs = prefsLock.withLock {
        withContext(Dispatchers.IO) { dashboard.widgetPrefs() }.also { setWidgetPrefs(it) }
    }

    /** A selection on [deviceId] was added or removed. Counted only for
     * this phone: what Home leaves out is what is selected for it. */
    fun selectionsChanged(deviceId: Long) {
        _state.update { if (deviceId == it.thisDeviceId) it.copy(thisPhoneSelections = it.thisPhoneSelections + 1) else it }
    }

    /** Re-reads the basket alone; returns the failure, null when fine. */
    suspend fun reloadBasket(): String? = try {
        val items = withContext(Dispatchers.IO) { app.basket() }
        setBasket(items)
        null
    } catch (e: IOException) {
        e.message ?: e.toString()
    } catch (e: JSONException) {
        e.message ?: e.toString()
    } catch (e: IllegalArgumentException) {
        // OkHttp's answer to a stored address it cannot build a request
        // from: the address is the user's, so this is an expected input
        // shown as a failure, never a crash.
        e.message ?: e.toString()
    }

    /** The basket as the server returned it after a write. */
    fun setBasket(items: List<BasketItem>) = _state.update { it.copy(basket = items) }

    /** The header's choice: shown on every tab at once, and kept on this
     * phone for the next start. */
    fun chooseDevice(deviceId: Long) {
        _state.update { it.copy(chosenDeviceId = deviceId) }
        viewModelScope.launch { Prefs.setHeaderDevice(context, deviceId) }
    }

    /** Asks the Library to open this artist page next time it shows. */
    fun openInLibrary(target: LibraryTarget) = _state.update { it.copy(libraryTarget = target) }

    /** The Library has opened [SharedState.libraryTarget]. */
    fun libraryTargetOpened() = _state.update { it.copy(libraryTarget = null) }

    /** Asks the Playlists tab to open this playlist next time it shows. */
    fun openPlaylist(id: Long) = _state.update { it.copy(playlistTarget = id) }

    /** The Playlists tab has opened [SharedState.playlistTarget]. */
    fun playlistTargetOpened() = _state.update { it.copy(playlistTarget = null) }

    /** The preferences as the server stored them after a write. */
    fun setWidgetPrefs(prefs: WidgetPrefs) = _state.update { it.copy(widgetPrefs = prefs) }

    /** A widget-preferences write that outlives the screen asking for it:
     * run in this holder's scope, not the caller's, so leaving the
     * preferences screen while the reply is on its way cancels the wait,
     * not the write -- and the stored preferences reach every screen
     * either way. Throws what the write throws. */
    suspend fun patchWidgetPrefs(
        disabled: List<String>?,
        order: List<String>?,
        coverLimit: Int?,
        months: Map<String, Int>,
        coverLimits: Map<String, Int> = emptyMap(),
        playlistsLimit: Int? = null,
    ): WidgetPrefs = viewModelScope.async(Dispatchers.Main.immediate) {
        // Queued on the lock here, synchronously, in the callers' order;
        // only the request itself goes to IO. In this holder's scope, so a
        // write finishes even if the screen that made it is left.
        prefsWriteLock.withLock {
            withContext(Dispatchers.IO) { dashboard.patchWidgetPrefs(disabled, order, coverLimit, months, coverLimits, playlistsLimit) }
                .also(::setWidgetPrefs)
        }
    }.await()

    companion object {
        /** A key per pairing: a new pairing is a new holder, never the old
         * one's state read with the new token. */
        fun key(pairing: Prefs.Pairing): String = "app-state:" + (pairing.serverUrl + "\u0000" + pairing.token).hashCode()

        fun factory(context: Context, pairing: Prefs.Pairing): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                AppState(app, AppApiClient(app, pairing.serverUrl, pairing.token), AppApiDashboardClient(app, pairing.serverUrl, pairing.token))
            }
        }
    }
}
