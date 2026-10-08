// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher

/** A scripted server that refuses what the script did not expect. The
 * default dispatcher blocks on an empty queue, so a request the test did
 * not enqueue a response for hangs on the client's read timeout and
 * surfaces as a wait that times out; this one answers it at once with a
 * status nothing expects, so it shows up as the wrong state instead. */
fun MockWebServer.scripted(): MockWebServer = apply {
    dispatcher = QueueDispatcher().apply {
        setFailFast(MockResponse().setResponseCode(UNSCRIPTED_STATUS).setBody("unscripted request"))
    }
}

const val UNSCRIPTED_STATUS = 599
