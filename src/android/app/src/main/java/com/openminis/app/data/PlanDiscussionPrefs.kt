package com.openminis.app.data

import android.content.Context

/**
 * Session-menu toggle: when on, each user send runs a shared multi-model
 * plan discussion (proposal → 3 rounds of agree/refute → main synthesizes)
 * instead of jumping straight into implementation.
 */
object PlanDiscussionPrefs {
    private const val PREFS = "minis_plan_discussion_prefs"
    private const val KEY_ENABLED = "planDiscussionEnabled"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedEnabled: Boolean = false

    fun prime(context: Context) {
        appContext = context.applicationContext
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(enabled: Boolean) {
        cachedEnabled = enabled
        appContext?.let { prefs(it).edit().putBoolean(KEY_ENABLED, enabled).apply() }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
