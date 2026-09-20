package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Key-free web search via DuckDuckGo HTML. Overlay already labeled this
 * tool; the agent schema never exposed it. Prefer this over spinning up
 * [browser_use] just to look up a fact.
 */
object WebSearchTool {
    const val NAME = "web_search"
    private const val MAX_RESULTS = 8
    private const val TIMEOUT_MS = 15_000

    data class Result(
        val title: String,
        val url: String,
        val snippet: String,
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Search the public web and return titles, URLs, and snippets. " +
            "Use this for facts, docs, news, and package versions instead of opening a browser. " +
            "Follow up with browser_use only when you need to interact with a specific page. " +
            "Uses Settings → Web search (DuckDuckGo by default; optional SearXNG or Bing). " +
            "Falls back to DuckDuckGo, then suggest browser_use for a specific URL.",
        parameters = mapOf(
            "tool_title" to AgentToolParam(
                "string",
                "A concise 5-10 word summary shown to the user (e.g. 'Search Android 15 release notes'). Use the same language as the user.",
            ),
            "query" to AgentToolParam("string", "Search query. Be specific; include version numbers or site: filters when useful."),
            "max_results" to AgentToolParam("integer", "How many results to return (default 5, max 8)."),
        ),
        required = listOf("tool_title", "query"),
        propertyOrdering = listOf("tool_title", "query", "max_results"),
    )

