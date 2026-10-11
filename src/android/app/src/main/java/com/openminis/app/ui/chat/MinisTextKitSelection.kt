package com.openminis.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.TextLayoutResult

/**
 * MinisTextKit — a self-contained text selection layer that survives
 * LazyColumn item recycling.
 *
 * Compose's built-in [androidx.compose.foundation.text.selection.SelectionContainer]
 * pairs each text node with a [androidx.compose.foundation.text.selection.Selectable]
 * registered on a per-SelectionContainer registrar. When a LazyColumn item
 * scrolls out of the viewport, its Selectable disposes → the registrar drops
 * the anchor → the active selection collapses.
 *
 * MinisTextKit avoids that by keeping the *authoritative selection state*
 * outside the LazyColumn entirely. Each visible text fragment registers a
 * [TextShard] with [SelectionController] while it's composed; when it
 * scrolls off-screen the registration drops but the [selection] state, keyed
 * by stable (messageId, shardId, charOffset), persists. When the shard
 * scrolls back into view it re-registers and the selection highlight is
 * redrawn automatically.
 *
 * This file defines only the state holder and the registration ABI.
 * Hit-test, drag gestures, and highlight drawing are implemented in
 * [MinisMarkdownView] and consumed by [StreamingMarkdownText] via
 * [LocalMinisSelectionController].
 */

/**
 * A handle, stable across recompositions, that identifies a single text-bearing
 * node inside a chat message. The combination (messageId, shardId) is unique
 * within the chat list — messageId scopes to a single ChatMessage, shardId
 * scopes to a fragment within that message (one paragraph / code block /
 * heading, matching [splitMarkdownIntoBlockTexts]).
 */
data class TextShardId(
    val messageId: String,
    val shardId: String,
)

/**
 * Per-shard payload registered with [SelectionController]. The controller
 * uses [textLayoutResult] for hit-testing screen points → character offsets
 * and the inverse (offset → bounding box) for highlight rendering. The
 * controller does NOT capture the LayoutCoordinates reference; it captures
 * the *position-in-window* recomputed every layout pass via [positionInWindow].
 */
class TextShard(
    val id: TextShardId,
    /** Plain text actually shown to the user — used for copy fallback. */
    val plainText: String,
    /** The TextLayoutResult of the rendered Text composable. */
    val textLayoutResult: TextLayoutResult,
    /** Top-left in window coordinates, refreshed by the shard's owner. */
    val positionInWindow: () -> Offset,
    /** Size of the laid-out text in pixels. */
    val sizePx: () -> androidx.compose.ui.unit.IntSize,
    /**
     * Optional mapping from a rendered AnnotatedString character offset back
     * to a raw markdown source offset. When null, copy falls back to
     * [plainText.substring]. When provided, copy can return raw markdown
     * (preserving `**bold**`, links, etc.).
     */
    val renderedToRawOffset: ((Int) -> Int)? = null,
    /** The raw markdown source for this shard. Used together with [renderedToRawOffset] for "copy markdown" paths. */
    val rawMarkdown: String? = null,
    /**
     * True when this shard is a self-contained unit that a long-press should
     * select in full — currently, a table cell.
     *
     * Prose wants the sentence-level expansion [SelectionController] normally
     * applies; a table cell does not. A cell's text is usually punctuation-free
     * ("Alice Smith", "张三 项目经理"), so that scan runs to both ends and picks
     * up the whole cell anyway — but a cell that DOES contain punctuation
     * ("1,200", "v1.2, beta") would select only a fragment, which is never what
     * someone long-pressing a table wants. Marking the cell atomic makes the
     * cell boundary the rule instead of an accident of its content.
     */
    val isAtomicUnit: Boolean = false,
)

/**
 * Identifies one endpoint of a selection. Stable across LazyColumn recycle:
 * even if the owning shard is currently not composed, the controller can
 * still emit the persisted selection back to the user as soon as the shard
 * re-registers.
 */
data class TextPosition(
    val shard: TextShardId,
    /** Character offset within the shard's [TextShard.plainText]. */
    val charOffset: Int,
)

/** A live text selection, possibly spanning multiple shards / messages. */
data class TextSelection(
    val start: TextPosition,
    val end: TextPosition,
)

/**
 * Owns the active selection plus the registry of currently-composed shards.
 * Hoist one of these per logical "text surface" — for ChatScreen that means
 * one controller for the entire LazyColumn, owned above the LazyColumn so
 * its lifetime exceeds any individual item's lifetime.
 */
class SelectionController {
    /** Which of the two selection endpoints a drag is adjusting. */
    enum class Handle { Start, End }

    /** Active selection or null when nothing is selected. */
    val selection = mutableStateOf<TextSelection?>(null)

    /**
     * [T-android-mouse-text-selection] True when the active selection was made
     * with a mouse, which suppresses the draggable handle dots.
     *
     * The dots exist to solve a touch-only problem: a fingertip cannot re-grab
     * a selection edge precisely, so the edges are given large hit targets that
     * hang below the text. A mouse re-adjusts by shift-clicking or simply
     * dragging again, so the dots would be decoration that covers the very
     * text the user is reading — and, worse, sit under the cursor as
     * click-targets that steal presses meant for the text.
     *
     * Set per selection rather than globally, so a 2-in-1 that switches from
     * trackpad to finger gets the handles back on the next touch selection.
     */
    val selectionFromMouse = mutableStateOf(false)

    /**
     * Message-level markdown snapshots captured at selection time so the
     * "Copy Markdown / Copy Rich Text" actions stay available even after
     * the originating shard scrolls out of viewport (the
     * MessageBoundsRegistry would have removed it by then). Keyed by
     * messageId; populated by [rememberMessageMarkdown] (called when a
     * shard registers), kept across the selection's lifetime, cleared on
     * [clearSelection].
     */
    // Snapshot-state map so writes from a SideEffect during one frame
    // invalidate any downstream reader (e.g. the toolbar's `md =
    // resolveSelectionMarkdown()` call) on the next frame, surfacing the
    // markdown buttons the moment a shard has published its source.
    private val messageMarkdownCache = androidx.compose.runtime.mutableStateMapOf<String, String>()

    /** Called by shard owners to publish the parent message's joined markdown. */
    fun rememberMessageMarkdown(messageId: String, markdown: String) {
        if (markdown.isEmpty()) return
        // Skip if already correct — avoid re-emitting on every recomposition.
        if (messageMarkdownCache[messageId] == markdown) return
        messageMarkdownCache[messageId] = markdown
    }

    /** Resolve the active selection's parent message markdown. Null when
     *  the selection spans multiple messages OR the cache never saw it. */
    fun selectionMessageMarkdown(): String? {
        val msgId = singleMessageId() ?: return null
        return messageMarkdownCache[msgId]
    }

    /**
     * [T-android-markdown-table-copy-actions] Table actions for the table the
     * selection is inside, so the SINGLE selection toolbar can append
     * "Copy Table" / "Copy Table Image" alongside Copy / Copy Markdown / etc.
     * (instead of the table rendering a second, separate popup). A
     * [RenderTable] publishes its [TableActions] while it's composed; the
     * toolbar reads them for the selected table.
     *
     * [copyTableMarkdown] returns the table's exact markdown source.
     * [copyTableImage] captures + clipboards the rendered table bitmap.
     */
    class TableActions(
        val copyTableMarkdown: () -> Unit,
        val copyTableImage: () -> Unit,
        /**
         * Rendered text of every cell of THIS table, used to tell two tables
         * apart when they share one shard. A frozen message coalesces its
         * markdown fragments ([coalesceMarkdownFragments]), so two tables can
         * legitimately register under the SAME shardId — the cell the user
         * selected then identifies which of them owns the selection.
         * [T-b10-table-actions-per-table]
         */
        val cellTexts: Set<String> = emptySet(),
    )

    /**
     * Two-level registry: messageId → (table identity → actions). A single
     * reply can render several tables, each composing/disposing on its own (a
     * distinct LazyColumn row per markdown fragment), so the previous
     * messageId-only key made the last-composed table win and let the first
     * table's dispose delete the other's registration — "Copy Table" then
     * copied the wrong table, and a button could vanish without ever coming
     * back. [T-b10-table-actions-per-table]
     *
     * The inner key is the table's stable identity, the same
     * `"<messageId>/<shardId>/tbl:<headers>"` string [RenderTable] uses for its
     * horizontal-scroll state.
     */
    private val tableActionsByMessage =
        mutableStateMapOf<String, SnapshotStateMap<String, TableActions>>()

    /** Publish a table's actions for ([messageId], [tableId]) while it's composed. */
    fun rememberTableActions(messageId: String, tableId: String, actions: TableActions) {
        val tables = tableActionsByMessage[messageId]
            ?: mutableStateMapOf<String, TableActions>().also { tableActionsByMessage[messageId] = it }
        tables[tableId] = actions
    }

    /** Drop ONE table's actions when it leaves composition. */
    fun forgetTableActions(messageId: String, tableId: String) {
        val tables = tableActionsByMessage[messageId] ?: return
        tables.remove(tableId)
        // Don't leave an empty entry behind per message.
        if (tables.isEmpty()) tableActionsByMessage.remove(messageId)
    }

    /**
     * Table actions for the table under the active single-message selection,
     * or null when there's no selection, it spans messages, or no table can be
     * identified for the selected shard.
     */
    fun selectionTableActions(): TableActions? =
        resolveTableActions { shardId -> shards[shardId]?.plainText }

    /**
     * [T-b10-table-actions-per-table] Resolution rule, with the endpoint's
     * displayed text injected so it can be exercised without a composed shard.
     *
     * The selection's shard names the table in the usual case (one table per
     * markdown fragment), so the lookup goes shard-first — the old messageId
     * lookup returned whichever table registered last, silently copying the
     * wrong table. Two tables CAN share one fragment (a frozen message
     * coalesces its paragraphs, and a table is not a split boundary), and then
     * the shardId is identical for both: the cell text the user selected
     * decides, and only when exactly one table claims it. Anything else
     * returns null — no buttons is better than copying the wrong table.
     */
    internal fun resolveTableActions(cellTextOf: (TextShardId) -> String?): TableActions? {
        val sel = selection.value ?: return null
        val msgId = singleMessageId() ?: return null
        val tables = tableActionsByMessage[msgId] ?: return null
        if (tables.isEmpty()) return null
        // 表格单元格是 shard 内的 MdText，其 shardId 带 "#<子序号>" 后缀
        // (StreamingMarkdownMdText)，所以先按原样匹配、再按去掉后缀的根 shard
        // 匹配；跨 shard 选区只要有一端落在某张表里就用那张表。
        for (endpoint in listOf(sel.start.shard, sel.end.shard)) {
            tables.entries.firstOrNull { it.key.startsWith("$msgId/${endpoint.shardId}/tbl:") }
                ?.let { return it.value }
            val root = shardIdRoot(endpoint.shardId)
            if (root == endpoint.shardId) continue
            val candidates = tables.entries.filter { it.key.startsWith("$msgId/$root/tbl:") }
            if (candidates.isEmpty()) continue
            if (candidates.size == 1) return candidates.first().value
            // 同一 fragment 里有多张表：由该单元格的渲染文本认领，且必须唯一。
            val cellText = cellTextOf(endpoint) ?: return null
            val claimed = candidates.filter { cellText in it.value.cellTexts }
            return if (claimed.size == 1) claimed.first().value else null
        }
        return null
    }

