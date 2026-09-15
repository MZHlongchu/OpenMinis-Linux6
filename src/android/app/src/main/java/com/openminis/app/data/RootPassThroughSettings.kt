package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences

object RootPassThroughSettings {
    private const val PREFS = "root_passthrough_prefs"
    private const val KEY_ENABLED = "root_passthrough_enabled"

    @Volatile
    private var cachedEnabled: Boolean = false

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prime(context: Context) {
        cachedEnabled = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun isEnabled(): Boolean = cachedEnabled

    fun setEnabled(context: Context, enabled: Boolean) {
        cachedEnabled = enabled
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
