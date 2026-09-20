package com.openminis.app.data

import org.json.JSONArray
import org.json.JSONObject

data class PromptTemplate(
    val id: String,
    val name: String,
    val text: String,
    val order: Int = 1,
)

data class PromptTemplateState(
    val templates: List<PromptTemplate> = emptyList(),
    val sessions: Map<String, String> = emptyMap(),
    val defaultTemplate: String = "",
)

object PromptTemplateCodec {
    const val NONE = "none"
    const val MAX_TEXT = 65_536

    fun parse(json: String): PromptTemplateState {
        if (json.isBlank()) return PromptTemplateState()
        return try {
            val root = JSONObject(json)
            val templates = mutableListOf<PromptTemplate>()
            val arr = root.optJSONArray("templates") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val text = o.optString("text")
                if (id.isBlank() || text.isBlank()) continue
                templates += PromptTemplate(
                    id = id,
                    name = o.optString("name").ifBlank { id },
                    text = text,
                    order = o.optInt("order", 1),
                )
            }
            val sessions = mutableMapOf<String, String>()
            val sess = root.optJSONObject("sessions")
            if (sess != null) {
                val keys = sess.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = sess.optString(k)
                    if (k.isNotBlank() && v.isNotBlank()) sessions[k] = v
                }
            }
            PromptTemplateState(
                templates = templates,
                sessions = sessions,
                defaultTemplate = root.optString("defaultTemplate"),
            )
        } catch (_: Exception) {
            PromptTemplateState()
        }
    }

    fun serialize(state: PromptTemplateState): String {
        val arr = JSONArray()
        for (t in state.templates) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("name", t.name)
                    .put("text", t.text)
                    .put("order", t.order),
            )
        }
        val sess = JSONObject()
        for ((k, v) in state.sessions) sess.put(k, v)
        return JSONObject()
            .put("templates", arr)
            .put("sessions", sess)
            .put("defaultTemplate", state.defaultTemplate)
            .toString()
    }

    fun templateById(state: PromptTemplateState, id: String?): PromptTemplate? =
        if (id.isNullOrBlank() || id == NONE) null else state.templates.find { it.id == id }

    fun templateForSession(state: PromptTemplateState, sessionId: String): PromptTemplate? {
        val picked = state.sessions[sessionId]
        if (picked != null) return templateById(state, picked)
        return templateById(state, state.defaultTemplate)
    }

    fun applyToSession(state: PromptTemplateState, sessionId: String, templateId: String): PromptTemplateState {
        val id = if (templateId == NONE || templateId.isBlank()) NONE else templateId
        return state.copy(sessions = state.sessions + (sessionId to id))
    }

    fun setDefault(state: PromptTemplateState, templateId: String): PromptTemplateState {
        val id = if (templateId == NONE || templateId.isBlank()) "" else templateId
        return state.copy(defaultTemplate = id)
    }

    fun delete(state: PromptTemplateState, id: String): PromptTemplateState {
        val templates = state.templates.filter { it.id != id }
        val sessions = state.sessions.mapValues { (_, v) -> if (v == id) NONE else v }
        val default = if (state.defaultTemplate == id) "" else state.defaultTemplate
        return PromptTemplateState(templates, sessions, default)
    }

    fun upsert(
        state: PromptTemplateState,
        name: String,
        text: String,
        order: Int = 1,
        id: String? = null,
    ): PromptTemplateState {
        val existing = id?.takeIf { it.isNotBlank() }?.let { want -> state.templates.find { it.id == want } }
        val template = if (existing != null) {
            existing.copy(name = name, text = text, order = order)
        } else {
            PromptTemplate(id = freshId(name), name = name, text = text, order = order)
        }
        val templates = if (existing != null) {
            state.templates.map { if (it.id == existing.id) template else it }
        } else {
            state.templates + template
        }
        return state.copy(templates = templates)
    }

    fun freshId(name: String): String {
        val slug = name.lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff]+"), "-")
            .trim('-')
            .ifBlank { "tpl" }
        return "$slug-${System.currentTimeMillis().toString(36).takeLast(6)}"
    }
}
