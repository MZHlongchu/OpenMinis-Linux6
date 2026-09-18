package com.openminis.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayComposeBusTest {
    @Test
    fun stickyConsumesOnce() {
        OverlayComposeBus.submit("sid-1", "  hello  ")
        assertEquals("sid-1", OverlayComposeBus.peekStickySessionId())
        assertEquals("hello", OverlayComposeBus.consumeSticky("sid-1"))
        assertNull(OverlayComposeBus.consumeSticky("sid-1"))
    }
}
