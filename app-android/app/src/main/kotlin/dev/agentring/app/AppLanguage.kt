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
    /** Each language and its name in the app's current language (English · Spanish, Inglés · Español). */
    val SUPPORTED = listOf("en" to R.string.language_english, "es" to R.string.language_spanish)
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

    /**
     * A context in the chosen language (the Application and every Activity use it). On Android 13+ the
     * system does this too, but only after it applies a change: this covers the very first screen.
     */
    fun wrap(base: Context): Context {
        val locale = Locale.forLanguageTag(current(base))
        if (Build.VERSION.SDK_INT < 33) Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        if (config.locales[0]?.language == locale.language) return base
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