    /**
     * Drag intent published by the gesture handler. While the user is
     * actively dragging (long-press phase 2 OR handle drag), `point` is the
     * latest finger position in window coords and `handle` identifies which
     * endpoint to update. When the finger lifts, set to null.
     *
     * A separate effect in ChatScreen watches this state together with the
     * LazyColumn's scroll position so the selection keeps tracking even
     * when the finger is stationary inside the edge auto-scroll zone (new
     * shards scroll into view and the selection extends to them).
     */
    val dragIntent = mutableStateOf<DragIntent?>(null)

    data class DragIntent(val point: Offset, val handle: Handle)

    /**
     * Currently-composed shards, keyed by their stable id. A shard registers
     * on enter-composition (DisposableEffect) and unregisters on dispose.
     */
    private val shards = mutableStateMapOf<TextShardId, TextShard>()

    /**
     * [T-cross-shard-copy-docmap-char-budget] 表内分片文本的字符总数，用于
     * [MAX_DOCUMENT_CHARS] 的淘汰记账（与 [MAX_DOCUMENT_SHARDS] 的条数上限并存）。
     */
    private var documentTextChars = 0

    /**
     * [T-cross-shard-copy-recycled] 文档级 plainText 表：**[register] 写入，
     * [unregister] 不删**。
     *
     * 跨分片复制（Copy / 加入输入框 / 朗读）恰恰发生在端点分片被 LazyColumn 回收
     * 之后。旧实现只认当前注册的分片：首分片一回收，`started` 恒为 false，中间分片
     * 被整段跳过而末分片仍被无条件追加 → 只复制出末分片的半截前缀；两端都回收时
     * 直接复制出空串（静默 no-op，比审计描述更糟）。
     *
     * accessOrder 的 LinkedHashMap 兼作 LRU：表的规模随「用户滚过的分片数」增长，
     * 超过 [MAX_DOCUMENT_SHARDS] 条**或** [MAX_DOCUMENT_CHARS] 字符时淘汰最久未读取
     * 的条目，避免长会话把已回收分片的文本一直攒在内存里。
     */
    private val documentTextMap = object : LinkedHashMap<TextShardId, String>(64, 0.75f, true) {
        /**
         * 条数上限与**字符预算**同时收口在这里，两条不变式：
         *  - `size > 1` 才淘汰：与 MarkdownParseCaches.Lru 同一条不变式 —— 单条超大
         *    分片（巨段落）必须留在表里，否则端点落在它里面时，跨分片复制在它被
         *    LazyColumn 回收后就再也取不到文本；
         *  - 淘汰的是最久未读的条目（accessOrder），刚注册 / 复制刚读过的分片总是
         *    最后被淘汰。
         */
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<TextShardId, String>,
        ): Boolean {
            if (size <= 1) return false
            val over = size > MAX_DOCUMENT_SHARDS || documentTextChars > MAX_DOCUMENT_CHARS
            if (over) documentTextChars -= eldest.value.length
            return over
        }
    }

    fun register(shard: TextShard) {
        shards[shard.id] = shard
        // [T-u6-sel-merge] Both registration side-effects, in this order: our
        // document table keeps the TEXT (it survives recycling even with no
        // selection active), upstream's ShardText seam keeps the text AND the
        // last known y for the ordering/copy logic the tests exercise.
        rememberShardText(shard.id, shard.plainText)
        registerText(
            ShardText(shard.id, shard.plainText, shard.rawMarkdown) {
                // A shard's coordinates can detach before its dispose runs.
                runCatching { shard.positionInWindow().y }.getOrNull()
            },
        )
    }

    /**
     * [T-android-selection-offscreen-order] (GH#296) Where each shard sits in
     * the document, whether or not it is composed. The chat supplies
     * [chatDocumentOrder]; null (every other surface) keeps the on-screen-y
     * ordering those surfaces always had. See SelectionDocumentOrder.kt.
     *
     * [T-u6-sel-merge] Kept alongside our document table rather than replacing
     * it: this is what orders shards ACROSS blocks and ACROSS messages (a row
     * index plus a sub-index), while [documentTextMap] is what still holds the
     * TEXT of a shard LazyColumn already recycled. Either alone leaves half of
     * GH#296 open — no order without the provider, no text without the table.
     */
    var documentOrder: ((TextShardId) -> DocOrder?)? = null

    /** Text + position of every registered shard ([ShardText] view of [shards]). */
    private val liveTexts = HashMap<TextShardId, ShardText>()

    /**
     * [T-android-selection-offscreen-order] (GH#296) Shards that unregistered
     * while a selection was active — scrolled out of the LazyColumn by the
     * handle's edge auto-scroll, typically the selection's own start. Their
     * text is frozen here so ordering and copy still see the whole selected
     * span; before, a copy walked registered shards only and returned just
     * the tail of the last one. Emptied when a selection begins or is
     * cleared, so it only ever holds what the current selection scrolled
     * past.
     */
    private val retired = HashMap<TextShardId, ShardText>()

    /** Test seam for the ordering / copy logic, which needs no TextLayoutResult. */
    internal fun registerText(text: ShardText) {
        liveTexts[text.id] = text
        retired.remove(text.id)
    }

    fun unregister(id: TextShardId) {
        // [T-cross-shard-copy-recycled] 只摘掉注册项；文本留在 documentTextMap 里
        // ——否则端点分片一被回收，跨分片复制就再也取不到它的文本。
        shards.remove(id)
        val live = liveTexts.remove(id) ?: return
        if (selection.value != null) {
            val lastY = live.y()
            retired[id] = ShardText(live.id, live.plainText, live.rawMarkdown) { lastY }
        }
    }

    /** Number of frozen off-screen shards (tests / diagnostics). */
    internal fun retiredCount(): Int = retired.size

    private fun yOf(id: TextShardId): Float? = liveTexts[id]?.y?.invoke() ?: retired[id]?.y?.invoke()

    /**
     * [T-android-selection-offscreen-order] Order of two shards: document
     * order when the surface provides it for both, else on-screen y (a
     * retired shard answers with the y it had when it left). Null when
     * neither is known, i.e. the order genuinely cannot be decided.
     */
    internal fun compareShards(a: TextShardId, b: TextShardId): Int? {
        if (a == b) return 0
        documentOrder?.let { order ->
            val oa = order(a)
            val ob = order(b)
            if (oa != null && ob != null) return oa.compareTo(ob)
        }
        // The id-embedded index that `shardOrderKey` used to provide, kept as
        // a fallback for surfaces without document order — now scoped to one
        // message AND one parent block (an index means nothing across
        // parents) and aware of the `#subIndex` suffix it choked on.
        sameBlockOrder(a, b)?.let { return it }
        val ya = yOf(a) ?: return null
        val yb = yOf(b) ?: return null
        return ya.compareTo(yb)
    }

    /**
     * [T-cross-shard-copy-recycled] [register] 的「文本半边」，单独开出来是因为
     * 「分片已被回收、只剩文本」正是跨分片复制要处理的状态（那时没有 TextShard /
     * TextLayoutResult 可注册）；JVM 单测也据此覆盖这条路径。
     */
    internal fun rememberShardText(id: TextShardId, text: String) {
        if (text.isEmpty()) return
        // [T-cross-shard-copy-docmap-char-budget] 覆盖写（分片重新挂载、文本变长）
        // 时先扣掉旧值，记账才与表内容一致。
        documentTextMap[id]?.let { documentTextChars -= it.length }
        documentTextChars += text.length
        documentTextMap[id] = text
    }

    /**
     * [T-cross-shard-copy-recycled] 分片文本：注册中的实例 → 未回收的 [ShardText]
     * → 回收后冻结的 [retired] → 文档级表 → 调用方注册表。
     *
     * [T-u6-sel-merge] 两侧各有一份同名实现（文本来源不同）。合成一条链：先问
     * 「当下真的挂着」的分片，再问上游冻结的 ShardText，最后才是我们那张按字节
     * 预算淘汰的文档级表——表可能因 LRU 淘汰而缺条目，而 retired 只在选区活动期间
     * 保留，两者互补，谁先谁后都能取到文本，取不到才返回 null（调用方按缺口计）。
     * 单一实现也意味着 [selectedPlainText] 的两条路径不会再各说各话。
     */
    private fun textOf(id: TextShardId, documentRegistry: Map<TextShardId, String> = emptyMap()): String? =
        shards[id]?.plainText
            ?: liveTexts[id]?.plainText
            ?: retired[id]?.plainText
            ?: documentTextMap[id]
            ?: documentRegistry[id]

    /**
     * [T-cross-shard-copy-recycled] 我们知道存在的分片：注册中的（[shards]）、文档级
     * 表里留有文本的（已回收）、以及 [ShardText] 那一侧登记过的（[liveTexts] /
     * [retired]）。
     *
     * [T-u6-sel-merge] 第三类必须算进来：合并后 [registerText] 是 [register] 的另一半
     * （上游的 ShardText 缝合口），而**只有 [register] 会写文档级表**——只认表的话，
     * 经 [ShardText] 登记的分片在键序跨度里完全不可见，跨度会被判成「枚举完整」却少
     * 一段：正是 [T-cross-shard-copy-span-blind] 要消灭的静默缺口，只是换了个入口。
     */
    private fun knownShardIds(): List<TextShardId> =
        LinkedHashSet<TextShardId>(shards.size + documentTextMap.size + liveTexts.size + retired.size).apply {
            addAll(shards.keys)
            addAll(documentTextMap.keys)
            addAll(liveTexts.keys)
            addAll(retired.keys)
        }.toList()

    /** Read-only snapshot of currently-composed shards. */
    internal fun currentShards(): Map<TextShardId, TextShard> = shards

    /**
     * Hit-test a window-space point against every currently composed shard.
     * Returns the [TextPosition] of the character nearest to that point, or
     * null if the point isn't over any registered shard.
     */
    fun hitTest(windowPoint: Offset): TextPosition? {
        val (shard, localPoint) = locateShard(windowPoint) ?: return null
        val offset = shard.textLayoutResult.getOffsetForPosition(localPoint)
            .coerceIn(0, shard.plainText.length)
        return TextPosition(shard.id, offset)
    }

    /**
     * Stricter hit-test that ONLY returns a position when the point falls
     * directly inside a registered shard's rect — no nearest-shard fallback.
     * Used by the long-press path so a press on a non-selectable region
     * (e.g. a user message bubble, which intentionally skips MinisTextKit
     * shard registration so it can show its own long-press menu) doesn't
     * snap to whichever assistant shard happens to be closest.
     */
    fun hitTestStrict(windowPoint: Offset): TextPosition? {
        for (shard in shards.values) {
            val origin = shard.positionInWindow()
            if (origin == Offset.Zero) continue
            val size = shard.sizePx()
            val rect = Rect(origin, Offset(size.width.toFloat(), size.height.toFloat()).let { d ->
                androidx.compose.ui.geometry.Size(d.x, d.y)
            })
            if (rect.contains(windowPoint)) {
                val local = windowPoint - origin
                val offset = shard.textLayoutResult.getOffsetForPosition(local)
                    .coerceIn(0, shard.plainText.length)
                return TextPosition(shard.id, offset)
            }
        }
        return null
    }

    /** Find a registered shard covering [windowPoint], plus the corresponding local-space point. */
    private fun locateShard(windowPoint: Offset): Pair<TextShard, Offset>? {
        var best: Pair<TextShard, Offset>? = null
        for (shard in shards.values) {
            val origin = shard.positionInWindow()
            val size = shard.sizePx()
            val rect = Rect(origin, Offset(size.width.toFloat(), size.height.toFloat()).let { d ->
                androidx.compose.ui.geometry.Size(d.x, d.y)
            })
            if (rect.contains(windowPoint)) {
                return shard to (windowPoint - origin)
            }
            // No direct hit; remember nearest-vertically shard as a fallback
            // so dragging out the side of a shard still extends sensibly.
            if (best == null) {
                val clampedX = windowPoint.x.coerceIn(rect.left, rect.right.coerceAtLeast(rect.left))
                val clampedY = windowPoint.y.coerceIn(rect.top, rect.bottom.coerceAtLeast(rect.top))
                best = shard to Offset(clampedX - origin.x, clampedY - origin.y)
            } else {
                val (curBest, _) = best!!
                val curOrigin = curBest.positionInWindow()
                val curSize = curBest.sizePx()
                val curCenterY = curOrigin.y + curSize.height / 2f
                val newCenterY = origin.y + size.height / 2f
                if (kotlin.math.abs(newCenterY - windowPoint.y) <
                    kotlin.math.abs(curCenterY - windowPoint.y)
                ) {
                    val clampedX = windowPoint.x.coerceIn(rect.left, rect.right.coerceAtLeast(rect.left))
                    val clampedY = windowPoint.y.coerceIn(rect.top, rect.bottom.coerceAtLeast(rect.top))
                    best = shard to Offset(clampedX - origin.x, clampedY - origin.y)
                }
            }
        }
        return best
    }

    /** Begin a fresh selection collapsed at [pos] (long-press start). */
    fun beginSelection(pos: TextPosition) {
        retired.clear()
        selection.value = TextSelection(start = pos, end = pos)
    }

    /**
     * Begin a selection that snaps to the WORD under [pos]. Mirrors iOS long-
     * press semantics: a one-finger long press should reveal a real, non-zero-
     * width selection so the user gets immediate visual feedback and can drag
     * handles outward to grow it. Falls back to a collapsed caret when the
     * shard isn't registered or the character isn't part of a word.
     */
    fun beginSelectionWord(pos: TextPosition) {
        retired.clear()
        val shard = currentShards()[pos.shard]
        if (shard == null) {
            beginSelection(pos)
            return
        }
        val text = shard.plainText
        if (text.isEmpty()) {
            beginSelection(pos)
            return
        }
        // A table cell selects as a whole cell — see TextShard.isAtomicUnit.
        if (shard.isAtomicUnit) {
            val start = text.indexOfFirst { !it.isWhitespace() }
            if (start < 0) {
                beginSelection(pos)
                return
            }
            val end = text.indexOfLast { !it.isWhitespace() } + 1
            selection.value = TextSelection(
                start = TextPosition(pos.shard, start),
                end = TextPosition(pos.shard, end),
            )
            return
        }
        val (lo, hi) = wordBoundsAt(text, pos.charOffset)
        if (hi <= lo) {
            beginSelection(pos)
            return
        }
        selection.value = TextSelection(
            start = TextPosition(pos.shard, lo),
            end = TextPosition(pos.shard, hi),
        )
    }

    /**
     * Compute the word range surrounding [offset] in [text]. Uses
     * [java.text.BreakIterator.getWordInstance] so the result honors locale
     * rules. When [offset] sits on whitespace / punctuation, scan outward
     * for the nearest word so a long-press near a paragraph end / between
     * two CJK glyphs still produces a non-collapsed selection. Falls back
     * to "select this single character" when no word can be found nearby,
     * which still gives the user a visible selection to drag.
     */
    private fun wordBoundsAt(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val len = text.length
        val clamped = offset.coerceIn(0, len)

        // Sentence-level selection: long-press should grab a meaningful
        // chunk of text — for Latin that's a word-ish run, for CJK we
        // expand to the next punctuation/whitespace boundary on either
        // side so the user gets a clause-sized selection (matching the
        // expectation set by other Chinese readers). System TextView's
        // word-iterator behavior on CJK is too aggressive (single-char
        // selection) and the resulting near-collapsed highlight isn't
        // discoverable. Define "sentence stops" as the union of common
        // Latin + CJK punctuation plus newline.
        val sentenceStops = setOf(
            '。', '？', '！', '；', '：', '，', '、',
            '.', '?', '!', ';', ':', ',',
            '\n', '\r',
        )
        // Scan backward to find the LO bound: the index just after the
        // nearest preceding sentence stop (or 0 if none).
        var lo = clamped.coerceAtMost(len - 1).coerceAtLeast(0)
        while (lo > 0 && text[lo - 1] !in sentenceStops) lo--
        // Skip leading whitespace inside the sentence so the selection
        // doesn't begin on a space.
        while (lo < len && text[lo].isWhitespace()) lo++
        // Scan forward to find the HI bound: the index of the first
        // sentence stop at or after the press point (inclusive of the stop
        // itself so the punctuation is part of the selection — feels more
        // natural to copy).
        var hi = clamped.coerceIn(0, len)
        while (hi < len && text[hi] !in sentenceStops) hi++
        if (hi < len) hi++ // include the stop character itself
        // Trim trailing whitespace.
        while (hi > lo && text[hi - 1].isWhitespace()) hi--
        if (hi <= lo) {
            // Degenerate — fall back to a single character so the user
            // still gets a visible selection to drag.
            val s = clamped.coerceIn(0, (len - 1).coerceAtLeast(0))
            return s to (s + 1).coerceAtMost(len)
        }
        return lo to hi
    }

    /** Extend the active selection's end anchor to [pos] (drag update). */
    fun extendSelectionTo(pos: TextPosition) {
        val cur = selection.value ?: return
        selection.value = cur.copy(end = pos)
    }

    /** Replace start anchor (left handle drag). */
    fun replaceStart(pos: TextPosition) {
        val cur = selection.value ?: return
        if (cur.start == pos) return
        selection.value = cur.copy(start = pos)
    }

    /** Replace end anchor (right handle drag). */
    fun replaceEnd(pos: TextPosition) {
        val cur = selection.value ?: return
        if (cur.end == pos) return
        selection.value = cur.copy(end = pos)
    }

    fun clearSelection() {
        selection.value = null
        retired.clear()
        messageMarkdownCache.clear()
        contextMenuRequest.value = null
    }

    /**
     * [T-android-mouse-text-selection] Window point at which a right-click
     * asked for the action menu, or null when no such request is pending.
     *
     * The touch path has no equivalent: there, the menu follows the selection
     * handles, because a finger that just long-pressed is still covering the
     * text and the menu has to get out of its way. A mouse cursor occludes
     * nothing, and the desktop convention users arrive with is that the menu
     * appears exactly where they clicked. So the anchor is carried separately
     * rather than derived from the selection rect.
     *
     * Cleared by [clearSelection] and whenever an action runs, so the menu
     * never outlives the selection it acts on.
     */
    val contextMenuRequest = mutableStateOf<Offset?>(null)

    fun requestContextMenu(windowPoint: Offset) {
        contextMenuRequest.value = windowPoint
    }

    fun dismissContextMenu() {
        contextMenuRequest.value = null
    }

    /**
     * Whether [pos] falls inside the active selection. Used by the right-click
     * handler to decide between "act on what is already highlighted" and
     * "select the word under the cursor first".
     *
     * Reuses [orderedEndpoints] and [isShardBetween] rather than comparing
     * offsets directly, so a selection spanning several shards or messages is
     * judged by the same document ordering the rest of the controller uses.
     * Returns false when ordering cannot be established (an endpoint shard has
     * recycled off-screen) — the caller then re-selects the word under the
     * cursor, which is the safe outcome: the menu still has a subject, and the
     * subject is one the user can see.
     */
    fun selectionContains(pos: TextPosition): Boolean {
        val sel = selection.value ?: return false
        val (first, last) = orderedEndpoints(sel) ?: return false
        if (first.shard == last.shard) {
            return pos.shard == first.shard &&
                pos.charOffset >= first.charOffset &&
                pos.charOffset <= last.charOffset
        }
        return when (pos.shard) {
            first.shard -> pos.charOffset >= first.charOffset
            last.shard -> pos.charOffset <= last.charOffset
            else -> isShardBetween(first.shard, last.shard, pos.shard)
        }
    }

    /**
     * Return the messageId common to BOTH endpoints of the active selection,
     * or null when the selection spans multiple messages (or there's no
     * selection). Used by the floating toolbar to decide whether
     * "Copy Markdown / Copy Rich Text" buttons should appear — those need
     * an unambiguous source message.
     */
    fun singleMessageId(): String? {
        val sel = selection.value ?: return null
        val a = sel.start.shard.messageId
        val b = sel.end.shard.messageId
        return if (a == b) a else null
    }

    /**
     * Window-space point at which to draw the small "handle" knob for an
     * endpoint, or null if that endpoint's shard isn't currently composed.
     * The point is the bottom of the cursor line at the endpoint's char
     * offset — start handle sits at the LEFT of the first selected char,
     * end handle at the RIGHT of the last selected char (mirrors iOS /
     * Android-native text-selection handles).
     */
    fun handleAnchor(handle: Handle): Offset? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        val endpoint = when (handle) {
            Handle.Start -> first
            Handle.End -> last
        }
        val shard = shards[endpoint.shard] ?: return null
        // When the shard is registered but its LayoutCoordinates have
        // detached (the row scrolled out of view but the composable hasn't
        // disposed yet), positionInWindow falls back to Offset.Zero — that
        // would slam the handle Popup to the top-left corner of the window.
        // Return null instead so the host simply hides the handle until the
        // shard re-attaches, matching system text-selection UX.
        val origin = shard.positionInWindow()
        if (origin == Offset.Zero) return null
        val tlr = shard.textLayoutResult
        val laidOutLen = tlr.layoutInput.text.length
        val clampedOffset = endpoint.charOffset.coerceIn(0, laidOutLen)
        if (laidOutLen <= 0) return null
        val box = runCatching {
            when (handle) {
                Handle.Start -> {
                    val charForStart = clampedOffset.coerceAtMost(laidOutLen - 1).coerceAtLeast(0)
                    tlr.getBoundingBox(charForStart)
                }
                Handle.End -> {
                    val charForEnd = (clampedOffset - 1).coerceIn(0, laidOutLen - 1)
                    tlr.getBoundingBox(charForEnd)
                }
            }
        }.getOrNull() ?: return null
        val (lx, by) = when (handle) {
            Handle.Start -> box.left to box.bottom
            Handle.End -> box.right to box.bottom
        }
        val result = Offset(origin.x + lx, origin.y + by)
        return result
    }

    /**
     * If the window-space point [p] lies within the visible-hit-target of
     * either handle's drag knob, return which one. The handle knob hangs
     * BELOW the line of text the endpoint sits on (anchor.y is the bottom
     * of the cursor line), so we look for [p] inside the rectangle
     *   x ∈ [anchor.x − hitSlopPx, anchor.x + hitSlopPx]
     *   y ∈ [anchor.y − 4, anchor.y + hitSlopPx]
     * The asymmetric vertical box stops "long-press, then tap below the
     * selected text to scroll" from being mistaken for a handle grab —
     * which was the cause of the "selection drifts as I scroll" bug.
     */
    fun grabHandleAt(p: Offset, hitSlopPx: Float): Handle? {
        val start = handleAnchor(Handle.Start)
        val end = handleAnchor(Handle.End)
        // Generous circular hit area centered slightly BELOW each anchor
        // (the visible dot hangs ~hitSlopPx below the text baseline that
        // anchor.y reports). hitSlopPx defaults to 48 dp at the call site —
        // about a thumb-pad's worth of forgiveness in any direction.
        fun within(anchor: Offset?): Float {
            if (anchor == null) return Float.MAX_VALUE
            // Bias center downward so a press AT the dot's visible position
            // counts; the dot is ~hitSlopPx/2 below anchor.y.
            val cx = anchor.x
            val cy = anchor.y + hitSlopPx / 2f
            val dx = p.x - cx
            val dy = p.y - cy
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val ds = within(start)
        val de = within(end)
        val nearest = if (ds <= de) Handle.Start to ds else Handle.End to de
        return if (nearest.second <= hitSlopPx * 1.5f) nearest.first else null
    }

    private fun distance(a: Offset, b: Offset): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /**
     * Compute the union bounding rectangle (window space) of the active
     * selection, walking only registered shards. Used to position the
     * floating toolbar. Returns null if no selection or none of the
     * selection's shards are currently composed.
     */
    fun selectionWindowRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        val firstShard = shards[first.shard] ?: return null
        val lastShard = shards[last.shard] ?: return null
        val firstBox = firstShard.textLayoutResult.getBoundingBox(
            first.charOffset.coerceIn(0, firstShard.textLayoutResult.layoutInput.text.length - 1)
                .coerceAtLeast(0)
        )
        val lastBox = lastShard.textLayoutResult.getBoundingBox(
            last.charOffset.coerceIn(0, lastShard.textLayoutResult.layoutInput.text.length - 1)
                .coerceAtLeast(0)
        )
        val topLeft = firstShard.positionInWindow() + Offset(firstBox.left, firstBox.top)
        val bottomRight = lastShard.positionInWindow() + Offset(lastBox.right, lastBox.bottom)
        return Rect(topLeft, bottomRight)
    }

    /**
     * Union the visible parts of the active selection across all currently-
     * composed shards. Returns the bounding rect of what's actually drawn
     * on screen right now, in window coords — which may be a subset of the
     * full logical selection when either endpoint shard has scrolled off-
     * screen. Used by the floating toolbar to track the visible portion
     * instead of vanishing the moment a selection endpoint goes off-view.
     *
     * Returns null when no part of the selection is currently drawable
     * (both endpoints AND all in-between shards are off-screen). Callers
     * are expected to fall back to a fixed anchor position in that case.
     */
    /**
     * Window-space rect of the LAST visible line of the active selection —
     * the line that contains the end handle (or, if the end handle's shard
     * is off-screen, the bottom-most line of the lowest still-visible
     * shard in the selection). Used by the floating toolbar to anchor
     * itself above the END of the selection so the menu and the end
     * handle stay clustered together rather than the menu floating up by
     * the start handle (which can be many lines away).
     */
    /**
     * Window-space horizontal mid-point between the two handles, used by
     * the toolbar to center itself "between" the handles instead of above
     * the geometric center of the highlight (which on multi-line selections
     * sits in the middle of a paragraph). Falls back to null when neither
     * endpoint is currently registered — caller should keep its own
     * default.
     */
    fun handlesCenterX(): Float? {
        val s = handleAnchor(Handle.Start)
        val e = handleAnchor(Handle.End)
        return when {
            s != null && e != null -> (s.x + e.x) / 2f
            s != null -> s.x
            e != null -> e.x
            else -> null
        }
    }

    /**
     * Window-space rect of the text line under a specific handle's
     * endpoint — used during a handle drag so the floating toolbar can
     * follow whichever handle the user is moving. Returns null when the
     * handle's shard isn't currently registered (off-screen). Sized to
     * the line height of the character under the endpoint, not the whole
     * shard, so the toolbar hugs the actual moving line rather than the
     * entire paragraph.
     */
    fun draggedHandleLineRect(handle: Handle): Rect? {
        val sel = selection.value ?: return null
        // The Handle enum corresponds to the visual START / END of the
        // selection in document order, not selection.start vs selection.end
        // (which depend on which side the user grabbed first). Use the
        // ordered pair so a "drag the right handle" gesture always tracks
        // the bottom/right endpoint regardless of whether it's stored in
        // selection.start or selection.end.
        val ordered = orderedEndpoints(sel) ?: return null
        val endpoint = when (handle) {
            Handle.Start -> ordered.first
            Handle.End -> ordered.second
        }
        val shard = shards[endpoint.shard] ?: return null
        val origin = shard.positionInWindow()
        if (origin == Offset.Zero) return null
        val tlr = shard.textLayoutResult
        val len = tlr.layoutInput.text.length
        if (len <= 0) return null
        val clamped = endpoint.charOffset.coerceIn(0, len)
        val charIdx = when (handle) {
            Handle.Start -> clamped.coerceAtMost(len - 1).coerceAtLeast(0)
            Handle.End -> (clamped - 1).coerceIn(0, len - 1)
        }
        val box = runCatching { tlr.getBoundingBox(charIdx) }.getOrNull() ?: return null
        return Rect(
            left = origin.x + box.left,
            top = origin.y + box.top,
            right = origin.x + box.right,
            bottom = origin.y + box.bottom,
        )
    }

    fun visibleSelectionEndLineRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered

        // Prefer the actual end shard if it's still composed and on screen.
        val lastShard = shards[last.shard]
        if (lastShard != null && lastShard.positionInWindow() != Offset.Zero) {
            val tlr = lastShard.textLayoutResult
            val len = tlr.layoutInput.text.length
            if (len > 0) {
                val charIdx = (last.charOffset - 1).coerceIn(0, len - 1)
                val box = runCatching { tlr.getBoundingBox(charIdx) }.getOrNull()
                if (box != null) {
                    val origin = lastShard.positionInWindow()
                    return Rect(
                        left = origin.x + box.left,
                        top = origin.y + box.top,
                        right = origin.x + box.right,
                        bottom = origin.y + box.bottom,
                    )
                }
            }
        }

        // Fallback: end handle's shard scrolled off-screen. Find the
        // BOTTOM-most still-composed shard that's part of the selection —
        // that's the visual "tail" of what the user sees as the highlight.
        var bestShard: TextShard? = null
        var bestBottom = Float.NEGATIVE_INFINITY
        for ((id, shard) in shards) {
            val origin = shard.positionInWindow()
            if (origin == Offset.Zero) continue
            val isFirst = id == first.shard
            val isLast = id == last.shard
            val isBetween = !isFirst && !isLast &&
                isShardBetween(first.shard, last.shard, id)
            if (!isFirst && !isLast && !isBetween) continue
            val bottom = origin.y + shard.sizePx().height
            if (bottom > bestBottom) {
                bestBottom = bottom
                bestShard = shard
            }
        }
        val tail = bestShard ?: return null
        val tlr = tail.textLayoutResult
        val len = tlr.layoutInput.text.length
        if (len <= 0) return null
        // Use the LAST char of this shard for the visual line.
        val lineCount = tlr.lineCount
        if (lineCount <= 0) return null
        val lastLine = lineCount - 1
        val lineTop = runCatching { tlr.getLineTop(lastLine) }.getOrNull() ?: return null
        val lineBottom = runCatching { tlr.getLineBottom(lastLine) }.getOrNull() ?: return null
        val origin = tail.positionInWindow()
        // Approximate the line's visible x range via the shard's body width.
        return Rect(
            left = origin.x,
            top = origin.y + lineTop,
            right = origin.x + tail.sizePx().width,
            bottom = origin.y + lineBottom,
        )
    }

    fun visibleSelectionWindowRect(): Rect? {
        val sel = selection.value ?: return null
        val ordered = orderedEndpoints(sel) ?: return null
        val (first, last) = ordered
        var minL = Float.POSITIVE_INFINITY
        var minT = Float.POSITIVE_INFINITY
        var maxR = Float.NEGATIVE_INFINITY
        var maxB = Float.NEGATIVE_INFINITY
        for ((id, shard) in shards) {
            val isFirstHere = id == first.shard
            val isLastHere = id == last.shard
            val isBetween = !isFirstHere && !isLastHere &&
                isShardBetween(first.shard, last.shard, id)
            if (!isFirstHere && !isLastHere && !isBetween) continue
            val tlr = shard.textLayoutResult
            val laidOutLen = tlr.layoutInput.text.length
            if (laidOutLen <= 0) continue
            val from = if (isFirstHere) first.charOffset.coerceIn(0, laidOutLen) else 0
            val to = if (isLastHere) last.charOffset.coerceIn(0, laidOutLen) else laidOutLen
            val lo = minOf(from, to)
            val hi = maxOf(from, to)
            if (hi <= lo) continue
            val origin = shard.positionInWindow()
            // The shard's positionInWindow falls back to Offset.Zero when
            // the LayoutCoordinates have detached — skip those, they
            // wouldn't be drawn anyway.
            if (origin == Offset.Zero) continue
            val startBox = runCatching {
                tlr.getBoundingBox((lo).coerceAtMost(laidOutLen - 1).coerceAtLeast(0))
            }.getOrNull() ?: continue
            val endBox = runCatching {
                tlr.getBoundingBox((hi - 1).coerceIn(0, laidOutLen - 1))
            }.getOrNull() ?: continue
            // Union both boxes — covers the case where the selection spans
            // multiple lines within this shard.
            val left = origin.x + minOf(startBox.left, endBox.left)
            val top = origin.y + minOf(startBox.top, endBox.top)
            val right = origin.x + maxOf(startBox.right, endBox.right)
            val bottom = origin.y + maxOf(startBox.bottom, endBox.bottom)
            if (left < minL) minL = left
            if (top < minT) minT = top
            if (right > maxR) maxR = right
            if (bottom > maxB) maxB = bottom
        }
        if (!minL.isFinite() || !maxR.isFinite() || maxR <= minL || maxB <= minT) return null
        return Rect(minL, minT, maxR, maxB)
    }

    /**
     * Returns (firstEndpoint, lastEndpoint) ordered by *visual* position so
     * the caller can iterate left-to-right / top-to-bottom regardless of
     * which handle the user grabbed. Requires both endpoints' shards to be
     * registered (so we can compare window-y).
     */
    fun orderedEndpoints(sel: TextSelection): Pair<TextPosition, TextPosition>? {
        val a = sel.start
        val b = sel.end
        if (a.shard == b.shard) {
            return if (a.charOffset <= b.charOffset) a to b else b to a
        }
        // Prefer stable document-order index when both endpoints have one,
        // because that survives a shard being off-screen / unregistered.
        // Fall back to y-comparison if we can't get an index for either.
        val ka = shardOrderKey(a.shard)
        val kb = shardOrderKey(b.shard)
        if (ka != null && kb != null && a.shard.messageId == b.shard.messageId) {
            return if (ka <= kb) a to b else b to a
        }
        // [T-cross-shard-copy-recycled] 同一个块内的两个子分片
        // (`text:<blockId>#<sub>` / `mdblock:…:<idx>#<sub>`) 也有确定的文档序：
        // 块 id 不是数字时 [shardOrderKey] 取不到键，但同根之下子序号就是文档序。
        // 少了这条，端点分片一旦被回收，`selectedPlainText` 就落进「只有已注册分片
        // 才可用」的兜底，复制结果被截断成末分片的半截前缀。
        if (a.shard.messageId == b.shard.messageId &&
            shardIdRoot(a.shard.shardId) == shardIdRoot(b.shard.shardId)
        ) {
            val sa = subIndexOrNull(a.shard.shardId)
            val sb = subIndexOrNull(b.shard.shardId)
            if (sa != null && sb != null) return if (sa <= sb) a to b else b to a
        }
        // [T-u6-sel-merge] 上游的文档序（GH#296）接在这两级之后：跨块、跨消息的
        // 端点没有数字块序号，[shardOrderKey] 给不出键，而 [compareShards] 会先问
        // 面板注入的 [documentOrder]（DocOrder = 行号 + 子序号，跨块跨消息都能排），
        // 再退到 [sameBlockOrder] 与 y（已回收的分片答它离开时的 y）。两段合起来：
        // 无 provider 的面板靠我们的键序，有 provider 的面板靠上游的文档序，都拿不到
        // 才返回 null —— 与上游一致，调用方据此按「顺序未知」处理而不是瞎猜。
        val c = compareShards(a.shard, b.shard) ?: return null
        return if (c <= 0) a to b else b to a
    }

    /**
     * Build the plain-text string covered by the active selection.
     *
     * [T-cross-shard-copy-recycled] Shards not currently composed are served by
     * the controller's document-level table (every shard that ever registered),
     * so an endpoint that LazyColumn recycled no longer truncates or empties
     * the copy. The explicit fallback chain — shard walk → message-markdown
     * slice → collapsed substring — exists so that Copy never silently yields
     * an empty string.
     */
    fun selectedPlainText(documentRegistry: Map<TextShardId, String> = emptyMap()): String {
        val sel = selection.value ?: return ""

        // [T-android-copy-selection-not-whole-message] orderedEndpoints()
        // returns null only when an endpoint shard recycled off-screen AND has
        // no document-order key (long drag). Fall back to the precise per-shard
        // walk over whatever shards ARE still registered — NOT the whole cached
        // message. (The prior fix [T-android-copy-long-reply-incomplete]
        // returned the entire message markdown here and in the cross-shard
        // branch below, which is why a partial multi-shard selection pasted the
        // full reply.) If even the walk comes up empty (genuinely-degenerate
        // single-shard recycle), keep the original collapsed substring.
        val ordered = orderedEndpoints(sel) ?: run {
            val walked = crossShardSelectedText(sel.start, sel.end, documentRegistry)
            return walked.ifEmpty {
                markdownSliceFallback(sel.start, sel.end, documentRegistry) ?: collapsedFallback(sel)
            }
        }
        val (first, last) = ordered

        // Single-shard selection — precise substring of the plainText.
        // Intentionally NOT routed through the message cache: a within-one-
        // shard selection is a normal short/partial-paragraph copy and must
        // stay an exact substring.
        if (first.shard == last.shard) {
            // [T-cross-shard-copy-gap-notice] 文本取不到（分片被回收 + documentTextMap
            // 的 LRU 淘汰 + 调用方注册表也没有）时不再静默返回空串：空串会让
            // 「复制」变成一次无声的 no-op（toast 都不弹），用户以为复制成功。
            val txt = textOf(first.shard, documentRegistry) ?: return unavailableShardNotice(1)
            val a = first.charOffset.coerceIn(0, txt.length)
            val b = last.charOffset.coerceIn(0, txt.length)
            return txt.substring(minOf(a, b), maxOf(a, b))
        }

        val walked = crossShardSelectedText(first, last, documentRegistry)
        return walked.ifEmpty {
            markdownSliceFallback(first, last, documentRegistry) ?: collapsedFallback(sel)
        }
    }

    /**
     * [T-android-copy-selection-not-whole-message] Build the text covered by a
     * cross-shard selection, bounded to the selection — never the whole
     * message.
     *
     * [T-cross-shard-copy-recycled] 两个端点分片**单独取片**（文本可来自文档级表，
     * 即分片已被回收也能取到），中间分片按 [selectionSpanInDocumentOrder] 的文档序
     * 补齐。旧实现把三者混在「遍历当前已注册分片」的一次循环里：首分片缺席 →
     * `started` 恒 false → 中间分片被整段跳过，而末分片仍被无条件追加，于是复制
     * 结果只剩末分片的半截前缀。
     *
     * The walk's only blind spot is non-shard blocks (code fences, tables,
     * math display) that render via their own Text composables and so drop out
     * of the shard registry. To recover THOSE without dumping the entire reply,
     * we splice in the slice of the cached message markdown that sits strictly
     * between the first selected shard's text and the last selected shard's
     * text — and only when such a gap actually exists. If the markdown anchors
     * can't be located, we keep the precise shard-walk result rather than
     * falling back to the full message.
     *
     * [T-cross-shard-copy-gap-notice] 走查里**取不到文本**的分片（端点分片被回收后
     * 又超出了 [MAX_DOCUMENT_SHARDS] 条 / [MAX_DOCUMENT_CHARS] 字符的 LRU；同一处
     * `?: continue` 的兜底也一并计数），以及 [T-cross-shard-copy-span-blind] 里
     * **根本枚举不到**的中间分片（已回收 + 无数字序号），都会让复制结果少一段。这种
     * 缺口必须显式：能定位锚点就退到该消息 markdown 的选中段（原文完整），否则在
     * 结果里写明缺了几段——静默少复制正是 B1 要消灭的形态，只是换了个触发器。
     */
    private fun crossShardSelectedText(
        first: TextPosition,
        last: TextPosition,
        documentRegistry: Map<TextShardId, String>,
    ): String {
        val sb = StringBuilder()
        var unavailable = 0
        // 头片段：端点可能已回收，文本从文档级表取。
        val headText = textOf(first.shard, documentRegistry)
        if (headText == null) {
            unavailable += 1
        } else {
            sb.append(headText.substring(first.charOffset.coerceIn(0, headText.length), headText.length))
        }
        // 中间分片：严格位于两端之间、按文档序。
        val span = selectionSpanInDocumentOrder(first, last, documentRegistry)
        for (id in span.middles) {
            val txt = textOf(id, documentRegistry)
            if (txt == null) {
                unavailable += 1
                continue
            }
            if (txt.isEmpty()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(txt)
        }
        // 尾片段：同上，端点回收后仍要有。
        val tailText = textOf(last.shard, documentRegistry)
        if (tailText == null) {
            unavailable += 1
        } else {
            val head = tailText.substring(0, last.charOffset.coerceIn(0, tailText.length))
            if (head.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(head)
            }
        }
        val walk = sb.toString()

        // [T-cross-shard-copy-span-blind] 跨度枚举不可信 = 至少有一段中间内容看不见
        // （已回收 + 定不了序的分片）。不知道缺几段时按 1 计：提示的价值在于「这一份
        // 不完整」这个事实本身；下面的分支会优先改用该消息 markdown 的选中段
        // （原文完整），拿不到锚点才把缺口写进结果。
        if (!span.complete) unavailable += 1

        if (unavailable > 0) {
            // 两端锚点都在（缺的是中间）时，markdown 选中段是完整原文——优先它。
            // [T-cross-shard-copy-span-blind] 仅限同消息：跨消息选区没有单一源串，
            // 拿头部的 markdown 去找尾部的锚点只会切出一段错内容。
            //
            // [T-cross-shard-copy-gap-notice] 但缺口提示必须跟着一起走：这一份的完整性
            // 是「锚点定位成功」推出来的，不是数出来的（锚点重复、端点分片被回收都可能
            // 定位失败或落到错误区间）。静默返回 slice 就是把不确定当成确定——B1 要消灭
            // 的正是这种静默缺口，只是换了个触发器。
            if (first.shard.messageId == last.shard.messageId) {
                selectionMarkdownSlice(first, last, documentRegistry)?.let { slice ->
                    val notice = unavailableShardNotice(unavailable)
                    return if (slice.isEmpty()) notice else slice + "\n\n" + notice
                }
            }
            val notice = unavailableShardNotice(unavailable)
            return if (walk.isEmpty()) notice else walk + "\n\n" + notice
        }

        // Same-message selection: try to recover non-shard blocks (code /
        // tables) inside the selected span by slicing the cached markdown
        // between the selection's head and tail anchors. Cross-message
        // selections have no single source string, so the walk stands.
        if (first.shard.messageId == last.shard.messageId) {
            spliceNonShardSpan(first, last, documentRegistry)?.let { return it }
        }
        return walk
    }

    /**
     * [T-cross-shard-copy-gap-notice] 选区里取不到文本的分片数 → 结果里必须能看见的
     * 提示文案（复制/引用/朗读都会带走它，用户据此知道这一份不完整）。
     *
     * [T-cross-shard-copy-gap-notice-i18n] 文案本体在 `values/strings.xml`
     * （`chat_selection_copy_gap_notice`，含 `values-zh` / `values-zh-rTW`），由
     * [RegisterSelectionShard] 在组合期装进 [gapNoticeTemplate]。控制器本身不碰
     * Android 资源（JVM 单测可直接裸构造），[DEFAULT_GAP_NOTICE_TEMPLATE] 只是
     * 没有资源时的兜底，与 zh 资源逐字一致。
     */
    internal var gapNoticeTemplate: String? = null

    private fun unavailableShardNotice(count: Int): String =
        (gapNoticeTemplate ?: DEFAULT_GAP_NOTICE_TEMPLATE)
            .replace(GAP_NOTICE_COUNT_PLACEHOLDER, count.toString())

    /**
     * [T-cross-shard-copy-span-blind] 文档序跨度的枚举结果：[middles] 是能定位的
     * 中间分片，[complete] 说明这次枚举**是否可信**。
     *
     * `complete = false` 的含义是「还有分片可能落在跨度里，但我们看不见它」——
     * 已回收（不在注册表里）又没有数字序号（定不了序）的分片正是这种。旧实现在
     * 无序兜底路径上只遍历当前注册的分片：回收掉的那些连「缺口」都计不进去，
     * 复制结果少一段却没有任何提示——静默缺口换了个触发器，B1 要消灭的形态。
     */
    private class ShardSpan(val middles: List<TextShardId>, val complete: Boolean)

    /**
     * [T-cross-shard-copy-recycled] 选区跨度内、按文档序排列的**中间**分片（不含
     * 两个端点自身）。三种定序来源，按可靠性依次尝试：
     *
     *  1. `mdblock:<parent>:<idx>#<sub>` —— 同一消息内按 (块序号, 子序号) 严格定序。
     *     候选来自**文档级表**（含已回收分片），所以首分片回收不再把跨度截断；
     *  2. 同一个根分片（`text:<blockId>#<sub>` 这类块 id 不是数字）—— 根相同即只有
     *     子序号能定序，子序号就是文档序；
     *  3. 其余（跨消息 / 无序号 id）—— 只有当前注册的分片有可信的窗口 y 序，
     *     退回 [isShardBetween]（与修复前一致）。
     *
     * 1/2 都带一道保险：只要有**我们知道的**同消息分片无法进入该文档序，就退回 3
     * ——旧路径包含它们，键序漏掉就是回归。3 无法枚举的分片则由 [ShardSpan.complete]
     * 标成缺口（[T-cross-shard-copy-span-blind]）。
     */
    private fun selectionSpanInDocumentOrder(
        first: TextPosition,
        last: TextPosition,
        documentRegistry: Map<TextShardId, String>,
    ): ShardSpan {
        val a = first.shard
        val b = last.shard
        if (a.messageId == b.messageId) {
            // 界内所有「我们知道存在」的分片：注册中的 + 已回收但留有文本的 +
            // 调用方注册表里的。只看 shards 会让回收掉的无序号分片漏出键序枚举。
            val known = knownShardIdsIn(a.messageId, documentRegistry)
            val ka = shardOrderKey(a)
            val kb = shardOrderKey(b)
            if (ka != null && kb != null && known.none { shardOrderKey(it) == null }) {
                return ShardSpan(keyedSpan(a, b, ka, kb, ::shardOrderKey), complete = true)
            }
            val root = shardIdRoot(a.shardId)
            if (root == shardIdRoot(b.shardId)) {
                // [T-u6-sel-merge] 这里必须用我们自己的 [subIndexOrNull]（三态：有子号
                // / 无子号），不能用上游 SelectionDocumentOrder.kt 的 shardSubIndex
                // ——后者把「没有 #n」和「#0」都算成 0，unplaceable 会恒为 0，跨度被
                // 误判成 complete，缺口不再计数：正是审计要消灭的静默少复制。
                val sa = subIndexOrNull(a.shardId)
                val sb = subIndexOrNull(b.shardId)
                // 子序号只给「同根 + 有子序号」的分片定序：同根无子号、或异根又无块
                // 序号的分片都可能落在跨度里却排不上，有它们就不能声称枚举完整。
                val unplaceable = known.count { id ->
                    if (shardIdRoot(id.shardId) == root) {
                        subIndexOrNull(id.shardId) == null
                    } else {
                        shardOrderKey(id) == null
                    }
                }
                if (sa != null && sb != null && unplaceable == 0) {
                    return ShardSpan(
                        keyedSpan(a, b, sa.toLong(), sb.toLong()) { id ->
                            if (shardIdRoot(id.shardId) == root) subIndexOrNull(id.shardId)?.toLong() else null
                        },
                        complete = true,
                    )
                }
            }
        }
        return fallbackSpan(a, b, documentRegistry)
    }

    /**
     * [T-cross-shard-copy-span-blind] 第 3 类兜底：文档序 / y 序兜底枚举 + 完整性判定。
     *
     * y 序只在**两端都注册且坐标已挂载**时才可信（[positionedInWindow]）：视口是连续
     * 的，两个可见分片之间的一切必然也已组合（[isShardBetween] 的 y 分支同样要求三者
     * 在册且定得了位）。否则跨越已回收分片的那一段既排不上序、也进不了 [middles]，
     * 只能按缺口计。
     *
     * [T-u6-sel-merge] 候选集换成 [knownShardsInOrder]（组合中的 + 已回收的 + 文档级
     * 表里的 + 调用方注册表里的，按文档序、其次 y 序）。原先只看 `shards.values`
     * ——「当下组合出来的 TextShard」——经 [registerText] 登记的 [ShardText] 分片与
     * **已回收**的分片全都看不见，而这正是上游 GH#296 的形状：端点一滚出屏幕，中间段落
     * 就整段消失（复制只剩末分片的半截前缀，[complete] 还谎称枚举完整）。上游同一处
     * 走的就是已知分片表（`knownShardsInOrder(documentRegistry)`）。
     *
     * [T-u6-sel-merge] 完整性再补上文档序这一路：端点没有窗口坐标、但跨度涉及的消息里
     * **每个已知分片都能用 [documentOrder] 定序**时，谁落在跨度里由全序唯一决定，不必
     * 再靠坐标去证明（见 [spanOrderable]）。缺了这条，跨消息选区——本就拿不到单一窗口
     * 坐标——永远被当成有缺口，复制结果后面会挂一条不该有的提示。
     */
    private fun fallbackSpan(
        a: TextShardId,
        b: TextShardId,
        documentRegistry: Map<TextShardId, String>,
    ): ShardSpan {
        val middles = knownShardsInOrder(documentRegistry)
            .filter { it != a && it != b && isShardBetween(a, b, it) }
        // [T-cross-shard-copy-y-fallback-position] 「注册了」不等于「定得了位」：
        // LayoutCoordinates 已 detach 时 positionInWindow 回退 Offset.Zero，两端点的 y
        // 于是都是 0，y 序分支（`ym in 0..0`）会把注册表里每个未挂载分片都判成「在中间」
        // 而真正在中间的已挂载分片被漏掉。旧实现只看 `shards[a] != null` 就声称
        // complete = true：一处都定位不了却报「枚举完整」——静默缺口正是这么来的。
        val endpointsPositioned = positionedInWindow(a) && positionedInWindow(b)
        val complete = when {
            endpointsPositioned -> true
            spanOrderable(a, b, documentRegistry) -> true
            a.messageId != b.messageId -> false
            else -> {
                // 端点有回收的：同消息里任何「不是端点、也没进跨度」的已知分片都可能
                // 正落在跨度内（它无序号、无坐标，我们证明不了它在外），计缺口。
                val inSpan = middles.toHashSet()
                knownShardIdsIn(a.messageId, documentRegistry)
                    .none { it != a && it != b && it !in inSpan }
            }
        }
        return ShardSpan(middles, complete)
    }

    /**
     * [T-u6-sel-merge] 这个跨度能不能用文档序**证明**：[documentOrder] 在册、两个端点
     * 定得了序，且跨度涉及的两条消息里每个已知分片都定得了序。
     *
     * 全序意味着「谁在跨度内」不是猜的：[isShardBetween] 对每个候选都给得出确定答案。
     * 定不了序的分片（行不在已加载窗口里）可能正落在跨度内，那种情况仍按缺口计
     * ——fail-closed 不变。两条消息都要查：跨消息选区的中间分片可能来自任意一侧。
     */
    private fun spanOrderable(
        a: TextShardId,
        b: TextShardId,
        documentRegistry: Map<TextShardId, String>,
    ): Boolean {
        val order = documentOrder ?: return false
        if (order(a) == null || order(b) == null) return false
        return knownShardIdsIn(a.messageId, documentRegistry).all { order(it) != null } &&
            knownShardIdsIn(b.messageId, documentRegistry).all { order(it) != null }
    }

    /**
     * [T-cross-shard-copy-y-fallback-position] 分片是否**注册且坐标已挂载**。
     * `positionInWindow` 对 detach 的 LayoutCoordinates 回退 [Offset.Zero]，与
     * [handleAnchor] / [visibleSelectionEndLineRect] 用的是同一个哨兵值。
     */
    private fun positionedInWindow(id: TextShardId): Boolean =
        shards[id]?.positionInWindow()?.let { it != Offset.Zero } == true

    /**
     * [T-cross-shard-copy-span-blind] 该消息里**我们知道存在**的分片：注册中的、
     * 文档级表里留有文本的（已回收）、以及调用方注册表里的。
     */
    private fun knownShardIdsIn(
        messageId: String,
        documentRegistry: Map<TextShardId, String>,
    ): List<TextShardId> = buildList {
        addAll(knownShardIds())
        addAll(documentRegistry.keys)
    }.filter { it.messageId == messageId }.distinct()

    /** [T-cross-shard-copy-recycled] 键序跨度：严格介于 [keyFirst]/[keyLast] 之间。 */
    private fun keyedSpan(
        first: TextShardId,
        last: TextShardId,
        keyFirst: Long,
        keyLast: Long,
        keyOf: (TextShardId) -> Long?,
    ): List<TextShardId> {
        val lo = minOf(keyFirst, keyLast)
        val hi = maxOf(keyFirst, keyLast)
        return knownShardIds()
            .filter { it.messageId == first.messageId && it != first && it != last }
            .mapNotNull { id -> keyOf(id)?.let { k -> if (k > lo && k < hi) k to id else null } }
            .sortedBy { it.first }
            .map { it.second }
    }

    /**
     * [T-cross-shard-copy-recycled] 走查为空时的兜底：退回该消息 markdown 里
     * **两个端点分片之间**的那一段（原始 markdown，保留 ``` 围栏等）。
     *
     * 走查为空意味着选区里没有一个字符来自分片文本——最典型的形状是「选区正好从
     * 段落 1 末尾起、到段落 3 开头止，中间整段是代码围栏 / 表格这类非分片块」。
     * 这时锚点取自两个端点分片的**整段文本**（头段的尾锚 + 尾段的头锚），而不是选中
     * 的那 0 个字符。找不到锚点就返回 null，由调用方退到 collapsedFallback：宁可不
     * 复制，也不要拿整条回复冒充选区（[T-android-copy-long-reply-incomplete] 的
     * 教训）。
     */
    private fun markdownSliceFallback(
        first: TextPosition,
        last: TextPosition,
        documentRegistry: Map<TextShardId, String>,
    ): String? {
        if (first.shard.messageId != last.shard.messageId) return null
        val md = messageMarkdownCache[first.shard.messageId]?.takeIf { it.isNotEmpty() } ?: return null
        val firstText = textOf(first.shard, documentRegistry).orEmpty()
        val lastText = textOf(last.shard, documentRegistry).orEmpty()
        val headAnchor = anchorFor(firstText, fromStart = false)
        val tailAnchor = anchorFor(lastText, fromStart = true)
        if (headAnchor.isEmpty() || tailAnchor.isEmpty()) return null
        // [T-cross-shard-copy-anchor-offset-match] 头锚点是头分片**末尾**的 24 字符，
        // 它在分片文本里的位置就是「选区偏移」：按位置消歧，别拿首个出现（见
        // [bestAnchorOccurrence]）。
        val headIdx = bestAnchorOccurrence(
            md = md,
            anchor = headAnchor,
            shardText = firstText,
            anchorStart = firstText.trimEnd().length - headAnchor.length,
        )
        if (headIdx < 0) return null
        val startIdx = headIdx + headAnchor.length
        val tailIdx = md.indexOf(tailAnchor, startIdx)
        if (tailIdx < 0) return null
        return md.substring(startIdx, tailIdx).trim('\n')
    }

    /**
     * [T-android-copy-selection-not-whole-message] When a same-message cross-
     * shard selection spans a non-shard block (code/table/math), return the
     * slice of the cached message markdown bounded by the selection's head and
     * tail anchors. Returns null when there's no cache, no usable anchors, or
     * the selected span contains no extra (non-shard) content beyond the
     * shard-walk text — in which case the caller keeps the exact shard walk.
     */
    private fun spliceNonShardSpan(
        first: TextPosition,
        last: TextPosition,
        documentRegistry: Map<TextShardId, String>,
    ): String? {
        // Only prefer the markdown slice when the selected span actually
        // crosses a NON-SHARD block — a fenced code block (``` / ~~~), a table
        // (pipe rows), or display math ($$). Those render via their own Text
        // composables (no MinisTextKit shard), so the per-shard walk drops them
        // and the markdown slice is the only way to recover them. For a plain
        // run of paragraphs / headings / list items (all shards), the walk is
        // already exact and formatting-free, so prefer it — returning the
        // markdown slice there would re-introduce `#`, `**`, etc. the user
        // didn't select as rendered text.
        val slice = selectionMarkdownSlice(first, last, documentRegistry) ?: return null
        return if (sliceCrossesNonShardBlock(slice)) slice else null
    }

    /**
     * [T-cross-shard-copy-gap-notice] 选区在**该消息 markdown** 里的那一整段
     * （含两端锚点本身），原始 markdown、保留围栏等。定位失败（没有缓存 / 端点分片
     * 的文本也已取不到 → 锚点为空 / 锚点在 markdown 里找不到 / 顺序倒置）返回 null。
     *
     * 与 [markdownSliceFallback] 的区别：这里用**选中部分**当锚点（头端点从
     * charOffset 起、尾端点止于 charOffset），所以切片就是用户选中的那段；
     * [markdownSliceFallback] 用的是两个端点分片的整段文本，只在走查完全为空
     * （选区正好落在非分片块之间）时才成立。
     */
    private fun selectionMarkdownSlice(
        first: TextPosition,
        last: TextPosition,
        documentRegistry: Map<TextShardId, String>,
    ): String? {
        val md = messageMarkdownCache[first.shard.messageId]?.takeIf { it.isNotEmpty() } ?: return null

        val firstText = textOf(first.shard, documentRegistry) ?: return null
        val lastText = textOf(last.shard, documentRegistry) ?: return null

        // Head anchor: a run of the selected text right after the start offset.
        val headSel = firstText.substring(first.charOffset.coerceIn(0, firstText.length))
        // Tail anchor: a run of the selected text right before the end offset.
        val tailSel = lastText.substring(0, last.charOffset.coerceIn(0, lastText.length))

        val headAnchor = anchorFor(headSel, fromStart = true)
        val tailAnchor = anchorFor(tailSel, fromStart = false)
        if (headAnchor.isEmpty() || tailAnchor.isEmpty()) return null

        // [T-cross-shard-copy-anchor-offset-match] 头锚点用**选区偏移**消歧：锚点只有
        // 24 字符，同一段样板文字在回复里出现两次时，`md.indexOf(headAnchor)` 取的首个
        // 出现可能是更早的那一处 —— 切出来的正是用户没选的内容。分片文本在选区起点
        // 前后各是什么，就是这段偏移信息（被 trim 掉的前导空白要算回来，[anchorFor]
        // 取的是 trim 后的头 24 字符）。
        val headAnchorStart = first.charOffset.coerceIn(0, firstText.length) +
            (headSel.length - headSel.trimStart().length)
        val startIdx = bestAnchorOccurrence(md, headAnchor, firstText, headAnchorStart)
        if (startIdx < 0) return null
        val tailIdx = md.indexOf(tailAnchor, startIdx + headAnchor.length)
        if (tailIdx < 0) return null
        val endIdx = tailIdx + tailAnchor.length
        if (endIdx <= startIdx) return null

        return md.substring(startIdx, endIdx)
    }

    /**
     * [T-cross-shard-copy-anchor-offset-match] 在 [md] 里定位 [anchor] 的出现位置，用
     * **选区偏移**消歧：锚点最多 24 字符（[anchorFor]），重复出现时 `indexOf` 取「首个」
     * 会切到更早的区间，复制到用户没选的内容。
     *
     * 偏移信息就是分片自己的文本：锚点在分片文本里的位置是 [anchorStart]，正确的那一处
     * 出现会让「锚点前后的 markdown」与「锚点前后的分片文本」对得上（正文段落里渲染
     * 文本与 markdown 逐字相同；行内格式会让两侧很快分叉，得分自然变低）。取两侧公共串
     * 最长者；全部分数相同（对不上）时保持旧行为——首个出现。
     *
     * 比较长度封顶 [ANCHOR_CONTEXT_CAP]：分片文本可能是巨段落，不封顶会让消歧本身变成
     * 「出现次数 × 文本长度」——复制跑在主线程上，这个上界必须是常数。
     */
    private fun bestAnchorOccurrence(
        md: String,
        anchor: String,
        shardText: String,
        anchorStart: Int,
    ): Int {
        if (anchor.isEmpty()) return -1
        var bestIdx = -1
        var bestScore = -1
        var idx = md.indexOf(anchor)
        while (idx >= 0) {
            // 锚点右侧：markdown 的续文 vs 分片文本的续文。
            var score = 0
            var m = idx + anchor.length
            var s = anchorStart + anchor.length
            while (score < ANCHOR_CONTEXT_CAP && m < md.length && s < shardText.length &&
                md[m] == shardText[s]
            ) {
                m++
                s++
                score++
            }
            // 锚点左侧：选区起点之前的那一段（没被选中，但它的内容说明我们在哪一块）。
            var back = 0
            m = idx - 1
            s = anchorStart - 1
            while (back < ANCHOR_CONTEXT_CAP && m >= 0 && s >= 0 && md[m] == shardText[s]) {
                m--
                s--
                back++
            }
            score += back
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
            if (bestScore >= ANCHOR_CONTEXT_CAP * 2) break // 两侧都满配：就是这一处
            idx = md.indexOf(anchor, idx + 1)
        }
        return bestIdx
    }

    /**
     * [T-android-copy-selection-not-whole-message] Heuristic: does this raw-
     * markdown slice contain a block that doesn't register as a MinisTextKit
     * text shard (fenced code, table, or display math)? Used to decide whether
     * the markdown slice carries content the per-shard walk would have dropped.
     */
    private fun sliceCrossesNonShardBlock(slice: String): Boolean {
        if (slice.contains("```") || slice.contains("~~~")) return true
        if (slice.contains("$$")) return true
        // A markdown table needs a header row plus a delimiter row of dashes
        // and pipes (e.g. `|---|---|`). Look for that delimiter shape so a
        // stray inline `|` in prose doesn't trigger a false positive.
        val tableDelimiter = Regex("""(?m)^\s*\|?\s*:?-{3,}.*\|""")
        return tableDelimiter.containsMatchIn(slice)
    }

    /**
     * Pick a short, distinctive anchor substring from one end of a selected
     * run so it can be located in the raw markdown. Trims whitespace and caps
     * the length to keep the match cheap and resilient to inline formatting
     * that lies deeper in the run. Returns "" when no usable anchor exists.
     */
    private fun anchorFor(selected: String, fromStart: Boolean): String {
        val trimmed = selected.trim()
        if (trimmed.isEmpty()) return ""
        val cap = 24
        return if (trimmed.length <= cap) trimmed
        else if (fromStart) trimmed.substring(0, cap)
        else trimmed.substring(trimmed.length - cap)
    }

    private fun collapsedFallback(sel: TextSelection): String {
        // [T-cross-shard-copy-recycled] 用文档级表：端点分片被回收后，最后这一层
        // 兜底也不该退化成空串。
        val txt = textOf(sel.start.shard, emptyMap()) ?: return ""
        val a = sel.start.charOffset.coerceIn(0, txt.length)
        val b = sel.end.charOffset.coerceIn(0, txt.length)
        return txt.substring(minOf(a, b), maxOf(a, b))
    }

    /**
     * Every shard the current selection has seen (composed + retired + the
     * document-level text table + the caller's registry), in document order
     * where the surface provides one, else by y. Nulls sort first, so on a
     * surface without document order this is exactly the old y ordering.
     *
     * [T-u6-sel-merge] 这是**唯一**的候选枚举：跨度补全（[fallbackSpan]）与
     * [knownShardIds] 都从它取「我们知道存在哪些分片」。文档级表也一并算上——它的
     * 条目可能连 [liveTexts] 都没有（[rememberShardText] 可以单独登记文本），能定序
     * 就该照常参与跨度补全；定不了序的由 [isShardBetween] fail-closed 挡掉。
     */
    private fun knownShardsInOrder(registry: Map<TextShardId, String>): List<TextShardId> {
        val order = documentOrder
        return LinkedHashSet<TextShardId>(
            liveTexts.size + retired.size + documentTextMap.size + registry.size,
        ).apply {
            addAll(liveTexts.keys)
            addAll(retired.keys)
            addAll(documentTextMap.keys)
            addAll(registry.keys)
        }.sortedWith(compareBy<TextShardId>({ order?.invoke(it) }, { yOf(it) }))
    }
}