    fun execute(argsJson: String, context: Context? = null): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val query = args.optString("query", "").trim()
            val toolTitle = args.optString("tool_title", NAME)
            val max = args.optInt("max_results", 5).coerceIn(1, MAX_RESULTS)
            if (query.isEmpty()) {
                return ToolExecutionResult("query is required", success = false, toolTitle = toolTitle)
            }
            val preferred = context?.let { WebSearchSettings.engine(it) } ?: WebSearchSettings.Engine.DDG
            val allowFallback = context?.let { WebSearchSettings.fallbackEnabled(it) } ?: true
            val engines = mutableListOf(preferred)
            if (allowFallback && preferred != WebSearchSettings.Engine.DDG) {
                engines += WebSearchSettings.Engine.DDG
            }
            var lastError: String? = null
            var used = preferred
            var results: List<Result> = emptyList()
            for (engine in engines) {
                used = engine
                val attempt = search(engine, query, max, context)
                if (attempt.results.isNotEmpty()) {
                    results = attempt.results
                    lastError = null
                    break
                }
                lastError = attempt.error
            }
            if (results.isEmpty()) {
                return ToolExecutionResult(
                    "web_search failed for \"$query\" via ${preferred.id}: ${lastError ?: "no results"}. " +
                        "Try a shorter query or open a known URL with browser_use.",
                    success = false,
                    toolTitle = toolTitle,
                )
            }
            ToolExecutionResult(format(query, results, used.id), success = true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("web_search failed: ${e.message}", success = false)
        }
    }

    private data class Attempt(val results: List<Result>, val error: String?)

    private fun search(
        engine: WebSearchSettings.Engine,
        query: String,
        max: Int,
        context: Context?,
    ): Attempt {
        return try {
            when (engine) {
                WebSearchSettings.Engine.DDG -> {
                    val html = fetchUrl("https://html.duckduckgo.com/html/?q=${enc(query)}", context = context)
                        ?: return Attempt(emptyList(), "empty response from DuckDuckGo")
                    val parsed = parseHtml(html, max)
                    Attempt(parsed, if (parsed.isEmpty()) "DuckDuckGo returned no cards" else null)
                }
                WebSearchSettings.Engine.SEARXNG -> {
                    val base = context?.let { WebSearchSettings.searxngUrl(it) }.orEmpty().trimEnd('/')
                    if (base.isEmpty()) return Attempt(emptyList(), "SearXNG URL is not configured")
                    val endpoint = if (base.endsWith("/search")) base else "$base/search"
                    val body = fetchUrl("$endpoint?q=${enc(query)}&format=json", context = context)
                        ?: return Attempt(emptyList(), "empty response from SearXNG")
                    val parsed = parseSearxJson(body, max)
                    Attempt(parsed, if (parsed.isEmpty()) "SearXNG returned no results" else null)
                }
                WebSearchSettings.Engine.BING -> {
                    val key = context?.let { WebSearchSettings.bingKey(it) }.orEmpty()
                    if (key.isEmpty()) return Attempt(emptyList(), "Bing API key is not configured")
                    val body = fetchUrl(
                        "https://api.bing.microsoft.com/v7.0/search?q=${enc(query)}&count=$max",
                        extraHeaders = mapOf(
                            "Ocp-Apim-Subscription-Key" to key,
                            "Accept" to "application/json",
                        ),
                        context = context,
                    ) ?: return Attempt(emptyList(), "empty response from Bing")
                    val parsed = parseBingJson(body, max)
                    Attempt(parsed, if (parsed.isEmpty()) "Bing returned no results" else null)
                }
                WebSearchSettings.Engine.CUSTOM -> {
                    val template = context?.let { WebSearchSettings.customUrl(it) }.orEmpty()
                    if (template.isEmpty()) return Attempt(emptyList(), "Custom search URL is not configured")
                    val key = context?.let { WebSearchSettings.customKey(it) }.orEmpty()
                    val headerName = context?.let { WebSearchSettings.customKeyHeader(it) }.orEmpty()
                    val endpoint = expandCustomUrl(template, query, key)
                    val headers = linkedMapOf("Accept" to "application/json, text/html")
                    if (key.isNotEmpty() && !template.contains("{key}", ignoreCase = true)) {
                        val h = headerName.ifBlank { "Authorization" }
                        val v = if (h.equals("Authorization", ignoreCase = true) &&
                            !key.startsWith("Bearer ", ignoreCase = true)
                        ) {
                            "Bearer $key"
                        } else {
                            key
                        }
                        headers[h] = v
                    }
                    val body = fetchUrl(endpoint, extraHeaders = headers, context = context)
                        ?: return Attempt(emptyList(), "empty response from custom search")
                    val trimmed = body.trimStart()
                    val parsed = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        parseGenericSearchJson(body, max)
                    } else {
                        parseHtml(body, max)
                    }
                    Attempt(parsed, if (parsed.isEmpty()) "Custom search returned no results" else null)
                }
            }
        } catch (e: Exception) {
            Attempt(emptyList(), e.message ?: engine.id)
        }
    }

    internal fun parseSearxJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val root = JSONObject(json)
        val arr = root.optJSONArray("results") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            val title = o.optString("title").trim()
            if (url.isBlank() || title.isBlank()) continue
            out += Result(title, url, o.optString("content").trim())
            if (out.size >= max) break
        }
        return out
    }

    internal fun parseBingJson(json: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val pages = JSONObject(json).optJSONObject("webPages") ?: return out
        val arr = pages.optJSONArray("value") ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").trim()
            val title = o.optString("name").trim()
            if (url.isBlank() || title.isBlank()) continue
            out += Result(title, url, o.optString("snippet").trim())
            if (out.size >= max) break
        }
        return out
    }

    internal fun expandCustomUrl(template: String, query: String, key: String = ""): String {
        var url = template.trim()
        val q = enc(query)
        val k = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
        url = url.replace("{query}", q, ignoreCase = true)
            .replace("{q}", q, ignoreCase = true)
            .replace("{key}", k, ignoreCase = true)
        if (!template.contains("{query}", ignoreCase = true) &&
            !template.contains("{q}", ignoreCase = true)
        ) {
            val sep = if (url.contains('?')) "&" else "?"
            url = "$url${sep}q=$q"
        }
        return url
    }

    internal fun parseGenericSearchJson(json: String, max: Int): List<Result> {
        val trimmed = json.trim()
        if (trimmed.startsWith("[")) {
            return parseResultArray(JSONArray(trimmed), max)
        }
        val fromSearx = parseSearxJson(json, max)
        if (fromSearx.isNotEmpty()) return fromSearx
        val fromBing = parseBingJson(json, max)
        if (fromBing.isNotEmpty()) return fromBing
        val root = JSONObject(json)
        for (key in arrayOf("items", "data", "organic", "organic_results", "results")) {
            val arr = root.optJSONArray(key) ?: continue
            val parsed = parseResultArray(arr, max)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    private fun parseResultArray(arr: JSONArray, max: Int): List<Result> {
        val out = ArrayList<Result>(max)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("url").ifBlank { o.optString("link") }
                .ifBlank { o.optString("href") }.trim()
            val title = o.optString("title").ifBlank { o.optString("name") }.trim()
            if (url.isBlank() || title.isBlank()) continue
            val snippet = o.optString("snippet").ifBlank { o.optString("content") }
                .ifBlank { o.optString("description") }.trim()
            out += Result(title, url, snippet)
            if (out.size >= max) break
        }
        return out
    }

    internal fun parseHtml(html: String, max: Int = MAX_RESULTS): List<Result> {
        val out = ArrayList<Result>(max)
        val seen = HashSet<String>()
        val resultBlock = Regex(
            """class="result(?:__body)?"[\s\S]{0,2500}?class="result__a"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>[\s\S]{0,1200}?class="result__snippet"[^>]*>([\s\S]*?)</(?:a|td|div)>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in resultBlock.findAll(html)) {
            val url = decodeDuckLink(htmlUnescape(m.groupValues[1]))
            val title = stripTags(m.groupValues[2])
            val snippet = stripTags(m.groupValues[3])
            if (url.isBlank() || title.isBlank()) continue
            if (!seen.add(url)) continue
            out += Result(title, url, snippet)
            if (out.size >= max) return out
        }
        if (out.isNotEmpty()) return out
        val lite = Regex(
            """<a[^>]+rel="nofollow"[^>]+href="(https?://[^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE,
        )
        for (m in lite.findAll(html)) {
            val url = htmlUnescape(m.groupValues[1])
            if (url.contains("duckduckgo.com", ignoreCase = true)) continue
            val title = stripTags(m.groupValues[2])
            if (url.isBlank() || title.isBlank()) continue
            if (!seen.add(url)) continue
            out += Result(title, url, "")
            if (out.size >= max) break
        }
        return out
    }

    internal fun decodeDuckLink(raw: String): String {
        val href = htmlUnescape(raw).trim()
        val normalized = when {
            href.startsWith("//") -> "https:$href"
            href.startsWith("/l/?") -> "https://duckduckgo.com$href"
            else -> href
        }
        val marker = "uddg="
        val idx = normalized.indexOf(marker)
        if (idx >= 0) {
            val start = idx + marker.length
            val end = normalized.indexOf('&', start).let { if (it < 0) normalized.length else it }
            val encoded = normalized.substring(start, end)
            if (encoded.isNotBlank()) {
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            }
        }
        return normalized
    }

    private fun format(query: String, results: List<Result>, engine: String = "ddg"): String = buildString {
        appendLine("web_search ($engine) results for \"$query\" (${results.size}):")
        results.forEachIndexed { i, r ->
            appendLine()
            appendLine("${i + 1}. ${r.title}")
            appendLine("   ${r.url}")
            if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
        }
    }

    private fun enc(query: String): String =
        URLEncoder.encode(query, StandardCharsets.UTF_8.name())

    private fun httpUserAgent(context: Context?): String {
        val major = context?.let {
            runCatching { com.openminis.app.browser.WebViewEngine.snapshot(it).major }.getOrNull()
        }
        val chrome = major?.let { "Chrome/$it.0.0.0" }
            ?: "Chrome/${com.openminis.app.browser.WebViewEngine.TARGET_MAJOR}.0.0.0"
        return "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; OpenMinis-Linux) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) $chrome Mobile Safari/537.36"
    }

    private fun fetchUrl(
        urlString: String,
        extraHeaders: Map<String, String> = emptyMap(),
        context: Context? = null,
    ): String? {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", httpUserAgent(context))
            setRequestProperty("Accept", extraHeaders["Accept"] ?: "text/html,application/xhtml+xml,application/json")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            stream?.use { inp ->
                BufferedReader(InputStreamReader(inp, StandardCharsets.UTF_8)).readText()
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun stripTags(raw: String): String =
        htmlUnescape(raw.replace(Regex("<[^>]+>"), " "))
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun htmlUnescape(raw: String): String =
        raw.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
}
