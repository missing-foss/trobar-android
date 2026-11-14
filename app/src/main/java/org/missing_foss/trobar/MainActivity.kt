// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.woheller69.freeDroidWarn.FreeDroidWarn


class MainActivity : ComponentActivity() {
    // A link shared from another app, waiting for the paired app to take it.
    // Held here, not in the composition, so it survives the pairing screens.
    private var sharedLink by mutableStateOf<String?>(null)

    // Registered on the Activity, not in the folder picker's composition:
    // saving the folder replaces that screen while the prompt is still up.
    // Nothing to do with the answer; Settings reads the current state.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Asks for the notification permission once, on Android 13 and later,
     * where the sync's progress notification needs it. A refusal changes
     * nothing else: the sync runs the same, without the notification. */
    private fun askForNotificationsOnce() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        lifecycleScope.launch {
            if (!shouldAskForNotifications(Build.VERSION.SDK_INT, granted,
                    Prefs.notificationPromptDone(this@MainActivity))) return@launch
            Prefs.setNotificationPromptDone(this@MainActivity)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // API 26-32: the in-app language choice is applied here; see AppLanguage.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) sharedLink = sharedLinkOf(intent)
        // Upgrade any legacy plaintext token to Keystore-encrypted at rest.
        lifecycleScope.launch { Prefs.migrateTokenIfNeeded(this@MainActivity) }
        warnAboutCertifiedDevices()
        LauncherIcon.followSettingWhenAppLeaves(applicationContext)
        setContent {
            val dynamicColor by Prefs.useDynamicColor(this).collectAsState(initial = false)
            val windowSizeClass = calculateWindowSizeClass(this)
            // #54: phones are portrait-only; large screens (tablets, foldables)
            // are left system-managed and resizable. That's the direction the
            // platform is forcing anyway — Android 16 ignores an app's
            // orientation on large screens at targetSdk 36, and Android 17 drops
            // the opt-out — so this is ready ahead of the targetSdk bump.
            // Leaving large screens UNSPECIFIED (no requestedOrientation churn)
            // is also what kills the earlier tablet flicker.
            val isCompact =
                windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
            LaunchedEffect(isCompact) {
                requestedOrientation = if (isCompact) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            // Nothing is drawn until the stored theme is read, so a Nuèch or
            // Contraste user never sees a frame of Troubadour first.
            val brandKey by Prefs.brandTheme(this).collectAsState(initial = null)
            val brand = brandKey?.let { BrandTheme.of(it) } ?: return@setContent
            TrobarTheme(brand = brand, dynamicColor = dynamicColor) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(
                        LocalWindowWidthSizeClass provides windowSizeClass.widthSizeClass,
                        LocalWindowHeightSizeClass provides windowSizeClass.heightSizeClass,
                    ) {
                        AppRoot()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedLinkOf(intent)?.let { sharedLink = it }
    }

    @Composable
    fun AppRoot() {
        // #101: PairingState distinguishes "never paired" from "paired, but
        // the token can't be decrypted" — collectAsState's own `initial`
        // (before DataStore's first real emission) is the only remaining
        // `null`, since Prefs.pairing() itself never emits one.
        val pairingState by Prefs.pairing(this).collectAsState(initial = null)
        val treeUri by Prefs.treeUri(this).collectAsState(initial = null)
        // Set on CredentialsUnreadableScreen's "Pair again" tap and checked
        // ahead of the plain NotPaired branch below, so the wizard still
        // opens pre-filled with the (still-known) server URL even once the
        // state itself has moved on from TokenUnreadable.
        var repairServerUrl by remember { mutableStateOf<String?>(null) }
        val state = pairingState // local val: `by` delegates don't smart-cast

        when {
            state == null -> Unit // still loading prefs, render nothing yet
            repairServerUrl != null -> EnrollmentWizard(
                initialServerUrl = repairServerUrl!!,
                onEnrolled = { url, token ->
                    lifecycleScope.launch {
                        Prefs.setPairing(this@MainActivity, url, token)
                        Prefs.setRecoveryPending(this@MainActivity, true)
                    }
                    repairServerUrl = null
                },
            )
            state is Prefs.PairingState.NotPaired -> EnrollmentWizard(onEnrolled = { url, token ->
                lifecycleScope.launch {
                    Prefs.setPairing(this@MainActivity, url, token)
                    // #34: the first sync checks the (soon-to-be-picked) folder
                    // for an existing library and runs recovery if found.
                    Prefs.setRecoveryPending(this@MainActivity, true)
                }
            })
            state is Prefs.PairingState.TokenUnreadable -> CredentialsUnreadableScreen(
                serverUrl = state.serverUrl,
                onRepair = { url -> repairServerUrl = url },
            )
            treeUri == null -> FolderPickerScreen(onPicked = { uri ->
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                lifecycleScope.launch {
                    Prefs.setTreeUri(this@MainActivity, uri.toString())
                    SyncWorker.schedulePeriodic(this@MainActivity)
                }
                askForNotificationsOnce()
            })
            else -> PairedApp(
                pairing = (state as Prefs.PairingState.Paired).pairing,
                treeUri = treeUri!!,
                onServerUrlChanged = { url -> lifecycleScope.launch { Prefs.setServerUrl(this@MainActivity, url) } },
                onFolderChanged = { newUri ->
                    try {
                        contentResolver.releasePersistableUriPermission(
                            Uri.parse(treeUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    } catch (ignored: Exception) { /* already gone — fine */ }
                    contentResolver.takePersistableUriPermission(
                        newUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    lifecycleScope.launch { Prefs.setTreeUri(this@MainActivity, newUri.toString()) }
                },
                onUnpair = { lifecycleScope.launch { SyncWorker.unpair(this@MainActivity) } },
                // #34: Re-enroll on a persistent 401 — clear the (now-orphaned)
                // pairing to return to pairing/enrollment; the local library +
                // folder stay, which the recovery flow (part 3) builds on.
                onReEnroll = { lifecycleScope.launch { SyncWorker.unpair(this@MainActivity) } },
                sharedLink = SharedLinkIn(sharedLink) { sharedLink = null },
            )
        }
    }

    /**
     * #21: tells sideloading users that Google's developer-verification
     * requirement will stop this app working on certified Android devices.
     *
     * Fires on the first launch after each version bump. That cadence is the
     * library's contract rather than a choice made here: it records the version
     * code it last warned for and shows the dialog whenever the running one is
     * higher, so the warning returns with every release until the requirement
     * itself goes away.
     *
     * The version code comes from PackageInfo, not BuildConfig: only `compose`
     * is enabled in this module's `buildFeatures`, so the upstream sample's
     * `BuildConfig.VERSION_CODE` does not exist here and enabling a build
     * feature for one call would be the larger change. PackageInfoCompat covers
     * the API 28 long-version split; narrowing to Int matches the library's
     * signature and is safe for a code that is hand-incremented.
     */
    private fun warnAboutCertifiedDevices() {
        val info = packageManager.getPackageInfo(packageName, 0)
        FreeDroidWarn.showWarningOnUpgrade(this, PackageInfoCompat.getLongVersionCode(info).toInt())
    }
}
