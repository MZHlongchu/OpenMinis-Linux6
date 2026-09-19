package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.tools.FetchUrlGuard
import com.openminis.app.tools.ToolExecutionResult
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Execute one remote OpenAPI-lite operation as an agent tool.
 *
 * Adapted from XINCODE-Public OnlineApiTool
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 * SSRF via [FetchUrlGuard]; auth header is never returned to the model.
 */
object OnlineApiTool {
    const val PREFIX = "online_"
    private const val UA = "OpenMinis-Agent/1.26"
    private const val MAX_OUTPUT = 4_000

    fun isOnline(name: String): Boolean = name.startsWith(PREFIX)

    fun toolName(pluginId: String, specName: String): String = "${PREFIX}${pluginId}__$specName"

    fun definition(
        plugin: PluginRegistry.RemotePlugin,
        spec: PluginRegistry.RemoteTool,
    ): AgentToolDefinition {
        val params = linkedMapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user. Use the same language as the user.",
            ),
        )
        val required = mutableListOf("tool_title")
        for (p in spec.params) {
            params[p.name] = AgentToolParam(p.type, p.description.ifBlank { p.name })
            if (p.required) required += p.name
        }
        return AgentToolDefinition(
            name = toolName(plugin.id, spec.name),
            description = "[${plugin.name}] ${spec.summary.ifBlank { spec.name }}",
            parameters = params,
            required = required,
            propertyOrdering = params.keys.toList(),
        )
    }

    fun execute(name: String, argsJson: String, context: Context): ToolExecutionResult {
        val parsed = parseName(name) ?: return ToolExecutionResult("Unknown online tool: $name", false)
        val plugin = PluginRegistry.cached(context).find { it.id == parsed.first }
            ?: return ToolExecutionResult("Plugin ${parsed.first} is not in the local catalog. Open Settings → Plugin market.", false)
        if (!OnlinePluginStore.isInstalled(context, plugin.id)) {
            return ToolExecutionResult("Plugin ${plugin.name} is not installed.", false)
        }
        val spec = plugin.tools.find { it.name == parsed.second }
            ?: return ToolExecutionResult("Unknown operation ${parsed.second} on ${plugin.name}", false)
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            JSONObject()
        }
        args.remove("tool_title")
        return runCatching { request(plugin, spec, args, context) }
            .getOrElse { ToolExecutionResult("Cannot reach plugin server: ${it.message?.take(200)}", false) }
    }

    private fun parseName(name: String): Pair<String, String>? {
        if (!name.startsWith(PREFIX)) return null
        val rest = name.removePrefix(PREFIX)
        val idx = rest.indexOf("__")
        if (idx <= 0) return null
        val id = rest.substring(0, idx)
        val op = rest.substring(idx + 2)
        if (id.isBlank() || op.isBlank()) return null
        return id to op
    }

    private fun request(
        plugin: PluginRegistry.RemotePlugin,
        spec: PluginRegistry.RemoteTool,
        args: JSONObject,
        context: Context,
    ): ToolExecutionResult {
        var urlPath = spec.path
        val substituted = mutableSetOf<String>()
        Regex("""\{([a-zA-Z0-9_]+)\}""").findAll(urlPath).forEach { m ->
            val key = m.groupValues[1]
            val v = args.optString(key)
            if (v.isNotBlank()) {
                urlPath = urlPath.replace(m.value, java.net.URLEncoder.encode(v, "UTF-8"))
                substituted.add(key)
            }
        }
        val url = plugin.baseUrl.trimEnd('/') + urlPath
        FetchUrlGuard.blockedReason(url)?.let {
            return ToolExecutionResult("Outbound request blocked: $it", false)
        }
        val method = spec.method.uppercase().ifBlank { "GET" }
        val conn = (URL(buildUrl(url, method, args, substituted)).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = false
            requestMethod = if (method == "PATCH") "POST" else method
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            val header = plugin.authHeader.trim()
            val key = OnlinePluginStore.apiKey(context, plugin.id)
            if (header.isNotBlank() && !key.isNullOrBlank()) {
                setRequestProperty(header, key)
            }
            if (method == "POST" || method == "PUT" || method == "PATCH") {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val body = JSONObject()
                args.keys().forEach { k ->
                    if (k !in substituted) body.putOpt(k, args.opt(k))
                }
                outputStream.bufferedWriter().use { it.write(body.toString()) }
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return when (code) {
            401, 403 -> ToolExecutionResult(
                "Plugin auth failed ($code). Re-enter a valid API key in Settings → Plugin market.",
                false,
            )
            429 -> ToolExecutionResult("Plugin rate-limited (429). Try again later.", false)
            in 200..299 -> ToolExecutionResult(text.take(MAX_OUTPUT), true)
            else -> ToolExecutionResult("Remote HTTP $code: ${text.take(300)}", false)
        }
    }

    private fun buildUrl(
        base: String,
        method: String,
        args: JSONObject,
        substituted: Set<String>,
    ): String {
        if (method == "POST" || method == "PUT" || method == "PATCH") return base
        val sb = StringBuilder(base)
        var first = !base.contains("?")
        args.keys().forEach { k ->
            if (k in substituted || args.isNull(k)) return@forEach
            val v = args.optString(k)
            if (v.isEmpty()) return@forEach
            sb.append(if (first) "?" else "&")
                .append(URLEncoder.encode(k, "UTF-8"))
                .append("=")
                .append(URLEncoder.encode(v, "UTF-8"))
            first = false
        }
        return sb.toString()
    }
}
