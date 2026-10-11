package com.openminis.app.browser

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URI
import java.util.UUID

/**
 * Tracks browser history with 7-day retention, mirroring iOS BrowserHistoryStore.
 */
class BrowserHistoryStore private constructor(private val context: Context) {

    companion object {
        private const val TAG = "BrowserHistory"
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
        private const val FILENAME = "browser_history.json"

        /**
         * [T-browser-history-save-debounce-android] record() 由 onPageFinished
         * （UI 线程）调用，原来每次都整表序列化 + writeText。改为脏快照 +
         * debounce 后在 IO 上落盘；一次导航里的连续记录只写一次。
         */
        private const val SAVE_DEBOUNCE_MS = 500L

        @Volatile
        private var instance: BrowserHistoryStore? = null

        fun getInstance(context: Context): BrowserHistoryStore {
            return instance ?: synchronized(this) {
                instance ?: BrowserHistoryStore(context.applicationContext).also { instance = it }
            }
        }
    }

    data class Entry(
        val id: String = UUID.randomUUID().toString(),
        val url: String,
        val title: String,
        val timestamp: Long = System.currentTimeMillis(),
        val domain: String = extractDomain(url),
    )

    private val entries = mutableListOf<Entry>()

    /** [T-browser-history-save-debounce-android] 落盘用的 IO scope（单例，进程级）。 */
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [T-browser-save-single-writer-android] 单写者 debounce 落盘器：快照在
     * **调用者线程**上取（[entries] 只在调用者线程被改，跨线程读会 CME），写盘
     * 在 [saveScope] 的 IO 上，500ms 内的连续请求合并成一次写。旧实现"先置快照、
     * 再看 saveJob?.isActive"在 job 已越过 delay、正在写旧快照的窗口里会丢更新
     * （新快照已置脏但没人落盘），队列化后不再存在该窗口。
     */
    private val saveWriter = DebouncedSnapshotWriter<String>(
        saveScope, Dispatchers.IO, SAVE_DEBOUNCE_MS,
    ) { writeJson(it) }

    init {
        load()
    }

    fun record(url: String, title: String) {
        if (url.isEmpty()) return
        // Deduplicate consecutive visits to same URL
        if (entries.lastOrNull()?.url == url) return

        entries.add(Entry(url = url, title = title))
        pruneOld()
        save()
    }

    fun getEntries(): List<Entry> = entries.sortedByDescending { it.timestamp }

    fun search(query: String): List<Entry> {
        if (query.isBlank()) return getEntries()
        val q = query.lowercase()
        return entries.filter {
            it.title.lowercase().contains(q) || it.url.lowercase().contains(q)
        }.sortedByDescending { it.timestamp }
    }

    fun groupedByDay(): Map<String, List<Entry>> {
        val cal = java.util.Calendar.getInstance()
        val today = java.util.Calendar.getInstance()
        val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }

        return getEntries().groupBy { entry ->
            cal.timeInMillis = entry.timestamp
            when {
                cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                    cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> "Today"
                cal.get(java.util.Calendar.YEAR) == yesterday.get(java.util.Calendar.YEAR) &&
                    cal.get(java.util.Calendar.DAY_OF_YEAR) == yesterday.get(java.util.Calendar.DAY_OF_YEAR) -> "Yesterday"
                else -> {
                    val month = cal.get(java.util.Calendar.MONTH) + 1
                    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
                    "$month/${day}"
                }
            }
        }
    }

    /** Get unique domains from history (for cookie domain listing). */
    fun uniqueDomains(): List<String> {
        return entries.map { it.domain }.filter { it.isNotEmpty() }.distinct().sorted()
    }

    fun clear() {
        entries.clear()
        // 用户主动清空：立即同步落盘，不留 debounce 窗口（否则进程被杀会看到历史
        // "回魂"）。flush 会等在飞的 debounce 写者写完再写这份空快照，因此不会再
        // 出现"旧快照后落盘把清空结果盖掉"。[T-browser-save-single-writer-android]
        saveWriter.flush(buildSnapshot())
    }

    private fun pruneOld() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeAll { it.timestamp < cutoff }
    }

    /**
     * [T-browser-history-save-debounce-android] 请求落盘。快照在**调用者线程**上取
     * （[entries] 只在调用者线程被改，跨线程读会 CME），落盘交给 [saveWriter] 的
     * 单写者队列（500ms 内的多次请求合并成一次写）。
     */
    private fun save() {
        saveWriter.request(buildSnapshot())
    }

    private fun buildSnapshot(): String {
        val array = JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("id", entry.id)
            obj.put("url", entry.url)
            obj.put("title", entry.title)
            obj.put("timestamp", entry.timestamp)
            obj.put("domain", entry.domain)
            array.put(obj)
        }
        return array.toString()
    }

    private fun writeJson(json: String) {
        try {
            val file = File(context.filesDir, FILENAME)
            file.writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save history: ${e.message}")
        }
    }

    private fun load() {
        try {
            val file = File(context.filesDir, FILENAME)
            if (!file.exists()) return
            val array = JSONArray(file.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                entries.add(Entry(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    url = obj.optString("url", ""),
                    title = obj.optString("title", ""),
                    timestamp = obj.optLong("timestamp", 0),
                    domain = obj.optString("domain", ""),
                ))
            }
            pruneOld()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load history: ${e.message}")
        }
    }
}

private fun extractDomain(url: String): String {
    return try {
        URI(url).host ?: ""
    } catch (_: Exception) {
        ""
    }
}
