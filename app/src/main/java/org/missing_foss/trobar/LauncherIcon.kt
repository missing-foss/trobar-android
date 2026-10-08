// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The launcher icon per theme: one activity-alias per theme in the
 * manifest (`.LauncherTroubadour` and so on), each with its theme's adaptive
 * icon, and exactly one enabled. Troubadour's is enabled in the manifest and
 * the others are not, so the default state needs no change at all.
 *
 * Disabling the alias the app was opened through destroys that activity,
 * DONT_KILL_APP or not, so the icon changes only once the whole app is in
 * the background: changed at once, the app would close under the user. */
internal object LauncherIcon {
    private var following = false

    /** Applies the stored setting each time the app leaves the foreground.
     * Call on the main thread; later calls do nothing. */
    fun followSettingWhenAppLeaves(context: Context) {
        if (following) return
        following = true
        val app = context.applicationContext
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                owner.lifecycleScope.launch {
                    val theme = BrandTheme.of(Prefs.brandTheme(app).first())
                    show(app, wanted(Prefs.matchIconToTheme(app).first(), theme))
                }
            }
        })
    }

    fun component(context: Context, theme: BrandTheme): ComponentName {
        val suffix = theme.key.replaceFirstChar { it.uppercase() }
        return ComponentName(context.packageName, "${MainActivity::class.java.`package`!!.name}.Launcher$suffix")
    }

    /** The theme whose icon the launcher should show: the brand theme when
     * the user asked for that, Troubadour otherwise. */
    fun wanted(matchTheme: Boolean, theme: BrandTheme): BrandTheme =
        if (matchTheme) theme else BrandTheme.DEFAULT

    /** Shows [theme]'s icon. The new alias is enabled before the old one is
     * disabled, so there is never a moment with no launcher entry, and
     * DONT_KILL_APP keeps the running app alive. Aliases already in the
     * wanted state are left alone: every change makes launchers redraw. */
    fun show(context: Context, theme: BrandTheme) {
        val pm = context.packageManager
        val order = listOf(theme) + BrandTheme.entries.filter { it != theme }
        for (each in order) {
            val wanted = when {
                // Back to the manifest's own state where that is the wanted one.
                each == theme && theme == BrandTheme.DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                each == theme -> PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                each == BrandTheme.DEFAULT -> PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                else -> PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            }
            val name = component(context, each)
            if (pm.getComponentEnabledSetting(name) != wanted) {
                pm.setComponentEnabledSetting(name, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
