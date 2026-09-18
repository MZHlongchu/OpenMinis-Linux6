package com.openminis.app.tools

import android.content.Context

/**
 * User-configurable search backend for [WebSearchTool].
 * DuckDuckGo stays the default (no key). SearXNG / Bing are opt-in.
 */
object WebSearchSettings {
    const val PREFS = "web_search_prefs"
    const val KEY_ENGINE = "engine"
    const val KEY_SEARXNG_URL = "searxng_url"
    const val KEY_BING_KEY = "bing_key"
    const val KEY_FALLBACK = "fallback_ddg"

    enum class Engine(val id: String) {
        DDG("ddg"),
        SEARXNG("searxng"),
        BING("bing"),
        ;

        companion object {
            fun fromId(raw: String?): Engine =
                entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: DDG
        }
    }

    fun engine(context: Context): Engine =
        Engine.fromId(prefs(context).getString(KEY_ENGINE, Engine.DDG.id))

    fun setEngine(context: Context, engine: Engine) {
        prefs(context).edit().putString(KEY_ENGINE, engine.id).apply()
    }

    fun searxngUrl(context: Context): String =
        prefs(context).getString(KEY_SEARXNG_URL, "")?.trim().orEmpty()

    fun setSearxngUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SEARXNG_URL, url.trim()).apply()
    }

    fun bingKey(context: Context): String =
        prefs(context).getString(KEY_BING_KEY, "")?.trim().orEmpty()

    fun setBingKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_BING_KEY, key.trim()).apply()
    }

    fun fallbackEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FALLBACK, true)

    fun setFallbackEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FALLBACK, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
