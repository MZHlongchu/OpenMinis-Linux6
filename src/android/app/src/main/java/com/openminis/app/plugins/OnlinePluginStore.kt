package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.util.EncryptedPrefsFactory

/**
 * Installed online OpenAPI plugins + encrypted API keys.
 *
 * Adapted from XINCODE-Public PluginStore online flags
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 */
object OnlinePluginStore {
    private const val FLAGS = "plugin_online_flags"
    private const val KEYS_FILE = "plugin_online_keys"

    fun isInstalled(context: Context, pluginId: String): Boolean =
        context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
            .getBoolean(pluginId, false)

    fun installedIds(context: Context): Set<String> {
        val all = context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE).all
        return all.filter { it.value == true }.keys
    }

    fun setInstalled(context: Context, pluginId: String, installed: Boolean) {
        context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(pluginId, installed)
            .apply()
        if (!installed) setApiKey(context, pluginId, null)
    }

    fun apiKey(context: Context, pluginId: String): String? {
        val prefs = EncryptedPrefsFactory.safeCreate(context, KEYS_FILE)
        return prefs.getString(pluginId, null)?.takeIf { it.isNotBlank() }
    }

    fun setApiKey(context: Context, pluginId: String, key: String?) {
        val prefs = EncryptedPrefsFactory.safeCreate(context, KEYS_FILE)
        if (key.isNullOrBlank()) prefs.edit().remove(pluginId).apply()
        else prefs.edit().putString(pluginId, key.trim()).apply()
    }

    fun toolDefinitions(context: Context): List<AgentToolDefinition> {
        val installed = installedIds(context)
        if (installed.isEmpty()) return emptyList()
        return PluginRegistry.cached(context)
            .filter { it.id in installed }
            .flatMap { plugin -> plugin.tools.map { OnlineApiTool.definition(plugin, it) } }
    }
}
