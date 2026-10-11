package com.openminis.app.ui.chat

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [T-b10-table-actions-per-table] "Copy Table" / "Copy Table Image" are
 * registered per TABLE, not per message.
 *
 * A reply can render several tables, and they compose / dispose independently
 * (one LazyColumn row per markdown fragment). The messageId-only registry this
 * replaces failed twice, both silently:
 *
 *  - the toolbar resolved the table that registered LAST, so with two tables in
 *    one reply "Copy Table" copied the wrong table's markdown / bitmap;
 *  - a table leaving composition deleted its sibling's registration too (same
 *    key), so the buttons vanished from a table that was still on screen.
 *
 * Cells are [MdText] shards inside the fragment, so their [TextShardId] carries
 * the allocator's "#<subIndex>" suffix — the resolution has to see through it.
 */
class TableActionsRegistryTest {

    private fun key(messageId: String, shardId: String, headers: String) =
        "$messageId/$shardId/tbl:$headers"

    private fun actions(tag: String, cells: Set<String> = emptySet()) =
        SelectionController.TableActions(
            copyTableMarkdown = { tag },
            copyTableImage = { tag },
            cellTexts = cells,
        )

    private fun controllerWithSelection(vararg shards: TextShardId): SelectionController =
        SelectionController().apply {
            selection.value = TextSelection(
                TextPosition(shards.first(), 0),
                TextPosition(shards.last(), 2),
            )
        }

    @Test
    fun `selection inside a cell resolves to that cell's table`() {
        val c = SelectionController()
        val first = actions("first")
        val second = actions("second")
        c.rememberTableActions("m1", key("m1", "mdblock:m1:0", "Col A|Col B"), first)
        c.rememberTableActions("m1", key("m1", "mdblock:m1:1", "Col C|Col D"), second)

        val cell = TextShardId("m1", "mdblock:m1:0#4")
        c.selection.value = TextSelection(TextPosition(cell, 0), TextPosition(cell, 3))
        assertSame("must not fall back to the last-registered table", first, c.selectionTableActions())
    }

    @Test
    fun `selection inside the second table resolves to the second table`() {
        val c = SelectionController()
        val first = actions("first")
        val second = actions("second")
        c.rememberTableActions("m1", key("m1", "mdblock:m1:0", "Col A|Col B"), first)
        c.rememberTableActions("m1", key("m1", "mdblock:m1:1", "Col C|Col D"), second)

        val cell = TextShardId("m1", "mdblock:m1:1#9")
        c.selection.value = TextSelection(TextPosition(cell, 1), TextPosition(cell, 5))
        assertSame(second, c.selectionTableActions())
    }

    @Test
    fun `disposing one table keeps its sibling registered`() {
        val c = SelectionController()
        val first = actions("first")
        val second = actions("second")
        val firstKey = key("m1", "mdblock:m1:0", "Col A|Col B")
        c.rememberTableActions("m1", firstKey, first)
        c.rememberTableActions("m1", key("m1", "mdblock:m1:1", "Col C|Col D"), second)

        // The first table scrolls out of view / re-parses away.
        c.forgetTableActions("m1", firstKey)

        val stillOnScreen = TextShardId("m1", "mdblock:m1:1#3")
        c.selection.value = TextSelection(TextPosition(stillOnScreen, 0), TextPosition(stillOnScreen, 2))
        assertSame("sibling must survive the other table's dispose", second, c.selectionTableActions())

        val gone = TextShardId("m1", "mdblock:m1:0#1")
        c.selection.value = TextSelection(TextPosition(gone, 0), TextPosition(gone, 2))
        assertNull(c.selectionTableActions())
    }

    @Test
    fun `selection in the fragment shard itself resolves without a sub index`() {
        val c = SelectionController()
        val table = actions("only")
        c.rememberTableActions("m1", key("m1", "mdblock:m1:0", "Col A"), table)

        val shard = TextShardId("m1", "mdblock:m1:0")
        c.selection.value = TextSelection(TextPosition(shard, 0), TextPosition(shard, 2))
        assertSame(table, c.selectionTableActions())
    }