/**
 * Composition local exposing the active [SelectionController] to descendants.
 * Default = a no-op controller so callers can pretend it's always there;
 * shards composed without a real controller simply never participate in any
 * selection. Wrap with [ProvideSelectionController] at the ChatScreen level.
 */
val LocalMinisSelectionController = compositionLocalOf<SelectionController?> { null }

@Composable
fun ProvideSelectionController(
    controller: SelectionController,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalMinisSelectionController provides controller) {
        content()
    }
}

/**
 * Helper for shard owners: registers the shard with the ambient controller
 * (if any) for the lifetime of the call site, then unregisters on dispose.
 *
 * Pass the *currently up-to-date* [TextShard] every time — the helper holds
 * the reference under the same id, replacing on each recomposition so
 * position-in-window callbacks and TextLayoutResult always reflect the
 * latest measurement.
 */
@Composable
fun RegisterSelectionShard(shard: TextShard?) {
    val controller = LocalMinisSelectionController.current ?: return
    if (shard == null) return
    // [T-cross-shard-copy-gap-notice-i18n] 缺口提示文案走资源（values/strings.xml +
    // values-zh / values-zh-rTW）。装的是**解析后的字符串**而不是 Context：控制器
    // 的寿命长于任何一个 Activity，持有 Context 就是泄漏，而模板串本身没有这个问题。
    // 这是选区界面唯一必然经过的组合点（每个 MdText 分片都会注册），所以放在这里；
    // 控制器仍可无 Android 依赖地裸构造（JVM 单测）。
    val gapNotice = androidx.compose.ui.res.stringResource(
        com.openminis.app.R.string.chat_selection_copy_gap_notice,
    )
    SideEffect { controller.gapNoticeTemplate = gapNotice }
    DisposableEffect(controller, shard.id, shard) {
        controller.register(shard)
        onDispose { controller.unregister(shard.id) }
    }
}

