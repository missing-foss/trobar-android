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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** A widget's data as the host holds it. */
sealed class WidgetData {
    object Loading : WidgetData()
    data class Loaded(val data: Any) : WidgetData()
    data class Failed(val message: String?) : WidgetData()
}

data class HomeUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    val catalog: List<WidgetCatalogEntry> = emptyList(),
    val prefs: WidgetPrefs = WidgetPrefs(emptyList(), emptyList(), WidgetSettings(AppApiDashboardClient.DEFAULT_COVER_LIMIT, emptyMap())),
    val isAdmin: Boolean = false,
    val data: Map<String, WidgetData> = emptyMap(),
    val thisDeviceId: Long? = null,
    val basket: List<BasketItem> = emptyList(),
    val destination: AppDevice? = null,
    /** Whether each listening-history widget's feed says a history is set
     * up, as far as it has been read; a widget missing here is not known. */
    val historyConfigured: Map<String, Boolean> = emptyMap(),
) {
    val staging: StagingContext get() = StagingContext(thisDeviceId, basket, destination)

    /** Every widget's loaded data, for the grids that deduplicate against their siblings. */
    val loaded: Map<String, Any> get() = data.mapNotNull { (id, d) -> (d as? WidgetData.Loaded)?.let { id to it.data } }.toMap()

    /** The catalog this account may see: an admin-only entry never appears
     * for a non-admin, whatever the stored lists say. */
    val visibleCatalog: List<WidgetCatalogEntry> get() = catalog.filter { !it.adminOnly || isAdmin }

    /** The catalog's ids in the account's order, minus the hidden ones --
     * the web's own rule, which maps over the catalog: no saved order
     * means catalog order; an id missing from the saved order (a widget
     * added since) goes after every ordered one, in catalog order; an id
     * in the saved order that the catalog does not know is never rendered.
     * It survives in the stored order all the same, because move() writes
     * the saved order back with the catalog's additions rather than
     * rebuilding it from the catalog -- what the phone writes keeps even
     * the entries it cannot draw, for a later app or catalog that can. */
    val orderedVisibleIds: List<String>
        get() {
            val catalogIds = visibleCatalog.map { it.id }
            val order = prefs.order
            val ordered = if (order.isEmpty()) catalogIds else order.filter { it in catalogIds } + catalogIds.filter { it !in order }
            val disabled = prefs.disabled.toSet()
            return ordered.filter { it !in disabled }
        }

    /** The widgets this account may add back: the visible catalog's hidden
     * ones, in catalog order, for the preferences screen's Add sheet. */
    val hiddenIds: List<String>
        get() {
            val disabled = prefs.disabled.toSet()
            return visibleCatalog.map { it.id }.filter { it in disabled }
        }
}

/** [id]'s listening-history flag from a loaded feed; unchanged for a failure or a widget without a feed. */
private fun HomeUiState.withHistory(id: String, result: WidgetData): HomeUiState {
    val feed = (result as? WidgetData.Loaded)?.data as? Feed ?: return this
    return copy(historyConfigured = historyConfigured + (id to feed.configured))
}

/** The Home dashboard's state: the catalog and the account's preferences
 * from the server, the account's rights from whoami, and each visible
 * widget's data -- fetched only when visible, on open and on refresh,
 * and again when a widget is switched on. Preference writes go through
 * the narrow PATCH, and what the server returns is what is shown. */