    @Test
    fun `re-registering the same table replaces its actions`() {
        val c = SelectionController()
        val stale = actions("stale")
        val fresh = actions("fresh")
        val tableKey = key("m1", "mdblock:m1:0", "Col A")
        c.rememberTableActions("m1", tableKey, stale)
        // Streaming re-parse: same identity, new closures over the new block.raw.
        c.rememberTableActions("m1", tableKey, fresh)

        val cell = TextShardId("m1", "mdblock:m1:0#2")
        c.selection.value = TextSelection(TextPosition(cell, 0), TextPosition(cell, 2))
        assertSame(fresh, c.selectionTableActions())
    }

    @Test
    fun `selection in prose of the same message resolves to no table`() {
        val c = SelectionController()
        c.rememberTableActions("m1", key("m1", "mdblock:m1:0", "Col A"), actions("table"))

        val prose = TextShardId("m1", "mdblock:m1:2#0")
        c.selection.value = TextSelection(TextPosition(prose, 0), TextPosition(prose, 4))
        assertNull(c.selectionTableActions())
    }

    @Test
    fun `cross message selection resolves to no table`() {
        val c = SelectionController()
        c.rememberTableActions("m1", key("m1", "mdblock:m1:0", "Col A"), actions("table"))

        c.selection.value = TextSelection(
            TextPosition(TextShardId("m1", "mdblock:m1:0#1"), 0),
            TextPosition(TextShardId("m2", "mdblock:m2:0#1"), 1),
        )
        assertNull(c.selectionTableActions())
    }

    @Test
    fun `no table composed resolves to null`() {
        val c = controllerWithSelection(TextShardId("m1", "mdblock:m1:0#1"))
        assertNull(c.selectionTableActions())

        c.selection.value = null
        assertNull(c.selectionTableActions())
    }

    // ── Two tables inside ONE markdown fragment (coalesced frozen message) ──
    //
    // A table is not a fragment split boundary, and a frozen reply coalesces
    // its paragraphs, so both tables legitimately register under the same
    // shardId. The selected cell's rendered text then has to decide; when it
    // cannot, the honest answer is "no button" — never a silent wrong copy.

    @Test
    fun `same fragment tables are told apart by the selected cell text`() {
        val c = SelectionController()
        val wide = actions("wide", cells = setOf("Col A", "Col B", "1", "2"))
        val tall = actions("tall", cells = setOf("Col C", "Col D", "3", "4"))
        c.rememberTableActions("m1", key("m1", "mdblock:m1:0", "Col A|Col B"), wide)
        c.rememberTableActions("m1", key("m1", "mdblock:m1:0", "Col C|Col D"), tall)

        val cell = TextShardId("m1", "mdblock:m1:0#5")
        c.selection.value = TextSelection(TextPosition(cell, 0), TextPosition(cell, 2))
        assertSame(tall, c.resolveTableActions { "Col C" })
        assertSame(wide, c.resolveTableActions { "2" })
    }

    @Test
    fun `same fragment table that nobody claims resolves to null`() {
        val c = SelectionController()
        c.rememberTableActions(
            "m1", key("m1", "mdblock:m1:0", "Col A"), actions("a", cells = setOf("Col A")),
        )
        c.rememberTableActions(
            "m1", key("m1", "mdblock:m1:0", "Col C"), actions("c", cells = setOf("Col C")),
        )

        val cell = TextShardId("m1", "mdblock:m1:0#5")
        c.selection.value = TextSelection(TextPosition(cell, 0), TextPosition(cell, 2))
        assertNull(c.resolveTableActions { "not a cell of either" })
        assertNull("no composed shard to read the text from", c.resolveTableActions { null })
    }

    @Test
    fun `a cell text claimed by both tables resolves to null`() {
        val c = SelectionController()
        c.rememberTableActions(
            "m1", key("m1", "mdblock:m1:0", "Name|A"), actions("a", cells = setOf("Name", "x")),
        )
        c.rememberTableActions(
            "m1", key("m1", "mdblock:m1:0", "Name|B"), actions("b", cells = setOf("Name", "y")),
        )

        val cell = TextShardId("m1", "mdblock:m1:0#5")
        c.selection.value = TextSelection(TextPosition(cell, 0), TextPosition(cell, 2))
        assertNull(c.resolveTableActions { "Name" })
    }
}