/**
 * Draw the active selection's highlight rectangles inside a single shard's
 * DrawScope. Call from a `Modifier.drawBehind` on the same node whose
 * [TextLayoutResult] backed the shard registration — the highlight is drawn
 * in the shard's local coordinate space and only the portion of the
 * selection that falls inside this shard.
 */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionForShard(
    shardId: TextShardId,
    result: TextLayoutResult,
    selection: TextSelection,
    controller: SelectionController?,
    color: androidx.compose.ui.graphics.Color,
) {
    val laidOutText = result.layoutInput.text.text
    val maxOffset = laidOutText.length
    val lineCount = result.lineCount
    if (maxOffset == 0 || lineCount == 0) return

    val ordered = controller?.orderedEndpoints(selection) ?: run {
        // [T-android-selection-offscreen-order] (GH#296) When the order is
        // unknown, keep the user's own direction (start, end). This used to
        // return (end, start) for ANY cross-shard pair, inverting the range.
        val a = selection.start
        val b = selection.end
        if (a.shard == b.shard && a.charOffset > b.charOffset) b to a else a to b
    }
    val (first, last) = ordered

    // Decide the [from, to] character range within this shard.
    val isFirstHere = first.shard == shardId
    val isLastHere = last.shard == shardId
    if (!isFirstHere && !isLastHere) {
        // This shard sits BETWEEN the two endpoints — only include it if
        // the selection actually spans across us. Use shard order via the
        // controller's snapshot of registered shards' window-y positions.
        if (controller == null) {
            return
        }
        val between = controller.isShardBetween(first.shard, last.shard, shardId)
        if (!between) return
    }

    val from = if (isFirstHere) first.charOffset.coerceIn(0, maxOffset) else 0
    val to = if (isLastHere) last.charOffset.coerceIn(0, maxOffset) else maxOffset
    val lo = minOf(from, to)
    val hi = maxOf(from, to)
    if (hi <= lo) return

    val startLine = result.getLineForOffset(lo).coerceIn(0, lineCount - 1)
    val endLine = result.getLineForOffset((hi - 1).coerceAtLeast(0)).coerceIn(0, lineCount - 1)
    if (endLine < startLine) return

    for (line in startLine..endLine) {
        val lineStart = if (line == startLine) lo else result.getLineStart(line)
        val lineEndRaw = if (line == endLine) hi else result.getLineEnd(line)
        val lineEnd = lineEndRaw.coerceAtMost(maxOffset)
        if (lineEnd <= lineStart) continue
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (offset in lineStart until lineEnd) {
            val box = result.getBoundingBox(offset)
            if (box.width <= 0f) continue
            if (box.left < left) left = box.left
            if (box.right > right) right = box.right
        }
        if (!left.isFinite() || right <= left) continue
        // If this is the last visible line of the selection in this shard
        // and we DON'T contain the final endpoint, extend the highlight
        // to the line's right edge so cross-shard selections feel continuous.
        val isFinalLineHere = line == endLine && isLastHere
        val drawRight = if (!isFinalLineHere) {
            val lineRight = result.getLineRight(line)
            maxOf(right, lineRight)
        } else right
        val top = result.getLineTop(line)
        val bottom = result.getLineBottom(line)
        drawRect(
            color = color,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(drawRight - left, bottom - top),
        )
    }
}

