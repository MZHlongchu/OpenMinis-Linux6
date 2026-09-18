package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WritePathGuardTest {

    @Test
    fun parseSplitsAndNormalizes() {
        val paths = WritePathGuard.parse("/var/minis/workspace/a/, /tmp/out; /var/minis/workspace/a")
        assertEquals(listOf("/var/minis/workspace/a", "/tmp/out"), paths)
    }

    @Test
    fun denyOutsidePrefix() {
        val prev = WritePathGuard.swap(listOf("/var/minis/workspace/proj"))
        try {
            assertNull(WritePathGuard.denyReason("/var/minis/workspace/proj/src/Main.kt"))
            val denied = WritePathGuard.denyReason("/var/minis/workspace/other/x")
            assertTrue(denied!!.contains("write_paths"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }

    @Test
    fun unrestrictedWhenEmpty() {
        val prev = WritePathGuard.swap(emptyList())
        try {
            assertNull(WritePathGuard.denyReason("/etc/passwd"))
        } finally {
            WritePathGuard.restore(prev)
        }
    }
}
