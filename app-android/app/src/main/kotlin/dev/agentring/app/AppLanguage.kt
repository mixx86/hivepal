package dev.agentring.app

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language: English unless the user picks Spanish in Settings (whatever the phone's language).
 * Android 13+ keeps it as the per-app language (also in the system's app settings); older versions
 * store it here and every context is wrapped with it.
 */
object AppLanguage {
    val SUPPORTED = listOf("en" to "English", "es" to "Español")
    private const val PREFS = "hivepal_ui"
    private const val KEY = "lang"
    private const val DEFAULT = "en"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= 33) {
            val tag = ctx.getSystemService(LocaleManager::class.java).applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
            if (tag != null) return tag
        }
        return prefs(ctx).getString(KEY, null) ?: DEFAULT
    }

    fun set(ctx: Context, tag: String) {
        prefs(ctx).edit().putString(KEY, tag).apply()
        // On 13+ this also recreates the open activities in the new language.
        if (Build.VERSION.SDK_INT >= 33) ctx.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
    }

    /** First run: English, unless a language was already set for the app (e.g. from the system settings). */
    fun ensureDefault(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains(KEY)) return
        if (Build.VERSION.SDK_INT >= 33) {
            val lm = ctx.getSystemService(LocaleManager::class.java)
            val chosen = lm.applicationLocales.takeIf { !it.isEmpty }?.get(0)?.language
            if (chosen != null) {
                p.edit().putString(KEY, chosen).apply()
                return
            }
            lm.applicationLocales = LocaleList.forLanguageTags(DEFAULT)
        }
        p.edit().putString(KEY, DEFAULT).apply()
    }

    /** Before Android 13: a context in the chosen language (Application and every Activity use it). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val locale = Locale.forLanguageTag(prefs(base).getString(KEY, null) ?: DEFAULT)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }
}
