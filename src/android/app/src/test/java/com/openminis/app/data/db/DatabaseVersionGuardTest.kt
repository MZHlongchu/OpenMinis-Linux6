package com.openminis.app.data.db

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-downgrade-compat] Keeps the guard's version constant honest.
 *
 * [DatabaseVersionGuard.CODE_DB_VERSION] duplicates the `version` in
 * [AppDatabase]'s `@Database` annotation because that annotation has BINARY
 * retention — it is genuinely not readable via reflection at runtime (checked,
 * rather than assumed: `javap -v androidx/room/Database.class` shows
 * `AnnotationRetention.BINARY`). Two copies of one number is exactly what
 * drifts on the next schema bump, and drift is silent in both directions:
 *
 *  - constant too LOW → the guard fires on every launch of a healthy install
 *    and users get a "your data is from a newer version" screen forever;
 *  - constant too HIGH → the guard never fires and a real downgrade goes back
 *    to crashing at first database access.
 *
 * So this compares against the committed schema JSON instead, which Room
 * regenerates from the annotation on every build. That makes it a check on the
 * real source of truth AND a check that the schema export is still enabled —
 * if someone turns `exportSchema` off, the file stops updating and this test
 * fails rather than the CI migration check silently going stale.
 */
class DatabaseVersionGuardTest {

    private val schemaDir = File("schemas/com.openminis.app.data.db.AppDatabase")

    private fun exportedVersions(): List<Int> =
        (schemaDir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()

    @Test
    fun `schema json is exported`() {
        assertTrue(
            "No exported Room schema found at ${schemaDir.absolutePath}. " +
                "exportSchema must stay true and room.schemaLocation must stay configured, " +
                "or MigrationTestHelper has nothing to replay.",
            exportedVersions().isNotEmpty(),
        )
    }

    @Test
    fun `guard constant matches the latest exported schema version`() {
        val latest = exportedVersions().lastOrNull()
            ?: return  // covered by the test above; nothing to compare against
        assertEquals(
            "DatabaseVersionGuard.CODE_DB_VERSION must be bumped together with " +
                "@Database(version=...) — see the schema-change checklist in CLAUDE.md",
            latest,
            DatabaseVersionGuard.CODE_DB_VERSION,
        )
    }

    @Test
    fun `guard constant matches AppDatabase annotation`() {
        val src = File("src/main/java/com/openminis/app/data/db/AppDatabase.kt")
        require(src.isFile) { "missing ${src.absolutePath}" }
        val text = src.readText()
        val match = Regex(
            """@Database\s*\([^)]*version\s*=\s*(\d+)""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(text) ?: error("could not parse @Database version in ${src.absolutePath}")
        assertEquals(
            "DatabaseVersionGuard.CODE_DB_VERSION must equal @Database(version=...)",
            match.groupValues[1].toInt(),
            DatabaseVersionGuard.CODE_DB_VERSION,
        )
    }

    /** The exported schema must actually contain the four attribution columns. */
    @Test
    fun `exported schema has the attribution columns`() {
        val latest = exportedVersions().lastOrNull() ?: return
        val json = JSONObject(File(schemaDir, "$latest.json").readText())
        val entities = json.getJSONObject("database").getJSONArray("entities")
        var messages: JSONObject? = null
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            if (e.getString("tableName") == "messages") messages = e
        }
        requireNotNull(messages) { "messages table missing from exported schema" }

        val fields = messages.getJSONArray("fields")
        val byName = buildMap {
            for (i in 0 until fields.length()) {
                val f = fields.getJSONObject(i)
                put(f.getString("columnName"), f)
            }
        }
        for (col in listOf("model_id", "model_display_name", "provider_type", "provider_instance_id")) {
            val f = byName[col] ?: error("$col missing from exported messages schema")
            // Nullability is load-bearing: NULL is how the Usage page tells a
            // pre-migration row apart from a measured one, and it is what lets
            // an older build INSERT without knowing these columns.
            assertTrue("$col must be nullable", !f.getBoolean("notNull"))
        }
    }

    /**
     * [T-b11-no-downgrade-migrations] No downgrade migration may be declared or
     * registered again.
     *
     * Room resolves a downgrade with the table compiled into the *running*
     * (older) build, so a `Migration(N+1, N)` sitting in this source tree can
     * never execute: [DatabaseVersionGuard] sends every onDisk >
     * CODE_DB_VERSION to the guidance screen, and the older build that would
     * have needed it shipped before it existed. A whole chain of these
     * accumulated (12→11 … 22→21) and the last one — 22→21 — dropped
     * `message_versions`, i.e. it was a silent data-loss trap one guard-bypass
     * away. This is a source check rather than a runtime one because the defect
     * is the mere presence of the declaration: it is misleading even when it is
     * unreachable.
     *
     * Every migration this file may contain is an UPGRADE (a < b). The doc
     * comments that explain the rule are allowed to name no migration
     * identifiers, which is why this test scans only for the identifier shape
     * `MIGRATION_<a>_<b>`.
     */
    @Test
    fun `app database declares and registers only upgrade migrations`() {
        val src = File("src/main/java/com/openminis/app/data/db/AppDatabase.kt")
        require(src.isFile) { "missing ${src.absolutePath}" }
        val identifiers = Regex("""MIGRATION_(\d+)_(\d+)""")
            .findAll(src.readText())
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            .toList()
        assertTrue("expected the upgrade chain to be non-empty", identifiers.isNotEmpty())
        val downgrades = identifiers.filter { (from, to) -> from > to }
        assertTrue(
            "AppDatabase must not declare or register downgrade migrations " +
                "(found $downgrades): they can never run — see [T-b11-no-downgrade-migrations]",
            downgrades.isEmpty(),
        )
    }
}
