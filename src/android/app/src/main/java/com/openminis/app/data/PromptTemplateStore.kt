package com.openminis.app.data

import android.content.Context
import com.openminis.app.agent.PromptSafetyFilter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-session system-prompt templates. SharedPreferences JSON, no Room bump.
 * Hot-swap: [buildSystemPrompt] re-reads on the next model request.
 */
object PromptTemplateStore {
    private const val PREFS = "minis_prompt_templates"
    private const val KEY = "state"

    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile private var cached: PromptTemplateState = PromptTemplateState()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun prime(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cached = PromptTemplateCodec.parse(prefs?.getString(KEY, "") ?: "")
        _revision.value++
    }

    fun state(): PromptTemplateState = cached

    fun templates(): List<PromptTemplate> = cached.templates

    fun templateForSession(sessionId: String): PromptTemplate? =
        PromptTemplateCodec.templateForSession(cached, sessionId)

    fun renderForSession(sessionId: String): String? {
        val tpl = templateForSession(sessionId) ?: return null
        val body = PromptSafetyFilter.scrub(tpl.text)
        if (body.isBlank()) return null
        return "Session prompt template (${tpl.name}):\n$body"
    }

    sealed class SaveResult {
        data class Ok(val template: PromptTemplate) : SaveResult()
        data class Error(val code: String) : SaveResult()
    }

    fun save(name: String, text: String, order: Int = 1, id: String? = null): SaveResult {
        val trimmedName = name.trim()
        val trimmedText = text.trim()
        if (trimmedName.isBlank() || trimmedText.isBlank()) return SaveResult.Error("empty")
        if (trimmedText.length > PromptTemplateCodec.MAX_TEXT) return SaveResult.Error("too_long")
        if (PromptSafetyFilter.containsUnsafe(trimmedText)) return SaveResult.Error("unsafe")
        cached = PromptTemplateCodec.upsert(cached, trimmedName, trimmedText, order, id)
        persist()
        val saved = cached.templates.lastOrNull { it.name == trimmedName && it.text == trimmedText }
            ?: cached.templates.find { it.id == id }
            ?: return SaveResult.Error("empty")
        return SaveResult.Ok(saved)
    }

    fun delete(id: String) {
        cached = PromptTemplateCodec.delete(cached, id)
        persist()
    }

    fun applyToSession(sessionId: String, templateId: String) {
        if (sessionId.isBlank()) return
        cached = PromptTemplateCodec.applyToSession(cached, sessionId, templateId)
        persist()
    }

    fun setDefault(templateId: String) {
        cached = PromptTemplateCodec.setDefault(cached, templateId)
        persist()
    }

    internal fun replaceForTests(state: PromptTemplateState) {
        cached = state
        _revision.value++
    }

    private fun persist() {
        prefs?.edit()?.putString(KEY, PromptTemplateCodec.serialize(cached))?.apply()
        _revision.value++
    }
}