/**
 * Order of two `mdblock:<parent>:<index>[#sub]` shards of the same message and
 * the same parent block, by (index, sub); null for any other pair.
 *
 * [T-u6-sel-merge] Upstream's cross-check, kept because [SelectionController.compareShards]
 * calls it between the document-order provider and the y fallback, and because
 * upstream's SelectionOffscreenOrderTest pins it. It uses the shared
 * [shardSubIndex] (SelectionDocumentOrder.kt), whose "absent means 0" rule is
 * what makes `#subIndex` suffixed ids comparable at all.
 */
internal fun sameBlockOrder(a: TextShardId, b: TextShardId): Int? {
    if (a.messageId != b.messageId) return null
    fun parse(id: TextShardId): Triple<String, Int, Int>? {
        val s = id.shardId
        if (!s.startsWith("mdblock:")) return null
        val hash = s.lastIndexOf('#')
        val base = if (hash >= 0) s.substring(0, hash) else s
        val colon = base.lastIndexOf(':')
        if (colon <= "mdblock:".length - 1) return null
        val index = base.substring(colon + 1).toIntOrNull() ?: return null
        return Triple(base.substring(0, colon), index, shardSubIndex(s))
    }
    val pa = parse(a) ?: return null
    val pb = parse(b) ?: return null
    if (pa.first != pb.first) return null
    return compareValuesBy(pa, pb, { it.second }, { it.third })
}

