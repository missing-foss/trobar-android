// SPDX-FileCopyrightText: 2026 missing-foss
// SPDX-License-Identifier: GPL-3.0-or-later
package org.missing_foss.trobar

import android.app.Activity
import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.util.Locale

/** The app's own language choice, and the one place that answers "which
 * language is the app in".
 *
 * API 33+: the system owns the per-app language. The choice is made on the
 * system's own page ([systemSettingsIntent]); the system applies it to the
 * process and recreates the activity, so there is nothing to store or apply
 * here.
 *
 * API 26-32: there is no system per-app language, so the app keeps the choice
 * itself and applies it to the activity in [wrap]. AppCompat's
 * setApplicationLocales() cannot do this here: it only re-localizes AppCompat
 * activities, and only reaches the system's LocaleManager through one, while
 * MainActivity is a plain ComponentActivity on a framework theme. */
internal object AppLanguage {
    const val SYSTEM = "system"

    /** The explicit choices, as language tags. Keep in step with
     * res/xml/locales_config.xml, which lists the same set for the system. */
    val SUPPORTED = listOf("en", "fr")

    private const val PREFS_NAME = "app_language"
    private const val KEY_TAG = "tag"

    /** True where the system's per-app language exists and the app defers to
     * it; false where the in-app picker is the only way to choose. */
    val systemManaged: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** The explicit choice, or [SYSTEM] when the app follows the device. */
    fun choice(context: Context): String =
        if (systemManaged) {
            Api33.applicationLocales(context).takeUnless { it.isEmpty }?.get(0)?.language ?: SYSTEM
        } else {
            stored(context)
        }

    /** The language the app's UI is showing, as a bare language tag: the
     * explicit choice, else the device's. Sent to the server as
     * Accept-Language and used to format dates, so neither drifts from what
     * the screen shows. */
    fun tag(context: Context): String =
        choice(context).takeUnless { it == SYSTEM } ?: Locale.getDefault().language

    fun locale(context: Context): Locale = Locale.forLanguageTag(tag(context))

    /** API 26-32 only: store [tag] and recreate the activity so it takes
     * effect at once. Written synchronously, so the recreated activity's
     * [wrap] already reads the new value. */
    fun choose(context: Context, tag: String) {
        check(!systemManaged) { "On API 33+ the language is chosen in the system settings" }
        require(tag == SYSTEM || tag in SUPPORTED) { "Unsupported language: $tag" }
        prefs(context).edit().putString(KEY_TAG, tag).commit()
        context.findActivity()?.recreate()
    }

    /** For Activity.attachBaseContext: on API 26-32, a context whose
     * resources use the stored choice. Unchanged on API 33+ and under
     * [SYSTEM]. Locale.getDefault() is deliberately left alone: the framework
     * resets it on every configuration change, so nothing may rely on it
     * carrying the choice. */
    fun wrap(base: Context): Context {
        if (systemManaged) return base
        val tag = stored(base)
        if (tag == SYSTEM) return base
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(config)
    }

    /** API 33+: the system's language page for this app. Falls back to the
     * app's details page where a vendor build lacks the dedicated one. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun openSystemSettings(context: Context) {
        val uri = Uri.fromParts("package", context.packageName, null)
        try {
            context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, uri))
        } catch (ignored: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
        }
    }

    private fun stored(context: Context): String =
        prefs(context).getString(KEY_TAG, null)?.takeIf { it in SUPPORTED } ?: SYSTEM

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun applicationLocales(context: Context): LocaleList =
            context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: LocaleList.getEmptyLocaleList()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
