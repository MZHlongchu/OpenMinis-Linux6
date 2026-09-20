package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileReadToolCapsTest {

    @Test
    fun userCapWinsOverRequestedChars() {
        assertEquals(4_000, FileReadTool.resolveMaxLength(15_000, 4_000))
        assertEquals(15_000, FileReadTool.resolveMaxLength(15_000, 80_000))
        assertEquals(80_000, FileReadTool.resolveMaxLength(200_000, 80_000))
    }

    @Test
    fun zeroLineCapMeansUnlimited() {
        assertNull(FileReadTool.resolveLineLimit(null, 0))
        assertEquals(12, FileReadTool.resolveLineLimit(12, 0))
        assertEquals(50, FileReadTool.resolveLineLimit(null, 50))
        assertEquals(20, FileReadTool.resolveLineLimit(80, 20))
    }
}