/**
 * Best-effort document-order key for a shard, extracted from the block index in
 * its shardId string. Returns null if the shardId doesn't carry a numeric block
 * index. Use case: ordering shards even when one of them isn't currently
 * registered with the SelectionController (e.g. it scrolled off-screen and got
 * disposed by LazyColumn).
 *
 * Convention from ChatScreen.kt: shard ids look like
 *   "mdblock:<parentBlockId>:<blockIndex>"   ← splitMarkdownIntoBlockTexts
 *   "text:<blockId>"                          ← single AssistantText block
 *   "legacy"                                  ← whole-message legacy path
 * The mdblock case is the only one that needs ordering today; the others
 * are single-shard so the comparison never has to disambiguate.
 *
 * [T-cross-shard-copy-recycled] 键是 Long：高 32 位 = 块序号，低 32 位 = 子序号。
 *
 * 旧实现取 `substringAfterLast(':')` 后 `toIntOrNull()`，而 `MdText` 会给同一个
 * markdown 片段里的每个文本节点加 `#<子序号>`（StreamingMarkdownMdText），于是
 * `mdblock:m:3#1` 解析成 "3#1" → null：文档序兜底对**所有**子分片永久失效，端点
 * 分片一旦被回收，跨分片复制就只剩末分片的半截前缀。现在保留子序号，既让这些 id
 * 可定序，也不会把同一片段里的兄弟子分片误判成「同在块内」。
 */
