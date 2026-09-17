package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
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
            "Does not require an API key.",
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

    fun execute(argsJson: String): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val query = args.optString("query", "").trim()
            val toolTitle = args.optString("tool_title", NAME)
            val max = args.optInt("max_results", 5).coerceIn(1, MAX_RESULTS)
            if (query.isEmpty()) {
                return ToolExecutionResult("query is required", success = false, toolTitle = toolTitle)
            }
            val html = fetch(query) ?: return ToolExecutionResult(
                "web_search failed: empty response from DuckDuckGo. Try browser_use on a specific URL.",
                success = false,
                toolTitle = toolTitle,
            )
            val results = parseHtml(html, max)
            if (results.isEmpty()) {
                return ToolExecutionResult(
                    "No results for \"$query\". Try a shorter query or open a known URL with browser_use.",
                    success = true,
                    toolTitle = toolTitle,
                )
            }
            ToolExecutionResult(format(query, results), success = true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("web_search failed: ${e.message}", success = false)
        }
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

    private fun format(query: String, results: List<Result>): String = buildString {
        appendLine("web_search results for \"$query\" (${results.size}):")
        results.forEachIndexed { i, r ->
            appendLine()
            appendLine("${i + 1}. ${r.title}")
            appendLine("   ${r.url}")
            if (r.snippet.isNotBlank()) appendLine("   ${r.snippet}")
        }
    }

    private fun fetch(query: String): String? {
        val q = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = URL("https://html.duckduckgo.com/html/?q=$q")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; OpenMinis-Linux) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
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
