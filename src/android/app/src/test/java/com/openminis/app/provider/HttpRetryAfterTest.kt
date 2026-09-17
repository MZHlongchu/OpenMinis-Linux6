package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HttpRetryAfterTest {

    @Test
    fun `parses integer Retry-After header`() {
        assertEquals(12, HttpRetryAfter.parseSeconds("12"))
        assertEquals(120, HttpRetryAfter.parseSeconds("999"))
        assertNull(HttpRetryAfter.parseSeconds("Wed, 21 Oct 2015 07:28:00 GMT"))
    }

    @Test
    fun `parses retry-after from JSON body`() {
        assertEquals(
            8,
            HttpRetryAfter.parseSeconds(null, """{"error":{"message":"retry_after=8"}}"""),
        )
        assertEquals(
            5,
            HttpRetryAfter.parseSeconds(null, """{"retryAfter": 5}"""),
        )
    }

    @Test
    fun `header wins over body`() {
        assertEquals(3, HttpRetryAfter.parseSeconds("3", """{"retry_after": 40}"""))
    }

    @Test
    fun `backoff follows schedule then honours Retry-After`() {
        assertEquals(1, HttpRetryAfter.delaySeconds(0, null))
        assertEquals(2, HttpRetryAfter.delaySeconds(1, null))
        assertEquals(16, HttpRetryAfter.delaySeconds(4, null))
        assertEquals(20, HttpRetryAfter.delaySeconds(0, 20))
        assertEquals(120, HttpRetryAfter.delaySeconds(0, 500))
    }
}
