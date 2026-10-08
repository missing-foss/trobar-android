// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException

/** What a surface needs to know about the account's staging: which is
 * this phone, what is in the basket, and the device the header names --
 * where a stage goes. Both the library and the dashboard hold one. */
class StagingContext(
    val thisDeviceId: Long?,
    val basket: List<BasketItem>,
    val destination: AppDevice?,
) {
    /** Whether the target is already staged for the header's device; staged
     * for another device only, a stage still has something to add. */
    fun stagedHere(type: String, target: String): Boolean {
        val id = destination?.id ?: return false
        return basket.any { it.type == type && it.target == target && id in it.deviceIds }
    }
}

/** What a stage answered at the gesture, before any write: started, or
 * refused with the reason to show. The screen plays the flight only on
 * [Started]. */
sealed interface StageStart {
    data object Started : StageStart
    data class AlreadyStaged(val device: AppDevice) : StageStart
    data object NoDestination : StageStart
}

/** The one staging write, shared by every surface that stages: one target
 * for the header's device, then the basket reloaded whatever happened, so
 * the badge shows what the server holds -- a failed write moves nothing.
 * Refused at once when the target is already staged there or on its way,
 * so a second double-tap never writes twice. The write runs in the shared
 * holder's scope, so leaving the screen does not cancel it. [onFailure]
 * gets the first failure met, for the screen to show. */
class BasketStager(
    private val api: AppApiClient,
    private val shared: AppState,
    private val onFailure: (String) -> Unit,
) {
    // Touched on the main thread only: stage() runs there, and so does the
    // continuation after withContext.
    private val pending = mutableSetOf<Triple<String, String, Long>>()

    fun stage(type: String, target: String): StageStart {
        val sh = shared.state.value
        val device = sh.destination ?: return StageStart.NoDestination
        val key = Triple(type, target, device.id)
        if (key in pending || StagingContext(sh.thisDeviceId, sh.basket.orEmpty(), device).stagedHere(type, target)) {
            return StageStart.AlreadyStaged(device)
        }
        pending += key
        shared.viewModelScope.launch {
            val failure = try {
                withContext(Dispatchers.IO) { write(type, target, device.id) }
            } finally {
                pending -= key
            }
            failure?.let(onFailure)
        }
        return StageStart.Started
    }

    private fun write(type: String, target: String, deviceId: Long): String? {
        var failure: String? = null
        try {
            api.addToBasket(type, target, listOf(deviceId))
        } catch (e: IOException) {
            failure = e.message ?: e.toString()
        } catch (e: JSONException) {
            failure = e.message ?: e.toString()
        }
        try {
            shared.setBasket(api.basket())
        } catch (e: IOException) {
            failure = failure ?: e.message ?: e.toString()
        } catch (e: JSONException) {
            failure = failure ?: e.message ?: e.toString()
        }
        return failure
    }
}
