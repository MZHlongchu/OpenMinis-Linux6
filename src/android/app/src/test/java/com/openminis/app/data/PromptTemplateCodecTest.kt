package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplateCodecTest {

    @Test
    fun roundTripPreservesTemplatesAndSessionPick() {
        val original = PromptTemplateState(
            templates = listOf(PromptTemplate("a", "Alpha", "Be concise", 1)),
            sessions = mapOf("s1" to "a"),
            defaultTemplate = "a",
        )
        val parsed = PromptTemplateCodec.parse(PromptTemplateCodec.serialize(original))
        assertEquals("a", parsed.templates.single().id)
        assertEquals("Be concise", parsed.templates.single().text)
        assertEquals("a", PromptTemplateCodec.templateForSession(parsed, "s1")?.id)
    }

    @Test
    fun sessionNoneBeatsDefault() {
        val state = PromptTemplateState(
            templates = listOf(PromptTemplate("a", "Alpha", "text", 1)),
            sessions = mapOf("s1" to PromptTemplateCodec.NONE),
            defaultTemplate = "a",
        )
        assertNull(PromptTemplateCodec.templateForSession(state, "s1"))
        assertEquals("a", PromptTemplateCodec.templateForSession(state, "s2")?.id)
    }

    @Test
    fun deleteClearsDefaultAndSessionBindings() {
        val state = PromptTemplateCodec.delete(
            PromptTemplateState(
                templates = listOf(PromptTemplate("a", "Alpha", "text", 1)),
                sessions = mapOf("s1" to "a"),
                defaultTemplate = "a",
            ),
            "a",
        )
        assertTrue(state.templates.isEmpty())
        assertEquals("", state.defaultTemplate)
        assertEquals(PromptTemplateCodec.NONE, state.sessions["s1"])
    }

    @Test
    fun corruptJsonFallsBackEmpty() {
        val parsed = PromptTemplateCodec.parse("{not json")
        assertTrue(parsed.templates.isEmpty())
    }
}
