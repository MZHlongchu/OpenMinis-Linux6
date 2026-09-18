package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionLogicTest {

    private fun rolling(
        code: Int? = 29,
        name: String? = "1.17-linux",
        updatedAt: Long = 2_000_000L,
        apk: String? = "https://example.com/app.apk",
    ) = UpdateVersionLogic.ReleaseCandidate(
        tagName = "android-latest",
        versionName = "android",
        releaseName = "Minis Ultra (Android)",
        changelog = "versionName: `$name`\nversionCode: $code",
        apkUrl = apk,
        apkSize = 1L,
        apkUpdatedAtMs = updatedAt,
        bodyVersionCode = code,
        bodyVersionName = name,
    )

    private fun tagged(
        tag: String,
        apk: String? = "https://example.com/app.apk",
    ) = UpdateVersionLogic.ReleaseCandidate(
        tagName = tag,
        versionName = UpdateVersionLogic.normalizeTag(tag),
        releaseName = tag,
        changelog = "",
        apkUrl = apk,
        apkSize = 1L,
        apkUpdatedAtMs = 0L,
        bodyVersionCode = null,
        bodyVersionName = null,
    )

    @Test
    fun `normalizeTag strips linux suffix`() {
        assertEquals("1.16", UpdateVersionLogic.normalizeTag("1.16-linux"))
        assertEquals("1.16", UpdateVersionLogic.normalizeTag("v1.16-linux"))
    }

    @Test
    fun `android-latest is not a semver upgrade by tag alone`() {
        val c = rolling(code = 28, name = "1.16-linux", updatedAt = 1000L)
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(
                c,
                localVer = "1.16",
                localCode = 28,
                localLastUpdateMs = 5000L,
            ),
        )
    }

    @Test
    fun `rolling versionCode bump is an upgrade`() {
        val c = rolling(code = 29, name = "1.17-linux")
        assertTrue(
            UpdateVersionLogic.isNewerThanLocal(
                c, "1.16", 28, localLastUpdateMs = 9_000_000L,
            ),
        )
        val picked = UpdateVersionLogic.pickUpgrade(listOf(c), "1.16", 28, 9_000_000L)
        assertNotNull(picked)
        assertEquals("1.17-linux", UpdateVersionLogic.displayVersion(picked!!))
    }

    @Test
    fun `rolling rebuilt APK with same versionCode is an upgrade by updated_at`() {
        val c = rolling(code = 28, name = "1.16-linux", updatedAt = 10_000_000L)
        assertTrue(
            UpdateVersionLogic.isNewerThanLocal(
                c, "1.16", 28, localLastUpdateMs = 1_000_000L,
            ),
        )
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(
                c, "1.16", 28, localLastUpdateMs = 10_000_000L,
            ),
        )
    }

    @Test
    fun `semver tag still wins over local`() {
        val c = tagged("1.17-linux")
        assertTrue(UpdateVersionLogic.isNewerThanLocal(c, "1.16", 28, 0L))
        assertFalse(UpdateVersionLogic.isNewerThanLocal(tagged("1.16-linux"), "1.16", 28, 0L))
    }

    @Test
    fun `rolling tag is excluded from highest semver`() {
        val highest = UpdateVersionLogic.highestPublished(
            listOf(rolling(), tagged("1.16-linux")),
        )
        assertEquals("1.16-linux", highest?.tagName)
    }

    @Test
    fun `body parsers`() {
        val body = """
            Rolling Android build.

            - versionName: `1.17-linux`
            - versionCode: 29
        """.trimIndent()
        assertEquals(29, UpdateVersionLogic.parseVersionCodeFromBody(body))
        assertEquals("1.17-linux", UpdateVersionLogic.parseVersionNameFromBody(body))
    }

    @Test
    fun `no apk means not newer`() {
        assertFalse(
            UpdateVersionLogic.isNewerThanLocal(
                rolling(apk = null), "1.16", 28, 0L,
            ),
        )
        assertNull(
            UpdateVersionLogic.pickUpgrade(listOf(rolling(apk = null)), "1.16", 28, 0L),
        )
    }
}