private fun shardOrderKey(id: TextShardId): Long? {
    val block = shardBlockIndex(id.shardId) ?: return null
    val sub = subIndexOrNull(id.shardId) ?: 0
    return (block.toLong() shl 32) or (sub.toLong() and SHARD_SUB_MASK)
}

/**
 * [T-cross-shard-copy-recycled] 剥离 `#<数字>` 子序号后缀后的根 shardId
 * （`MdText` 的每个文本节点都会带该后缀）。本身含 '#' 但不是 `#<数字>` 形态的
 * shardId 原样返回，避免误伤。
 */
private fun shardIdRoot(shardId: String): String {
    val hash = shardId.lastIndexOf('#')
    if (hash <= 0) return shardId
    return if (shardId.substring(hash + 1).all { it.isDigit() }) {
        shardId.substring(0, hash)
    } else {
        shardId
    }
}

/**
 * [T-cross-shard-copy-recycled] `#<子序号>` 的数字值；没有该后缀时 null。
 *
 * [T-u6-sel-merge] 名字区别于上游的 [shardSubIndex]（SelectionDocumentOrder.kt，
 * internal，缺后缀返回 0）：这里要的是「有没有子序号」这个三态，键序与跨度完整性
 * 判定都依赖它；两者语义不同，不能共用一个名字（同名同签名会直接编译失败）。
 */
