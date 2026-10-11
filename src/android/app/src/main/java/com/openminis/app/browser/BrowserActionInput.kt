package com.openminis.app.browser

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsed input for a browser_use tool call, mirroring iOS BrowserActionInput.
 */
data class BrowserActionInput(
    val action: BrowserAction,
    val url: String? = null,
    val selector: String? = null,
    val text: String? = null,
    val coordinateX: Int? = null,
    val coordinateY: Int? = null,
    val direction: ScrollDirection? = null,
    val amount: Int? = null,
    val script: String? = null,
    val userAgent: UserAgentProfile? = null,
    val maxDepth: Int? = null,
    val tabId: Int? = null,
    /** Viewport width override (set_viewport action). */
    val viewportWidth: Int? = null,
    /** Viewport height override (set_viewport action). */
    val viewportHeight: Int? = null,
    /** When true on set_viewport, drop the session-level viewport override. */
    val reset: Boolean = false,
    /** Cookie-name / scroll_and_collect keyword filter. */
    val keywords: List<String>? = null,
    /** Fuzzy-match cookie names for get_cookies. */
    val fuzzy: Boolean = false,
    /** Item selector for scroll_and_collect (each matching element is captured). */
    val itemSelector: String? = null,
    /** Scroll iterations for scroll_and_collect. */
    val scrollCount: Int? = null,
    /** Milliseconds to wait (wait_for_dom_stable). */
    val timeoutMs: Int? = null,
    /** When true on screenshot, stretch viewport to full document.scrollHeight before capture. */
    val fullPage: Boolean = false,
    /**
     * Cookies to write (set_cookies). Each entry has keys name + value
     * (required) and optional domain, path, secure, http_only, expires
     * (Unix seconds). Values are loosely typed (String / Boolean / Number).
     */
    val cookies: List<Map<String, Any?>>? = null,
) {
    companion object {
        fun parse(json: String): BrowserActionInput? {
            return try {
                val obj = JSONObject(json)
                val actionStr = obj.optString("action", "")
                val action = BrowserAction.fromString(actionStr) ?: return null

                BrowserActionInput(
                    action = action,
                    url = obj.optString("url").ifEmpty { null },
                    selector = obj.optString("selector").ifEmpty { null },
                    text = obj.optString("text").ifEmpty { null },
                    coordinateX = if (obj.has("coordinate_x")) obj.optInt("coordinate_x") else null,
                    coordinateY = if (obj.has("coordinate_y")) obj.optInt("coordinate_y") else null,
                    direction = obj.optString("direction").ifEmpty { null }?.let { ScrollDirection.fromString(it) },
                    amount = if (obj.has("amount")) obj.optInt("amount") else null,
                    script = obj.optString("script").ifEmpty { null },
                    userAgent = obj.optString("user_agent").ifEmpty { null }?.let { UserAgentProfile.fromString(it) },
                    maxDepth = if (obj.has("max_depth")) obj.optInt("max_depth") else null,
                    tabId = if (obj.has("tab_id")) obj.optInt("tab_id") else null,
                    viewportWidth = if (obj.has("viewport_width")) obj.optInt("viewport_width") else null,
                    viewportHeight = if (obj.has("viewport_height")) obj.optInt("viewport_height") else null,
                    reset = obj.optBoolean("reset", false),
                    keywords = obj.optJSONArray("keywords")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                            .takeIf { it.isNotEmpty() }
                    },
                    fuzzy = obj.optBoolean("fuzzy", false),
                    itemSelector = obj.optString("item_selector").ifEmpty { null },
                    scrollCount = if (obj.has("scroll_count")) obj.optInt("scroll_count") else null,
                    // [T-browser-domstable-timeout-unit] `timeout` 有两个生产方：
                    // agent 工具的 schema 声明它是**秒**（AgentTools "Timeout in
                    // seconds … default: 10"），CLI offload 的 `--timeout <ms>` 则是
                    // **毫秒**（BrowserUseOffloadHandler 帮助文本）。执行器
                    // （normalizedExecArgs）会把 agent 侧的 `timeout` 按别名补出
                    // `timeout_seconds`，所以：有 timeout_seconds 就按秒换算，否则
                    // 保持 `timeout` 的毫秒语义不动（CLI 路径不受影响）。
                    timeoutMs = if (obj.has("timeout_seconds")) {
                        // 先夹住再乘，避免恶意/极端秒数把 Int 乘溢出。
                        obj.optInt("timeout_seconds").takeIf { it > 0 }?.coerceAtMost(3_600)?.times(1000)
                    } else if (obj.has("timeout")) {
                        obj.optInt("timeout")
                    } else {
                        null
                    },
                    fullPage = obj.optBoolean("full_page", false),
                    cookies = parseCookies(obj),
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * `cookies` may arrive either as a real JSON array or — because the tool
         * schema can only type it as a string — as a JSON-encoded STRING like
         * `"[{\"name\":...}]"`. Accept both so a schema-faithful model (which
         * emits the string form) isn't silently dropped to empty. Returns null
         * when absent / unparseable / empty.
         */
        private fun parseCookies(obj: JSONObject): List<Map<String, Any?>>? {
            val arr: JSONArray? = obj.optJSONArray("cookies")
                ?: obj.optString("cookies").takeIf { it.isNotBlank() }
                    ?.let { runCatching { JSONArray(it) }.getOrNull() }
            arr ?: return null
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { co ->
                    co.keys().asSequence().associateWith { k -> co.get(k) }
                }
            }.takeIf { it.isNotEmpty() }
        }
    }
}
