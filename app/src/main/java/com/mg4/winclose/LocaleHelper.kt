package com.mg4.winclose

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    const val PREF_NAME = "winclose_prefs"
    const val PREF_LANG = "lang"
    const val DEFAULT_LANG = "fr"

    fun getLang(context: Context): String =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(PREF_LANG, DEFAULT_LANG) ?: DEFAULT_LANG

    fun setLang(context: Context, lang: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_LANG, lang).apply()
    }

    /** Wrap a context with the user-selected locale. */
    fun wrap(base: Context): Context {
        val lang = getLang(base)
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