private fun subIndexOrNull(shardId: String): Int? {
    val hash = shardId.lastIndexOf('#')
    if (hash <= 0) return null
    return shardId.substring(hash + 1).toIntOrNull()
}

/** [T-cross-shard-copy-recycled] 根 shardId 末尾的数字块序号；不是数字时 null。 */
private fun shardBlockIndex(shardId: String): Int? {
    val root = shardIdRoot(shardId)
    val lastColon = root.lastIndexOf(':')
    if (lastColon < 0) return null
    return root.substring(lastColon + 1).toIntOrNull()
}

/**
 * [T-cross-shard-copy-recycled] 文档级 plainText 表的条目上限。表按「用户滚过的
 * 分片数」增长，超限后按最久未读取淘汰（见 SelectionController.documentTextMap）。
 */
private const val MAX_DOCUMENT_SHARDS = 4_096

/**
 * [T-cross-shard-copy-docmap-char-budget] 文档级 plainText 表的**字符预算**。
 *
 * 只用条数上限不封顶内存：条目值是分片全文，单个巨段落分片可达数十 KB，
 * 4096 × 几十 KB 最坏把上百 MB 的分片文本攒在常驻内存里（长会话滚到底即可触发）。
 * 512K 字符 ≈ 1 MB（UTF-16），足够装下「用户刚滚过去、复制正要取用」的那一段历史
 * （约 1000 个普通段落分片，或几十个巨型分片），而与 blocksLru / inlineLru 的
 * 2M 字符预算同一量级。
 */
private const val MAX_DOCUMENT_CHARS = 512_000

/** [T-cross-shard-copy-gap-notice-i18n] 资源串与兜底串里的计数占位符，必须同形。 */
private const val GAP_NOTICE_COUNT_PLACEHOLDER = "%1\$d"

/**
 * [T-cross-shard-copy-anchor-offset-match] 锚点消歧时逐字符比对的**单侧**长度上限：
 * 分片文本可能是巨段落，不封顶会让「找对锚点出现位置」这件事本身变成 O(出现次数 ×
 * 文本长度) —— 复制跑在主线程上，上界必须是常数。
 */
private const val ANCHOR_CONTEXT_CAP = 64

/**
 * [T-cross-shard-copy-gap-notice-i18n] 拿不到资源时的缺口提示（与
 * `values-zh` 的 `chat_selection_copy_gap_notice` 逐字一致）。运行路径由
 * [RegisterSelectionShard] 注入资源串，这里只服务没有 Context 的调用方（JVM 单测）。
 */
internal const val DEFAULT_GAP_NOTICE_TEMPLATE =
    "[…… 选区中有 %1\$d 个分片的文本已被回收，复制到的内容不完整 ……]"

/** [T-cross-shard-copy-recycled] [shardOrderKey] 低 32 位的子序号掩码。 */
private const val SHARD_SUB_MASK = 0xFFFF_FFFFL

/**
 * Tells whether shard [middle] lies between shards [a] and [b] (either order).
 * Used by the highlight code to decide whether a shard with no selection
 * endpoint inside it should still be filled.
 *
 * [T-android-selection-offscreen-order] (GH#296) An endpoint that scrolled
 * off-screen unregisters, and everything ordered by on-screen y loses its
 * footing: every paragraph between the endpoints lost its highlight mid-drag.
 */
internal fun SelectionController.isShardBetween(
    a: TextShardId,
    b: TextShardId,
    middle: TextShardId,
): Boolean {
    // First try the document-order index baked into the shardId string —
    // mdblock:<parent>:<index> / text:<blockId>. The numeric index is a
    // stable document position, immune to a/b having scrolled off-screen
    // (which would leave their TextShard entries unregistered and the
    // y-comparison below unable to position them). The "messageId" axis
    // is compared first so cross-message selections still slot correctly:
    // a shard from message M1 only lies "between" two endpoints if they
    // straddle M1 in the registered-shards' visual order.
    val orderA = shardOrderKey(a)
    val orderB = shardOrderKey(b)
    val orderM = shardOrderKey(middle)
    if (orderA != null && orderB != null && orderM != null &&
        a.messageId == b.messageId && middle.messageId == a.messageId
    ) {
        val lo = minOf(orderA, orderB)
        val hi = maxOf(orderA, orderB)
        return orderM in lo..hi
    }
    // [T-u6-sel-merge] Upstream's route for everything the numeric key cannot
    // place — a selection that spans messages, or ids without a block index:
    // [compareShards] asks the surface's [documentOrder] first (DocOrder =
    // flattened-row index + sub-index, so it orders across blocks AND
    // messages), then [sameBlockOrder], then the last known y of a shard that
    // has since been retired.
    //
    // [T-cross-shard-copy-y-fallback-position] Fail-closed is preserved: when
    // compareShards cannot decide (an endpoint never registered, no provider),
    // it returns null and this returns false — "cannot position it" is NOT
    // "it lies between", exactly as before. Treating an unpositionable shard
    // as between is what dragged unrelated content into a copy, and reading a
    // detached shard's Offset.Zero as y=0 did the same.
    val ab = compareShards(a, b) ?: return false
    val (lo, hi) = if (ab <= 0) a to b else b to a
    val loToMiddle = compareShards(lo, middle) ?: return false
    val middleToHi = compareShards(middle, hi) ?: return false
    return loToMiddle <= 0 && middleToHi <= 0
}

/**
 * Convenience for callers that already have a [LayoutCoordinates] from an
 * `onGloballyPositioned` modifier — wraps it into the `positionInWindow` and
 * `sizePx` closures the controller expects.
 */
fun buildTextShard(
    id: TextShardId,
    plainText: String,
    layoutResult: TextLayoutResult,
    coordinatesProvider: () -> LayoutCoordinates?,
    rawMarkdown: String? = null,
    renderedToRawOffset: ((Int) -> Int)? = null,
    isAtomicUnit: Boolean = false,
): TextShard = TextShard(
    id = id,
    plainText = plainText,
    textLayoutResult = layoutResult,
    isAtomicUnit = isAtomicUnit,
    positionInWindow = {
        val c = coordinatesProvider()
        if (c != null && c.isAttached) c.positionInWindow() else Offset.Zero
    },
    sizePx = {
        val c = coordinatesProvider()
        if (c != null && c.isAttached) {
            androidx.compose.ui.unit.IntSize(c.size.width, c.size.height)
        } else {
            androidx.compose.ui.unit.IntSize.Zero
        }
    },
    renderedToRawOffset = renderedToRawOffset,
    rawMarkdown = rawMarkdown,
)

