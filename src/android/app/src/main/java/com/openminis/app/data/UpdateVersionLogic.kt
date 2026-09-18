package com.openminis.app.data

/**
 * Pure version / rolling-channel rules for [UpdateChecker].
 *
 * Semver tags (`1.17-linux`) compare as today. The fork's rolling pre-release
 * is always tagged `android-latest`; after [normalizeTag] that becomes
 * `"android"` and would lexicographically beat `"1.16"`. Rolling updates are
 * therefore decided by `versionCode` / `versionName` in the release body and,
 * as a last resort, the APK asset's `updated_at` vs the installed APK's
 * `lastUpdateTime`.
 */
object UpdateVersionLogic {

    const val ROLLING_TAG = "android-latest"
    const val ROLLING_SLOP_MS = 60_000L

    data class ReleaseCandidate(
        val tagName: String,
        val versionName: String,
        val releaseName: String,
        val changelog: String,
        val apkUrl: String?,
        val apkSize: Long,
        val apkUpdatedAtMs: Long,
        val bodyVersionCode: Int?,
        val bodyVersionName: String?,
    )

    fun isRollingTag(tag: String): Boolean =
        tag.equals(ROLLING_TAG, ignoreCase = true)

    fun normalizeTag(tag: String): String {
        val trimmed = tag.trim().removePrefix("v").removePrefix("V")
        val dashIdx = trimmed.indexOf('-')
        return if (dashIdx > 0) trimmed.substring(0, dashIdx) else trimmed
    }

    fun compareVersions(a: String, b: String): Int {
        val ap = a.split('.', '-')
        val bp = b.split('.', '-')
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val x = ap.getOrNull(i) ?: ""
            val y = bp.getOrNull(i) ?: ""
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    fun parseVersionCodeFromBody(body: String): Int? {
        return Regex("""(?im)(?:^|\b)versionCode\s*[:=]\s*(\d+)""")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    fun parseVersionNameFromBody(body: String): String? {
        return Regex("""(?im)(?:^|\b)versionName\s*[:=]\s*`?([0-9][0-9A-Za-z.+_-]*)`?""")
            .find(body)
            ?.groupValues
            ?.get(1)
    }

    fun parseGithubTime(iso: String): Long {
        if (iso.isBlank()) return 0L
        return try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    fun displayVersion(c: ReleaseCandidate): String {
        if (isRollingTag(c.tagName)) {
            val fromBody = c.bodyVersionName?.trim().orEmpty()
            if (fromBody.isNotEmpty()) return fromBody
            return c.tagName
        }
        return c.versionName
    }

    fun isNewerThanLocal(
        c: ReleaseCandidate,
        localVer: String,
        localCode: Int,
        localLastUpdateMs: Long,
    ): Boolean {
        if (c.apkUrl.isNullOrEmpty()) return false
        if (isRollingTag(c.tagName)) {
            val bodyCode = c.bodyVersionCode
            val bodyVer = c.bodyVersionName?.let { normalizeTag(it) }
            if (bodyCode != null && bodyCode > localCode) return true
            if (bodyVer != null && compareVersions(bodyVer, localVer) > 0) return true
            if (bodyCode == null) {
                val apkName = bodyVer ?: normalizeTag(c.versionName)
                if (c.apkUpdatedAtMs > 0L &&
                    localLastUpdateMs > 0L &&
                    c.apkUpdatedAtMs > localLastUpdateMs + ROLLING_SLOP_MS &&
                    compareVersions(apkName, localVer) > 0
                ) {
                    return true
                }
            }
            return false
        }
        return compareVersions(c.versionName, localVer) > 0
    }

    fun pickUpgrade(
        candidates: List<ReleaseCandidate>,
        localVer: String,
        localCode: Int,
        localLastUpdateMs: Long,
    ): ReleaseCandidate? {
        val newer = candidates.filter {
            isNewerThanLocal(it, localVer, localCode, localLastUpdateMs)
        }
        if (newer.isEmpty()) return null
        return newer.maxWithOrNull(
            compareBy<ReleaseCandidate> { compareVersions(displayVersion(it), "0") }
                .thenBy { it.bodyVersionCode ?: -1 }
                .thenBy { it.apkUpdatedAtMs },
        )
    }

    fun highestPublished(candidates: List<ReleaseCandidate>): ReleaseCandidate? {
        val semver = candidates.filter { !isRollingTag(it.tagName) }
        val pool = semver.ifEmpty { candidates }
        return pool.maxWithOrNull(compareBy { compareVersions(it.versionName, "0") })
    }
}