class HomeViewModel(
    private val dashboard: AppApiDashboardClient,
    private val app: AppApiClient,
    private val widgets: Map<String, Widget>,
    private val shared: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state
    private val stager = BasketStager(app, shared) { failure -> _state.update { it.copy(error = failure) } }
    private val clients = WidgetClients(dashboard, app)

    init {
        // Who this phone is, the basket, the header's device and the
        // widget preferences are the shared holder's:
        // mirrored here one way, written only through it, so the other tab,
        // the preferences screen and the badge see every change at once.
        viewModelScope.launch {
            shared.state.collect { sh ->
                val before = _state.value
                _state.update {
                    it.copy(thisDeviceId = sh.thisDeviceId, isAdmin = sh.isAdmin, basket = sh.basket.orEmpty(),
                            destination = sh.destination, prefs = sh.widgetPrefs ?: it.prefs)
                }
                if (!before.loading) refetchFor(before, _state.value)
            }
        }
        load(fresh = false)
    }

    /** A preferences change reaches the cards, wherever it was made -- here
     * or on the preferences screen: a widget turned on gets its data, and
     * one whose settings changed is read again. Every write lands here once,
     * through the shared holder, so this is the one place that fetches. */
    private fun refetchFor(before: HomeUiState, after: HomeUiState) {
        val shown = after.orderedVisibleIds
        val stale = shown.filter { id ->
            id !in before.orderedVisibleIds ||
                before.prefs.settings.coverLimitOf(id) != after.prefs.settings.coverLimitOf(id) ||
                before.prefs.settings.months[id] != after.prefs.settings.months[id]
        }
        if (stale.isNotEmpty()) viewModelScope.launch { fetch(stale) }
    }

    /** Loads the dashboard. The first load, at start, reuses what the
     * shared holder already read for another tab; an explicit one -- a
     * retry -- reads it all again. */
    fun load(fresh: Boolean = true) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val (catalog, prefs, failure) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val c = async { dashboard.catalog() }
                        val p = async { if (fresh) shared.reloadWidgetPrefs() else shared.ensureWidgetPrefs() }
                        // The staging context the grids need, read
                        // alongside, never on the way to a card.
                        val f = async { if (fresh) shared.reload() else shared.ensureLoaded() }
                        Triple(c.await(), p.await(), f.await())
                    }
                }
                _state.update { it.copy(loading = false, catalog = catalog, prefs = prefs, error = failure) }
                fetch(_state.value.orderedVisibleIds)
            } catch (e: IOException) {
                Log.w(TAG, "dashboard load failed", e)
                _state.update { it.copy(loading = false, error = e.message) }
            } catch (e: JSONException) {
                Log.w(TAG, "dashboard answered with something unexpected", e)
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    /** Reads the shared state and the preferences again, then the visible
     * widgets only; a hidden one costs nothing. The gesture that fixes a
     * dashboard that looks stale. */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            val failure = shared.reload()
            try {
                _state.update { it.copy(prefs = shared.reloadWidgetPrefs()) }
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message) }
            } catch (e: JSONException) {
                _state.update { it.copy(error = e.message) }
            }
            if (failure != null) _state.update { it.copy(error = failure) }
            fetch(_state.value.orderedVisibleIds)
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun fetch(ids: List<String>) {
        val toLoad = ids.filter { it in widgets }
        if (toLoad.isEmpty()) return
        _state.update { s -> s.copy(data = s.data + toLoad.associateWith { WidgetData.Loading }) }
        coroutineScope {
            toLoad.forEach { id ->
                launch {
                    val settings = _state.value.prefs.settings
                    val result = try {
                        WidgetData.Loaded(withContext(Dispatchers.IO) { widgets.getValue(id).load(clients, settings) })
                    } catch (e: IOException) {
                        WidgetData.Failed(e.message)
                    } catch (e: JSONException) {
                        WidgetData.Failed(e.message)
                    }
                    _state.update { s -> s.copy(data = s.data + (id to result)).withHistory(id, result) }
                }
            }
        }
    }

    /** Reads whether a listening history is set up for each hidden widget
     * that needs one and is not known yet, so the Add sheet can leave out
     * what would not show on Home. A read that fails records nothing: the
     * widget stays offered, with its caption, rather than hidden on a guess. */
    fun checkHistory() {
        val s = _state.value
        val unknown = s.hiddenIds.filter { widgets[it]?.needsListeningHistory == true && it !in s.historyConfigured }
        if (unknown.isEmpty()) return
        viewModelScope.launch {
            unknown.forEach { id ->
                val result = try {
                    WidgetData.Loaded(withContext(Dispatchers.IO) { widgets.getValue(id).load(clients, _state.value.prefs.settings) })
                } catch (e: IOException) {
                    WidgetData.Failed(e.message)
                } catch (e: JSONException) {
                    WidgetData.Failed(e.message)
                }
                _state.update { it.withHistory(id, result) }
            }
        }
    }

    /** Takes [id] off Home. Its place in the order is kept, for [restore]. */
    fun remove(id: String) = patch(disabled = (_state.value.prefs.disabled + id).distinct())

    /** Undoes a [remove]: back on Home in the place it had. */
    fun restore(id: String) = patch(disabled = _state.value.prefs.disabled - id)

    /** Puts a hidden widget back on Home at the end of the list, where the
     * person who picked it will look -- not at the place it had before,
     * which they can no longer see. The hidden list and the order go in
     * one write. */
    fun add(id: String) {
        val full = shownOrder().first
        patch(disabled = _state.value.prefs.disabled - id, order = full.filter { it != id } + id)
    }

    /** Moves one id a step among the widgets on Home, in the saved order,
     * then stores the whole order. A hidden widget, or an id the catalog
     * does not know, is not listed, so it is not a step: it keeps its place
     * in what is written -- swapping with it would have moved nothing the
     * user can see. */
    fun move(id: String, up: Boolean) {
        val i = shownOrder().second.indexOf(id)
        if (i >= 0) moveTo(id, if (up) i - 1 else i + 1)
    }

    /** Moves [id] to position [to] among the listed widgets -- a drag's drop,
     * or one step of [move] -- in one write of the whole order, however far
     * it went. Nothing is written for a move that lands where it started or
     * off either end. */
    fun moveTo(id: String, to: Int) {
        val (full, shown) = shownOrder()
        val i = shown.indexOf(id)
        if (i < 0 || to !in shown.indices || to == i) return
        shown.removeAt(i)
        shown.add(to, id)
        // The catalog ids take the new sequence in the positions they held; every other id stays put.
        val catalogIds = shown.toSet()
        val next = shown.iterator()
        patch(order = full.map { if (it in catalogIds) next.next() else it })
    }

    /** The saved order with the catalog's additions after it, and the ids of
     * it this user has on Home, in that order. */
    private fun shownOrder(): Pair<List<String>, MutableList<String>> {
        val s = _state.value
        val catalogIds = s.visibleCatalog.map { it.id }
        val disabled = s.prefs.disabled.toSet()
        val full = if (s.prefs.order.isEmpty()) catalogIds else s.prefs.order + catalogIds.filter { it !in s.prefs.order }
        return full to full.filter { it in catalogIds && it !in disabled }.toMutableList()
    }

    fun setMonths(id: String, months: Int) = patch(months = mapOf(id to months.coerceIn(WidgetIds.MONTHS_RANGE)))
    /** A cover widget's own count -- or, on a server older than per-widget
     * counts, the one value, which there sets all three. */
    fun setCoverLimit(id: String, limit: Int) =
        if (_state.value.prefs.settings.perWidgetCovers) patch(coverLimits = mapOf(id to limit)) else patch(coverLimit = limit)

    private fun patch(
        disabled: List<String>? = null,
        order: List<String>? = null,
        coverLimit: Int? = null,
        months: Map<String, Int> = emptyMap(),
        coverLimits: Map<String, Int> = emptyMap(),
    ) {
        viewModelScope.launch {
            try {
                // Written through the shared holder, which finishes the write
                // even if this screen is left first. The data the change
                // needs is fetched by the collector above, once, as for a
                // change made on the preferences screen.
                shared.patchWidgetPrefs(disabled, order, coverLimit, months, coverLimits)
            } catch (e: IOException) {
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    /** Stages an album for the header's device, as the library does. */
    fun stage(type: String, target: String): StageStart = stager.stage(type, target)

    companion object {
        private const val TAG = "HomeDashboard"

        fun factory(
            context: Context,
            pairing: Prefs.Pairing,
            shared: AppState,
            widgets: Map<String, Widget> = WidgetRegistry.all,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = context.applicationContext
                    HomeViewModel(
                        AppApiDashboardClient(app, pairing.serverUrl, pairing.token),
                        AppApiClient(app, pairing.serverUrl, pairing.token),
                        widgets,
                        shared,
                    )
                }
            }
    }
}
