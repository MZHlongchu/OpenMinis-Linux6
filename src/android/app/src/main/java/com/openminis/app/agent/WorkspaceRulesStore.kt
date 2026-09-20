package com.openminis.app.agent

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class WorkspaceRule(
    val id: String,
    val name: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * File-backed rule library: `<id>.md` + `<id>.json` + `state.json`.
 * File library only; active flags are stored in state.json and are not
 * injected into the chat system prompt.
 */
object WorkspaceRulesStore {
    private const val DIR = "workspace_rules"
    const val MAX_BODY = 65_536

    @Volatile private var appContext: Context? = null
    @Volatile private var rootOverride: File? = null

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun prime(context: Context) {
        appContext = context.applicationContext
        root().mkdirs()
        _revision.value++
    }

    internal fun setRootForTests(dir: File) {
        rootOverride = dir
        dir.mkdirs()
        _revision.value++
    }

    fun list(): List<WorkspaceRule> {
        val dir = root()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") && f.name != "state.json" }
            .orEmpty()
            .mapNotNull { meta -> loadOne(meta) }
            .sortedBy { it.name.lowercase() }
    }

    fun activeIds(): Set<String> {
        val raw = File(root(), "state.json")
        if (!raw.isFile) return emptySet()
        return try {
            val arr = JSONObject(raw.readText()).optJSONArray("active") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { id -> id.isNotBlank() } }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun isActive(id: String): Boolean = id in activeIds()

    fun setActive(id: String, active: Boolean) {
        val next = activeIds().toMutableSet()
        if (active) next += id else next -= id
        val arr = JSONArray()
        next.forEach { arr.put(it) }
        atomicWrite(File(root(), "state.json"), JSONObject().put("active", arr).toString())
        _revision.value++
    }

    sealed class SaveResult {
        data class Ok(val rule: WorkspaceRule) : SaveResult()
        data class Error(val code: String) : SaveResult()
    }

    fun save(name: String, body: String, id: String? = null): SaveResult {
        val trimmedName = name.trim()
        val trimmedBody = body.trim()
        if (trimmedName.isBlank() || trimmedBody.isBlank()) return SaveResult.Error("empty")
        if (trimmedBody.length > MAX_BODY) return SaveResult.Error("too_long")
        val now = System.currentTimeMillis()
        val existing = id?.let { load(it) }
        val rule = WorkspaceRule(
            id = existing?.id ?: freshId(trimmedName),
            name = trimmedName,
            body = trimmedBody,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        writeRule(rule)
        _revision.value++
        return SaveResult.Ok(rule)
    }

    fun delete(id: String) {
        File(root(), "$id.md").delete()
        File(root(), "$id.json").delete()
        setActive(id, false)
    }

    private fun load(id: String): WorkspaceRule? = loadOne(File(root(), "$id.json"))

    private fun loadOne(meta: File): WorkspaceRule? {
        if (!meta.isFile) return null
        return try {
            val o = JSONObject(meta.readText())
            val id = o.optString("id").ifBlank { meta.name.removeSuffix(".json") }
            val bodyFile = File(root(), "$id.md")
            val body = if (bodyFile.isFile) bodyFile.readText() else ""
            WorkspaceRule(
                id = id,
                name = o.optString("name").ifBlank { id },
                body = body,
                createdAt = o.optLong("createdAt", meta.lastModified()),
                updatedAt = o.optLong("updatedAt", meta.lastModified()),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun writeRule(rule: WorkspaceRule) {
        val dir = root()
        dir.mkdirs()
        val meta = JSONObject()
            .put("id", rule.id)
            .put("name", rule.name)
            .put("createdAt", rule.createdAt)
            .put("updatedAt", rule.updatedAt)
            .toString()
        atomicWrite(File(dir, "${rule.id}.json"), meta)
        atomicWrite(File(dir, "${rule.id}.md"), rule.body)
    }

    private fun freshId(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
            .trim('-')
            .ifBlank { "rule" }
        return "$slug-${System.currentTimeMillis().toString(36).takeLast(6)}"
    }

    private fun root(): File {
        rootOverride?.let { return it }
        val ctx = appContext
        return if (ctx != null) File(ctx.filesDir, DIR) else File(DIR)
    }

    private fun atomicWrite(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.writeText(text)
            tmp.delete()
        }
    }
}
