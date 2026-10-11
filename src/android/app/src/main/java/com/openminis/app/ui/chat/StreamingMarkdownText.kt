package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.text.BoundedText
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import android.widget.Toast
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle

// ─── MinisTextKit hook ────────────────────────────────────────────────────────
// Each markdown fragment renders inside a [MarkdownBlock] / [RenderBlock]
// scope that provides a [TextShardId] via [LocalShardId]. [MdText] reads it,
// registers a [TextShard] with the ambient [SelectionController] (if any),
// and draws the selection highlight inside its existing drawBehind. The
// indirection lets MdText stay markdown-agnostic — paragraph, heading,
// blockquote, table cell, etc. all participate uniformly without each
// caller having to plumb a per-text-node identifier.
val LocalShardId = compositionLocalOf<TextShardId?> { null }

/**
 * [T-android-markdown-longtext-selection-broken] Per-MdText sub-id allocator.
 *
 * A single markdown fragment ([MarkdownBlock] / [StreamingMarkdownText]) is
 * parsed into many [MdBlock]s and each is rendered by a [RenderBlock] that may
 * itself emit several [MdText]s (every list item, table cell, blockquote line,
 * heading, paragraph…). ALL of them read the same ambient [LocalShardId], so
 * before this fix they registered [TextShard]s under one identical
 * [TextShardId] key — and `SelectionController.shards` is a map keyed by id, so
 * each registration overwrote the previous one. Only the LAST MdText in a
 * fragment survived in the registry, so a long (multi-paragraph) reply was
 * un-selectable except for its final text node; short single-paragraph replies
 * happened to have exactly one MdText and worked, which is why the regression
 * looked length-dependent.
 *
 * This allocator hands each MdText a stable, unique index within its fragment.
 * `remember { allocator.next() }` runs once per MdText composition slot, so the
 * index is assigned in first-composition order and survives recomposition
 * (Compose re-runs `remember`s in the same slot order). The index is appended
 * to the base shardId so every text node gets a distinct [TextShardId] and all
 * register independently.
 */
internal class ShardSubIndexAllocator {
    private var counter = 0
    fun next(): Int = counter++
}

internal val LocalShardSubIndexAllocator = compositionLocalOf<ShardSubIndexAllocator?> { null }

/**
 * [T-android-markdown-longtext-selection-broken] Provide a per-fragment
 * [ShardSubIndexAllocator] so every [MdText] composed under [content] gets a
 * distinct shard sub-index. The allocator is `remember`ed once per fragment
 * composable instance (NOT keyed on the block list): its counter is monotonic,
 * so when blocks stream in / change, newly-added MdText slots draw fresh
 * indices while existing slots keep theirs — indices never collide. Each
 * MdText reads it via [LocalShardSubIndexAllocator].
 */
@Composable
internal fun ShardSubIndexScope(content: @Composable () -> Unit) {
    val allocator = remember { ShardSubIndexAllocator() }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalShardSubIndexAllocator provides allocator,
        content = content,
    )
}

/** Selection-highlight fill color. Resolved per-composition for theme support. */
@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun currentSelectionHighlightColor(): Color {
    // Match Android's default textSelectHandle tint at ~30% alpha so it
    // visually overlays without obscuring the glyphs underneath.
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    return accent.copy(alpha = 0.28f)
}

// ─── Markdown color palette — resolved per-composition via currentMdColors() ──
internal data class MdColors(
    val text: Color,
    val codeText: Color,
    val codeBg: Color,
    val inlineCodeText: Color,
    val inlineCodeBg: Color,
    val link: Color,
    val blockquote: Color,
    val divider: Color,
    val tableBorder: Color,
    val tableHeaderBg: Color,
)

@Composable
@androidx.compose.runtime.ReadOnlyComposable
internal fun currentMdColors(): MdColors {
    val c = com.openminis.app.ui.theme.LocalChatPalette.current
    return MdColors(
        text = c.primaryText,
        codeText = c.codeBlockText,
        codeBg = c.codeBlockBg,
        inlineCodeText = c.inlineCodeText,
        inlineCodeBg = c.inlineCodeBg,
        link = c.link,
        blockquote = c.secondaryText,
        divider = c.separator,
        tableBorder = c.tableBorder,
        tableHeaderBg = c.secondaryBg,
    )
}

internal val MdCodeLangColor = Color.White.copy(alpha = 0.5f)

val LocalMarkdownFontScale = compositionLocalOf { 1f }

/** Handler invoked when a markdown URL span is tapped. Provided by ChatScreen. */
val LocalMarkdownUrlClickHandler = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * [T-android-usage-capsule-blank-tap] Handler invoked when the user taps a
 * BLANK part of an assistant text block — past the end of a line, in the
 * ragged right margin, or below the last line. Receives the message id.
 *
 * This is how the usage capsule is revealed, mirroring iOS `onTapBlank`
 * (SelectableMarkdownView.swift:5636): iOS hit-tests the tap against the
 * glyph rects and treats a miss as "blank". Gating on a separate tap strip
 * instead — as this did first — makes the capsule's own 32dp row the ONLY
 * target, which is not discoverable: the user is told to tap the reply and
 * nothing happens anywhere they naturally tap.
 *
 * Deliberately fires only on a genuine miss, so it can never swallow a URL
 * tap, an inline-code copy, or the start of a text selection.
 */
val LocalMarkdownBlankTapHandler = compositionLocalOf<((String) -> Unit)?> { null }

/**
 * [T-android-markdown-image-gallery-cross-message] Handler invoked when a
 * markdown image (`![alt](src)`) inside an assistant message is tapped, with
 * the parent message id so the host can collect every sibling image across
 * the conversation and open a paged gallery (mirrors iOS
 * `handleMarkdownImageTap` in AIChatView.swift:2082).
 *
 * Distinct from [LocalMarkdownUrlClickHandler] so the existing url-only
 * routing keeps working unchanged. When null, the image renderer falls back
 * to [LocalMarkdownUrlClickHandler] (which routes a single-item open).
 *
 * The id corresponds to [TextShardId.messageId] supplied via [LocalShardId]
 * — every assistant text block already provides one, so the renderer reads
 * the id from the ambient shard rather than threading a separate prop.
 */
val LocalMarkdownImageTapHandler =
    compositionLocalOf<((messageId: String, url: String) -> Unit)?> { null }

/**
 * Session id that owns the currently-rendering markdown. Used by
 * `resolveMdMediaFile` to prefer `PRootKernel.resolveSessionHostPath` — the
 * session-scoped resolver — over the global `bindMounts` map, which is
 * last-writer-wins across sessions. Null in contexts that don't know the
 * owning session (e.g. standalone previews).
 */
val LocalMarkdownSessionId = compositionLocalOf<String?> { null }

private val BaseFontSizeDefault = 16.sp
private val BaseLineHeightDefault = 24.sp

internal val BaseFontSize: TextUnit
    @Composable get() = BaseFontSizeDefault * LocalMarkdownFontScale.current

internal val BaseLineHeight: TextUnit
    @Composable get() = BaseLineHeightDefault * LocalMarkdownFontScale.current

internal val InlineCodeCornerRadius = 6.dp

/**
 * Streaming-friendly markdown renderer.
 *
 * Strategy:
 * - Parse markdown into a list of Block objects
 * - Each block is an independent composable — Compose's structural diff only
 *   recomposes blocks that actually changed
 * - The LAST block is the only one that changes during streaming (text appends to it)
 * - Completed blocks above are structurally stable → Compose skips them
 *
 * Update cadence: while [isStreaming] is true, content updates are coalesced
 * to at most one re-parse every [STREAMING_THROTTLE_MS] (~120 ms). Pixel 4a
 * traces showed every TextDelta (~5 ms cadence) was triggering a full
 * `parseMarkdownBlocks` over the entire accumulating string + a recompose of
 * every RenderBlock — a 5-row markdown table plus a few tool calls was enough
 * to ANR the main thread with 22 MB GC every 2 s. Throttling the *display*
 * content (not the underlying StateFlow) keeps the conversation visually
 * live (3-4 fps of growth is plenty for reading) while leaving 90 % of the
 * frame budget free.
 *
 * When the stream finishes ([isStreaming] flips to false), the final value
 * is published immediately so the user never sees a truncated last frame.
 */
// Adaptive streaming throttle, mirrors iOS CollectionViewMessageListV3
// `flushStreamingLayout` (100 ms when auto-scrolling, 3 s when away). On
// Android we don't have direct access to the chat-level scroll state from
// here, so substitute "doc length" as a proxy: long documents already cost
// more per parse pass, so amortize them by sampling less often. Crashes
// observed on Pixel 6 traced to ICU `RegexPattern::matcher` allocations
// piling up under Scudo (OOM at ~140s of streaming) — slowing parses on
// large bodies cuts native allocation pressure dramatically.
//
// [T-android-stream-flush-dualpath] Time-throttle tiers ported verbatim from
// iOS AIChatViewModel+SSEStream (the `throttle` ladder): the time path is one
// half of the dual-path flush — the other half is the newline fast-path below.
// Tiers scale with total length to hold the Pixel 4a ANR / Pixel 6 Scudo-OOM
// line on dense streams while keeping short replies responsive.
//   < 500  : 200ms   < 2000 : 300ms   < 32K : 500ms
//   < 64K  : 1000ms  < 128K : 1500ms  else  : 2000ms
internal fun streamingThrottleFor(content: String): Long = when {
    content.length < 500 -> 200L
    content.length < 2_000 -> 300L
    content.length < 32_000 -> 500L
    content.length < 64_000 -> 1_000L
    content.length < 128_000 -> 1_500L
    else -> 2_000L
}


@Composable
fun StreamingMarkdownText(
    content: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /** MinisTextKit shard id (see [MarkdownBlock]). */
    shardId: TextShardId? = null,
) {
    val shown = com.openminis.app.agent.DisplayRegex.apply(
        androidx.compose.ui.platform.LocalContext.current,
        content,
        com.openminis.app.agent.DisplayRegex.Scope.ASSISTANT,
    )
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            StreamingMarkdownTextBody(shown, isStreaming, modifier)
        }
        return
    }
    StreamingMarkdownTextBody(shown, isStreaming, modifier)
}

/**
 * T285-md: full-document markdown viewer for FilePreviewScreen and any
 * other "open a `.md` file end-to-end" surface. Differs from
 * [StreamingMarkdownText] in two important ways:
 *
 *  1. Renders blocks via [LazyColumn] instead of [Column]. A 200-block
 *     document only composes the on-screen blocks on first frame, so
 *     `parseInline`/`collectInlineMathLatex` (still main-thread per
 *     RenderBlock) costs scale with viewport height, not document
 *     length. Critical for the chat-tap → preview transition: pre-T285-md
 *     a multi-KB markdown ran ~150-300ms of inline scanning across all
 *     blocks during the same frame the navigation animation started,
 *     stuttering the slide-in. (StreamingMarkdownText still uses Column
 *     because chat-side messages live inside ChatScreen's outer
 *     LazyColumn — putting a LazyColumn-in-LazyColumn there would hit
 *     the "infinite vertical constraint" runtime error.)
 *
 *  2. No streaming throttle / snapshotFlow plumbing — the file is
 *     loaded once and the content never mutates after publication, so
 *     the live-tail logic in [StreamingMarkdownText] would just be
 *     overhead.
 *
 * Pass the outer scroll [Modifier] (height/padding) to this composable;
 * do NOT wrap the call site in a `verticalScroll` — the LazyColumn
 * provides the scroll itself.
 */
@Composable
fun MarkdownDocument(
    content: String,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    // [T-android-inline-parse-offmain] Theme snapshot for off-main prewarm —
    // the doc viewer benefits the same way: per-block inline scans become
    // cache hits as blocks scroll into view.
    val mdColors = currentMdColors()
    var blocks by remember(content) { mutableStateOf<List<MdBlock>>(emptyList()) }
    LaunchedEffect(content) {
        val computed = withContext(Dispatchers.Default) {
            // [T-android-md-parse-gate] Static document: no incremental reuse
            // to be had, but it must still queue behind the same gate so
            // opening a large .md while sub-agents stream cannot stack another
            // full-document parse on top of them.
            markdownParseGate.withPermit { parseMarkdownBlocks(content) }.also {
                MarkdownParseCaches.prewarm(it, mdColors)
            }
        }
        coroutineContext.ensureActive()
        blocks = computed
    }
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        // Composite key: index disambiguates blocks with identical raw
        // bodies (multiple `---` HR lines, repeated empty paragraphs, etc.
        // would otherwise crash LazyColumn with "Key was already used"),
        // while raw still helps item reuse when the list is rebuilt with
        // the same content at the same position.
        itemsIndexed(blocks, key = { idx, b -> "$idx:${b.raw}" }) { _, block ->
            RenderBlock(block)
        }
    }
}

// ─── Block-level splitting (Pattern A: stable scroll while streaming) ─────────
//
// Earlier the entire streaming markdown was rendered inside a single
// LazyColumn item. When that item's height grew mid-stream, LazyList's
// per-item anchor couldn't help — the user's scroll position drifted as the
// internal Column reflowed. Splitting the message into one LazyColumn item
// per markdown block shifts the anchor granularity down: completed blocks
// (anything before the trailing fence/blank-line boundary) become frozen
// items whose visual position is preserved by LazyList; only the trailing
// "live" block can change height.
//
// `splitMarkdownIntoBlockTexts` returns ordered raw text fragments. The
// boundary rule is:
//   - blank line OUTSIDE a fenced code block → split (paragraph end)
//   - fenced code block start/end → its own fragment
// Fence-internal blank lines never split. Tables and HR-only lines stay
// attached to their preceding/following fragment because the parser
// detects them at parse time anyway.

/**
 * Split a streaming markdown buffer into ordered raw-text fragments at
 * stable boundaries. Each fragment is suitable as the input to a
 * standalone [MarkdownBlock] composable. Concatenating the returned list
 * with "\n" reconstructs the input exactly.
 */
fun splitMarkdownIntoBlockTexts(content: String): List<String> {
    if (content.isEmpty()) return emptyList()
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inFence = false
    val lines = content.lines()
    fun flush() {
        if (cur.isNotEmpty()) {
            // Trim trailing empty line we used as boundary, but keep
            // intentional internal newlines.
            out.add(cur.toString().trimEnd('\n'))
            cur.clear()
        }
    }
    for (line in lines) {
        val trimmed = line.trimStart()
        val isFence = trimmed.startsWith("```")
        if (isFence) {
            // A fence line both closes the previous fragment (when we're
            // not inside a fence) and opens/closes the fence fragment.
            if (!inFence) {
                flush()
                cur.append(line).append('\n')
                inFence = true
            } else {
                cur.append(line).append('\n')
                inFence = false
                flush()
            }
            continue
        }
        if (inFence) {
            cur.append(line).append('\n')
            continue
        }
        if (line.isBlank()) {
            // Boundary: paragraph end. Drop the blank line itself; it
            // signals the split.
            flush()
            continue
        }
        cur.append(line).append('\n')
    }
    flush()
    return out
}

/**
 * [T-android-defensive-fragment-merge] A fenced code block fragment is one
 * whose first non-blank line opens a ``` fence. Such fragments must stay
 * standalone (own LazyColumn row) for correct code rendering + horizontal
 * scroll, so coalescing never merges across them.
 */
private fun isFenceFragment(fragment: String): Boolean {
    val firstLine = fragment.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    return firstLine.trimStart().startsWith("```")
}

/**
 * [T-android-defensive-fragment-merge] Coalesce the per-paragraph fragments
 * produced by [splitMarkdownIntoBlockTexts] into fewer, larger fragments so
 * a long frozen assistant message becomes a handful of LazyColumn rows
 * instead of dozens.
 *
 * Why: each fragment is its own LazyColumn item carrying its own
 * BoundsTrackedBlock + MarkdownBlock + per-item Compose state. A dense
 * assistant reply (e.g. a 50-item list with blank lines) fans out into ~50
 * rows; a long session reaches several thousand rows, which on low-memory
 * devices contributes to a GC storm on cold-open full-build. Re-joining
 * adjacent plain-text fragments with their original blank-line separator
 * (`\n\n`) keeps the rendered markdown identical — MarkdownBlock re-parses
 * the joined text the same way it would parse them separately — while
 * cutting the row count ~8x.
 *
 * Rules:
 *   - Code-fence fragments are NEVER merged (kept standalone for syntax
 *     highlight + horizontal scroll). They flush the current accumulator
 *     and emit on their own.
 *   - Plain fragments accumulate until adding the next would exceed
 *     [maxChars]; then the accumulator flushes and a new one starts. This
 *     caps any single merged row's height so the streaming/scroll anchor
 *     granularity stays reasonable.
 *   - Joining uses `\n\n` so paragraph boundaries survive the round-trip.
 *
 * Callers should only apply this to FROZEN (non-streaming) messages — the
 * live streaming tail keeps fine-grained fragments so only the trailing
 * paragraph re-parses per token (Pattern A jank optimization).
 */
fun coalesceMarkdownFragments(fragments: List<String>, maxChars: Int = 2000): List<String> {
    if (fragments.size <= 1) return fragments
    val out = ArrayList<String>(fragments.size)
    val acc = StringBuilder()
    fun flush() {
        if (acc.isNotEmpty()) {
            out.add(acc.toString())
            acc.setLength(0)
        }
    }
    for (frag in fragments) {
        if (isFenceFragment(frag)) {
            flush()
            out.add(frag)
            continue
        }
        // Would appending this fragment overflow the budget? Flush first,
        // unless the accumulator is empty (a single oversized paragraph
        // still gets its own row rather than being dropped).
        if (acc.isNotEmpty() && acc.length + 2 + frag.length > maxChars) {
            flush()
        }
        if (acc.isNotEmpty()) acc.append("\n\n")
        acc.append(frag)
    }
    flush()
    return out
}

/**
 * Render a single markdown fragment (one or a few related blocks) inside
 * its own composable. Designed to be used as the body of an independent
 * LazyColumn item — each fragment is one item, so its height changes
 * cannot disturb the scroll position of any other fragment.
 *
 * `isStreaming` controls async re-parse: when false (frozen completed
 * fragment), the parse runs once on first composition and is never
 * recomputed. When true (the trailing live fragment), the parse is
 * re-run on every content tick, mirroring the original
 * StreamingMarkdownText behavior.
 */
@Composable
fun MarkdownBlock(
    rawText: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    /**
     * MinisTextKit shard id — when supplied, every MdText composed beneath
     * this fragment will register with the ambient [SelectionController].
     * The id should be stable across recompositions so the controller's
     * registry doesn't churn (e.g. "msg:abc:block:7"). Null = participate
     * in no selection (legacy behavior).
     */
    shardId: TextShardId? = null,
) {
    if (shardId != null) {
        androidx.compose.runtime.CompositionLocalProvider(LocalShardId provides shardId) {
            // [T-android-markdown-longtext-selection-broken] Disambiguate the
            // several MdTexts a multi-block fragment renders under this one
            // shardId so each registers its own shard.
            ShardSubIndexScope {
                MarkdownBlockBody(rawText, isStreaming, modifier)
            }
        }
        return
    }
    MarkdownBlockBody(rawText, isStreaming, modifier)
}

/**
 * [T-android-live-block-degrade] A LIVE fragment larger than this renders as a
 * bounded plain-text tail until it freezes. 8KB of markdown in one unsplit
 * block is far beyond normal prose paragraphs — only giant tables/fences get
 * here, and those were the per-tick full-re-parse ANR load.
 */
internal const val LIVE_FRAGMENT_DEGRADE_CHARS = 8_000
internal const val LIVE_FRAGMENT_TAIL_CHARS = 3_000

/**
 * [T-android-coldload-offmain-parse] A FROZEN fragment above this size whose
 * block parse would be a cache MISS parses off-main (with a plain-text
 * preview in the meantime) instead of synchronously in composition. Below
 * it the parse is sub-ms and the placeholder swap would flicker for nothing.
 */
internal const val COLD_PARSE_OFFMAIN_THRESHOLD_CHARS = 2_000
internal const val COLD_PARSE_PREVIEW_CHARS = 4_000

/**
 * [T-android-coldload-offmain-parse] Composition-snapshot prewarmer for the
 * chat flatten pipeline: returns a thread-safe lambda that block-parses each
 * raw fragment AND prewarms the inline/math caches with the palette captured
 * here. Lets ChatScreen (which cannot see the file-private MdBlock/MdColors
 * types) warm the exact keys RenderBlock will look up, off-main, before the
 * viewport rows first compose.
 */
/**
 * [T-android-md-parse-gate] Cold-scroll prewarmer.
 *
 * This is the path the Pixel 6 tombstone died on: ChatScreen launches it on
 * `Dispatchers.Default` once per flatten tick per ChatViewModel, and each call
 * runs `MarkdownParseCaches.blocks()` → `parseMarkdownBlocksBlocking` →
 * `runBlocking { parseMarkdownBlocks(...) }` over up to 16 rows × 96 KB. With
 * several sub-agent sessions flattening at once these stack up unbounded, one
 * full ICU-matcher-allocating parse per core, until `operator new` throws
 * `std::bad_alloc`.
 *
 * Made `suspend` so it can take the shared [markdownParseGate]: prewarming is
 * pure look-ahead for scrolling, so it is exactly the work that should yield
 * to a visible stream's parse rather than race it.
 */
@Composable
internal fun rememberMarkdownPrewarmer(): suspend (List<String>) -> Unit {
    val mdColors = currentMdColors()
    return remember(mdColors) {
        val fn: suspend (List<String>) -> Unit = { raws ->
            for (raw in raws) {
                markdownParseGate.withPermit {
                    MarkdownParseCaches.prewarm(MarkdownParseCaches.blocks(raw), mdColors)
                }
                // Cooperative: a cancelled scroll must not keep prewarming.
                coroutineContext.ensureActive()
            }
        }
        fn
    }
}

/**
 * Synchronous variant of [parseMarkdownBlocks] used for frozen blocks
 * where we don't need cooperative cancellation. Implemented by reusing
 * the suspend version under a runBlocking on the calling thread — frozen
 * blocks parse once and the input is small, so this is fine.
 */
// [T-u6-smt-merge] Upstream's indented-fence fix, adopted verbatim. Its call
// site lives in the parser this port moved to StreamingMarkdownParse.kt (see
// the leftover note for U6) — the function itself is unit-tested directly by
// FencedCodeIndentTest, so it belongs in this file either way.
/**
 * [T-android-codeblock-fence-indent] A fenced code block's content, with the
 * fence's own indentation removed from every line (CommonMark: a fence indented
 * N spaces strips up to N spaces from each content line).
 *
 * The parser recognises an indented fence (it tests the trimmed line) but used
 * to keep the content lines verbatim. Models put code blocks inside list items
 * all the time —
 *
 *     - Install the dependencies:
 *       ```bash
 *       npm install
 *       ```
 *
 * — so the copy button handed out "  npm install": two leading spaces under a
 * bullet, three under "1.". iOS parses with cmark and strips them.
 *
 * Spaces only, and never more than the fence's indent: a line indented
 * further keeps its extra indentation (it is part of the code), and a line
 * indented less loses only what it has.
 */
internal fun fencedCodeContent(fenceLine: String, contentLines: List<String>): String {
    val indent = fenceLine.length - fenceLine.trimStart(' ').length
    if (indent == 0) return contentLines.joinToString("\n")
    return contentLines.joinToString("\n") { line ->
        var n = 0
        while (n < indent && n < line.length && line[n] == ' ') n++
        line.substring(n)
    }
}

internal fun parseMarkdownBlocksBlocking(content: String): List<MdBlock> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content) }

// ─── [T-u6-smt-merge] Incremental block parse, adopted from upstream 1.14 ─────
//
// Upstream 1.14's answer to the O(n²) re-parse of a growing stream: keep the
// blocks of the longest already-SEALED prefix and re-parse only the tail. The
// port's own tree never had this (its incremental work covers the INLINE
// layer — see MarkdownParseCaches.inlineIncremental), so the whole mechanism
// comes across: the gate, the state, the boundary test and the two test hooks
// (MarkdownIncrementalParseTest calls them directly).
//
// It calls the tree's existing `parseMarkdownBlocks` (StreamingMarkdownParse.kt)
// rather than upstream's copy of it, so the block parser stays single-sourced.
// Wiring it into StreamingMarkdownTextBody.kt is the remaining follow-up — see
// the U6 leftover note.

/**
 * [T-android-md-parse-gate] One permit for the whole process, so N concurrent
 * sub-agent streams cannot multiply peak native ICU allocation.
 *
 * A caller that is cancelled while waiting never runs its parse at all, which
 * is exactly the desired behaviour for a superseded chunk.
 */
private val markdownParseGate = Semaphore(1)

/**
 * [T-android-md-parse-incremental] Longest already-parsed prefix of a growing
 * stream, so an append-only update re-parses only the tail.
 *
 * Streaming content is append-only, and markdown block structure is
 * prefix-stable up to the last blank-line boundary: everything before the
 * final sealed boundary can no longer change no matter what arrives next (the
 * parser never looks backwards past a blank line — each `when` branch consumes
 * forward from its opening line). So we cache the blocks for that stable
 * prefix and, on the next pass, parse only what follows it.
 *
 * The saving is what makes long streams survivable: without it, a 60 KB reply
 * is re-parsed from byte 0 on every throttle tick — O(n²) native matcher
 * allocations over the life of the stream. With it, each tick parses only the
 * newly-arrived tail.
 */
private class IncrementalParseState {
    var prefixLength: Int = 0
    var prefixBlocks: List<MdBlock> = emptyList()

    /**
     * Cheap fingerprint of the frozen prefix, to detect that growth really was
     * append-only (a retry / edit / shorter re-render must invalidate).
     *
     * Deliberately NOT the prefix text itself: holding a copy would retain a
     * second full-size String per live composable and make the check O(n) on
     * every tick — reintroducing exactly the memory and CPU pressure this
     * change exists to remove. A length + hash of the boundary region is
     * O(1)-ish and false-positives only if an edit preserved both.
     */
    var prefixHash: Int = 0
}

/** Fingerprint over the tail of [content] up to [end] (bounded work). */
private fun prefixFingerprint(content: String, end: Int): Int {
    if (end <= 0) return 0
    val from = (end - 512).coerceAtLeast(0)
    var h = end
    for (i in from until end) h = h * 31 + content[i].code
    return h
}

/**
 * [T-android-md-parse-incremental] Test hook: the `raw` text of each block a
 * parse produces. Lets a unit test assert that feeding a stream in chunks
 * through [parseMarkdownIncremental] yields exactly the same block split as
 * one full parse of the final text — the invariant the whole optimization
 * rests on.
 */
internal fun debugParseBlockRaws(content: String): List<String> =
    kotlinx.coroutines.runBlocking { parseMarkdownBlocks(content).map { it.raw } }

/** Test hook: same, but replayed incrementally as the text grows. */
internal fun debugParseIncrementalRaws(chunks: List<String>): List<String> =
    kotlinx.coroutines.runBlocking {
        val state = IncrementalParseState()
        var last: List<MdBlock> = emptyList()
        for (c in chunks) last = parseMarkdownIncremental(c, state)
        last.map { it.raw }
    }

/**
 * Offset of the end of the last "sealed" block boundary — the final blank line
 * that is followed by more content. Returns [searchFrom] when nothing is
 * sealed yet.
 *
 * Deliberately conservative: a fenced code block can contain blank lines, so
 * an odd number of ``` fences before the candidate boundary means the boundary
 * is inside a fence and is not safe to freeze. Same for `$$` display math.
 */
internal fun stableParsePrefixEnd(content: String, searchFrom: Int): Int {
    var idx = content.lastIndexOf("\n\n")
    while (idx >= searchFrom) {
        val candidate = idx + 2
        if (isSealedBoundary(content, candidate)) return candidate
        if (idx == 0) break
        idx = content.lastIndexOf("\n\n", idx - 1)
    }
    return searchFrom
}

/**
 * True when [end] is not inside an open ``` fence, `$$` / `\[` display-math
 * span, or a list that can still continue past the blank line.
 * Counts delimiters that START a line, matching how [parseMarkdownBlocks]
 * recognises them (it tests `trimmed.startsWith`).
 *
 * [T-android-md-sealed-boundary-lists] A blank line is only a safe freeze
 * point if no block construct in [parseMarkdownBlocks] crosses it. Fences and
 * `$$` were covered; two more constructs DO cross a blank line and were not:
 *  - `\[ … \]` display math: its loop scans forward over blank lines to the
 *    first line containing `\]`, so a blank line inside it is not a boundary.
 *  - bullet / ordered lists: both list loops `continue` over blank lines and
 *    keep consuming the next list item or indented line. A loose list or an
 *    item with an indented continuation paragraph is ONE block in a full
 *    parse; freezing at its blank line split it into several while streaming,
 *    and the block re-laid out at the streaming -> frozen edge.
 * Refusing costs only re-parse work; accepting wrongly changes the render.
 */
internal fun isSealedBoundary(content: String, end: Int): Boolean {
    var fences = 0
    var maths = 0
    var bracketMathOpen = false
    // Last non-blank line before [end] — a list only ever ends on a list item
    // or an indented line, so this is what decides whether one is still open.
    var lastNonBlankStart = -1
    var lastNonBlankEnd = -1
    var lineStart = 0
    while (lineStart < end) {
        var lineEnd = content.indexOf('\n', lineStart)
        if (lineEnd < 0 || lineEnd > end) lineEnd = end
        var p = lineStart
        while (p < lineEnd && (content[p] == ' ' || content[p] == '\t')) p++
        if (bracketMathOpen) {
            // Mirrors the parser's `\[` loop: the first line containing `\]`
            // anywhere closes the span.
            if (content.indexOf("\\]", lineStart).let { it in lineStart until lineEnd }) {
                bracketMathOpen = false
            }
        } else if (content.startsWith("\\[", p)) {
            bracketMathOpen = content.indexOf("\\]", p + 2).let { it !in 0 until lineEnd }
        }
        if (content.startsWith("```", p)) fences++
        else if (content.startsWith("$$", p)) {
            // A single-line `$$ … $$` opens and closes on the same line.
            maths += if (content.indexOf("$$", p + 2).let { it in 0 until lineEnd }) 0 else 1
        }
        if (p < lineEnd && content.substring(p, lineEnd).isNotBlank()) {
            lastNonBlankStart = lineStart
            lastNonBlankEnd = lineEnd
        }
        lineStart = lineEnd + 1
    }
    if (fences % 2 != 0 || maths % 2 != 0 || bracketMathOpen) return false
    if (lastNonBlankStart < 0) return true
    val prev = content.substring(lastNonBlankStart, lastNonBlankEnd)
    if (!isListContinuationLine(prev)) return true
    // A list is (possibly) open. The blank line is a boundary only if the
    // next non-blank line is COMPLETE and would break the list — neither a
    // list item nor indented. An unterminated line may still grow into one
    // ("-" -> "- beta", "" -> "   more"), and no next line at all means the
    // stream has not decided yet.
    var q = end
    while (q < content.length) {
        val nl = content.indexOf('\n', q)
        if (nl < 0) return false
        val line = content.substring(q, nl)
        if (line.isNotBlank()) return !isListContinuationLine(line)
        q = nl + 1
    }
    return false
}

/**
 * [T-android-md-sealed-boundary-lists] Whether [line] is something the list
 * loops in [parseMarkdownBlocks] would keep consuming after a blank line: a
 * list item, or any indented line (continuation / nested content).
 */
private fun isListContinuationLine(line: String): Boolean {
    if (line.isEmpty()) return false
    if (line[0] == ' ' || line[0] == '\t') return true
    return line.matches(bulletListItemRegex) || line.matches(numberedListItemRegex) ||
        line.matches(taskListItemRegex)
}

/**
 * [T-android-md-parse-gate] The entry point every streaming parse must use.
 *
 * Combines the two protections: it takes the global [markdownParseGate]
 * permit (so N concurrent sub-agent streams cannot multiply peak native ICU
 * allocation) and reuses the already-parsed stable prefix carried in [state]
 * (so an append-only stream is not re-parsed from byte 0 on every tick).
 *
 * The permit is taken around the parse only. Suspending here is the desired
 * back-pressure: a caller whose LaunchedEffect is cancelled while queued
 * never runs its parse, which is correct — a newer chunk has superseded it.
 */
private suspend fun parseMarkdownIncremental(
    content: String,
    state: IncrementalParseState,
): List<MdBlock> = markdownParseGate.withPermit {
    // Growth must be append-only for the cached prefix to remain valid. Any
    // other edit (retry, edit-message, a shorter re-render) resets the state.
    val reusable = state.prefixLength in 1..content.length &&
        prefixFingerprint(content, state.prefixLength) == state.prefixHash
    if (!reusable) {
        state.prefixLength = 0
        state.prefixBlocks = emptyList()
        state.prefixHash = 0
    }

    val head = state.prefixLength
    // Split the unparsed remainder at its newest sealed boundary, so the part
    // that can never change again is parsed exactly once, here, and then
    // frozen into the prefix for every later tick.
    val sealedEnd = stableParsePrefixEnd(content, head)
    val sealedTail = if (sealedEnd > head) {
        parseMarkdownBlocks(content.substring(head, sealedEnd))
    } else {
        emptyList()
    }
    val liveTail = if (sealedEnd < content.length) {
        parseMarkdownBlocks(content.substring(sealedEnd))
    } else {
        emptyList()
    }

    if (sealedEnd > head) {
        state.prefixBlocks = state.prefixBlocks + sealedTail
        state.prefixLength = sealedEnd
        state.prefixHash = prefixFingerprint(content, sealedEnd)
    }
    state.prefixBlocks + liveTail
}

// ─── Block model ────────────────────────────────────────────────────────────

internal sealed class MdBlock(val raw: String) {
    class Paragraph(raw: String) : MdBlock(raw)
    class Heading(raw: String, val level: Int, val text: String) : MdBlock(raw)
    class CodeBlock(raw: String, val language: String, val code: String) : MdBlock(raw)
    class BlockQuote(raw: String, val innerBlocks: List<MdBlock>) : MdBlock(raw)
    class UnorderedList(raw: String, val items: List<ListItem>) : MdBlock(raw)
    class OrderedList(raw: String, val items: List<ListItem>, val startNum: Int = 1) : MdBlock(raw)
    class TaskList(raw: String, val items: List<TaskItem>) : MdBlock(raw)
    class HorizontalRule(raw: String) : MdBlock(raw)
    class Table(
        raw: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        /** Per-column alignment from the separator row; missing columns are START. */
        val alignments: List<MdTableAlign> = emptyList(),
    ) : MdBlock(raw)
    class Image(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Video(raw: String, val alt: String, val url: String) : MdBlock(raw)
    class Audio(raw: String, val alt: String, val url: String) : MdBlock(raw)
    /** T155: display-mode LaTeX rendered via KaTeX (`$$…$$` or `\[…\]`). */
    class MathDisplay(raw: String, val latex: String) : MdBlock(raw)
}

private val nativeVideoExts = setOf("mp4", "mov", "m4v", "avi", "mkv", "webm")
private val nativeAudioExts = setOf("mp3", "m4a", "wav", "aac", "ogg", "flac")

internal fun mediaBlockFrom(raw: String, alt: String, url: String): MdBlock {
    // Classify by the last path segment's extension. Decoding first means a
    // filename like `foo%23China.mp4` or `foo#China.mp4` still resolves to
    // `.mp4` instead of being swallowed by `substringBefore('#')`.
    val lastSeg = url.substringAfterLast('/')
    val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
    val ext = decoded.substringAfterLast('.', "").lowercase()
    return when (ext) {
        in nativeVideoExts -> MdBlock.Video(raw, alt, url)
        in nativeAudioExts -> MdBlock.Audio(raw, alt, url)
        else -> MdBlock.Image(raw, alt, url)
    }
}

/** Matches any `![alt](url)` anywhere in a line. Non-greedy to handle multiple per line. */
private val inlineMediaRegex = Regex("""!\[([^\]\n]*)]\(([^)\s]+)\)""")

// ─── Hoisted block-parser regexes ─────────────────────────────────────────────
//
// parseMarkdownBlocks runs on every recompose during streaming — once per
// chunk, often dozens of times a second. Constructing each Regex inline
// triggered Pattern.compile (an ICU JNI call) on the main thread for every
// pattern, every chunk, on every block; long markdown documents pinned the
// main thread inside Pattern.compile long enough that the OS posted ANRs
// (>5 s waited for input). Hoisting to file-level vals compiles each pattern
// exactly once, at class init, so the streaming hot path is allocation-free
// for these matches.
internal val thematicBreakRegex = Regex("^[-*_]{3,}\\s*$")
internal val standaloneImageLineRegex = Regex("^!\\[.*]\\(.*\\)\\s*$")
internal val imageMatchRegex = Regex("^!\\[(.*)\\]\\((.*)\\)")
internal val tableSeparatorRegex = Regex("^\\|?[\\s\\-:|]+\\|?$")
internal val taskListItemRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+.*")
internal val taskListPrefixRegex = Regex("^[-*+]\\s+\\[[ xX]\\]\\s+")
internal val bulletListItemRegex = Regex("^[-*+]\\s+.*")
internal val bulletListPrefixRegex = Regex("^[-*+]\\s+")
internal val numberedListItemRegex = Regex("^\\d+[.)\\s]+.*")
internal val numberedListStartRegex = Regex("^(\\d+)")
internal val numberedListPrefixRegex = Regex("^\\d+[.)\\s]+")

/**
 * A blockquote line must be `>` followed by a space, a tab, or end of line.
 * Anything else (e.g. `>foo`, `>5`, `>=`) is regular prose — likely shell
 * output or a comparison emitted by the LLM, not an intentional quote.
 */
internal fun isBlockquoteLine(trimmed: String): Boolean {
    if (!trimmed.startsWith(">")) return false
    if (trimmed.length == 1) return true
    val next = trimmed[1]
    return next == ' ' || next == '\t'
}

/**
 * Split a paragraph's raw text at inline `![alt](url)` occurrences, extracting
 * video/audio references into standalone MdBlock.Video/Audio blocks. Image
 * references stay inline (Compose doesn't render inline bitmap attachments in
 * text here, but the `[alt]` link fallback is acceptable for images).
 *
 * Why: LLMs very commonly emit `"Here's the video: ![robot](minis://attachments/x.mp4)"`
 * on a single line alongside explanatory text. Without this split, the line
 * becomes one Paragraph and the video markdown is rendered as just a blue
 * `[alt]` link — no preview card, no tap-to-play.
 */
internal fun splitParagraphOnInlineMedia(text: String): List<MdBlock> {
    if (text.length > BoundedText.MAX_ICU_INPUT_CHARS) {
        return listOf(MdBlock.Paragraph(text))
    }
    val matches = inlineMediaRegex.findAll(text).toList()
    if (matches.isEmpty()) return listOf(MdBlock.Paragraph(text))

    // Pre-check: only split when at least one match is a media (video/audio)
    // that we can render as a card. Plain image-extension matches stay inline.
    val hasMediaExt = matches.any { m ->
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        ext in nativeVideoExts || ext in nativeAudioExts
    }
    if (!hasMediaExt) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (m in matches) {
        val url = m.groupValues[2]
        val lastSeg = url.substringAfterLast('/')
        val decoded = runCatching { java.net.URLDecoder.decode(lastSeg, "UTF-8") }.getOrDefault(lastSeg)
        val ext = decoded.substringAfterLast('.', "").lowercase()
        val isMedia = ext in nativeVideoExts || ext in nativeAudioExts
        if (!isMedia) continue

        val preceding = text.substring(cursor, m.range.first).trim('\n', ' ', '\t')
        if (preceding.isNotBlank()) result.add(MdBlock.Paragraph(preceding))
        val alt = m.groupValues[1]
        result.add(mediaBlockFrom(m.value, alt, url))
        cursor = m.range.last + 1
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

/**
 * T208-4 part 3: heuristic for "wide" inline math that should be promoted
 * to a display-mode block instead of stuffed into Compose's fixed-size
 * `InlineTextContent` placeholder.
 *
 * Wide constructs (matrices, aligned, multi-row \\, large \frac, long
 * formulas) overflow the inline slot — Compose's Placeholder API can't
 * resize per-formula, so the only options inside an inline span are
 * "clip" or "scale-down to unreadable". Promoting to a display block
 * lets it render at its natural size on its own line (same shape that
 * Markwon and MathJax adopt for `\displaystyle` / `\begin{...}`).
 *
 * Short inline math (`$x$`, `$x_i$`, `$f(x)=5$`) stays inline so prose
 * still flows naturally.
 */
private fun looksLikeWideMath(latex: String): Boolean {
    if (latex.length > 30) return true
    if (latex.contains("\\begin{")) return true        // bmatrix, pmatrix, aligned, cases…
    if (latex.contains("\\\\")) return true            // explicit LaTeX line break / matrix row sep
    if (latex.contains("\\frac")) return true          // fractions render two-line
    if (latex.contains("\\sum") || latex.contains("\\int") || latex.contains("\\prod")) return true
    if (latex.contains("\\sqrt")) return true
    if (latex.contains("\\mathbf{") || latex.contains("\\mathbb{") || latex.contains("\\mathcal{")) return true
    if (latex.contains("\\overline") || latex.contains("\\underline")) return true
    if (latex.contains("\\binom")) return true
    return false
}

/**
 * T208-4 part 3: split a paragraph at *wide* inline math spans, promoting
 * each one to a `MathDisplay` block. Mirrors the inline-media split: the
 * text before the math becomes a Paragraph, the math becomes its own
 * block, the trailing text becomes a Paragraph. Short math stays inline.
 *
 * Recognises the same delimiters as `parseInline`: `\(...\)` and
 * single-`$...$` (skipping `$$` which is already a block-level form).
 *
 * Walking the string by hand (rather than regex) so escape rules and
 * the "stop at newline" behavior of `findInlineMathClose` stay in sync
 * with the inline parser.
 */
internal fun splitParagraphOnWideMath(text: String): List<MdBlock> {
    if (!text.contains('\\') && !text.contains('$')) return listOf(MdBlock.Paragraph(text))

    data class Span(val start: Int, val end: Int, val latex: String)
    val spans = mutableListOf<Span>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        // Skip escaped chars inside prose so `\$5` doesn't open a math span.
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                val latex = text.substring(i + 2, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 2, latex))
                }
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && looksLikeWideMath(latex)) {
                    spans.add(Span(i, end + 1, latex))
                }
                i = end + 1; continue
            }
        }
        i++
    }
    if (spans.isEmpty()) return listOf(MdBlock.Paragraph(text))

    val result = mutableListOf<MdBlock>()
    var cursor = 0
    for (s in spans) {
        val before = text.substring(cursor, s.start).trim('\n', ' ', '\t')
        if (before.isNotBlank()) result.add(MdBlock.Paragraph(before))
        result.add(MdBlock.MathDisplay(text.substring(s.start, s.end), s.latex))
        cursor = s.end
    }
    val tail = text.substring(cursor).trim('\n', ' ', '\t')
    if (tail.isNotBlank()) result.add(MdBlock.Paragraph(tail))
    return result
}

// [T-u6-smt-merge] Upstream's wide-math split for list items, adopted whole.
// Both data classes stay `internal` (the parser in StreamingMarkdownParse.kt
// builds them) while upstream's `TaskItem.children` and the two helpers come
// across, so wiring `items.map { it.withWideMathSplit() }` into the moved
// parser is a one-line follow-up — see the U6 leftover note.
internal data class ListItem(val text: String, val children: List<MdBlock> = emptyList())

/**
 * [T-android-math-overflow-wrap] A list item whose text holds wide inline
 * math renders as [ListItem.children]: its prose as paragraphs and each wide
 * formula as a display block that fits (and wraps to) the line. Top-level
 * paragraphs already get this split ([splitParagraphOnWideMath]); list items
 * kept the formula inline, in a fixed-size slot up to 22em wide, so on a
 * phone a long formula in a list ran past the right edge and was clipped —
 * the iOS report behind 7238a39d4. Items without wide math are unchanged.
 */
internal fun ListItem.withWideMathSplit(): ListItem {
    val split = splitParagraphOnWideMath(text)
    return if (split.any { it is MdBlock.MathDisplay }) copy(children = split) else this
}

internal data class TaskItem(
    val checked: Boolean,
    val text: String,
    val children: List<MdBlock> = emptyList(),
)

/** [T-android-math-overflow-wrap] Same wide-math split as [ListItem.withWideMathSplit]. */
internal fun TaskItem.withWideMathSplit(): TaskItem {
    val split = splitParagraphOnWideMath(text)
    return if (split.any { it is MdBlock.MathDisplay }) copy(children = split) else this
}

// ─── Block parser ───────────────────────────────────────────────────────────

/**
 * [T-android-latex-code-mask] Find the line index that closes a multi-line
 * `$$` display-math block opened just before [from], or null when no
 * *plausible* closer exists.
 *
 * Mirrors the rules ported into MarkdownParser (521b2dc7 / iOS bce7e2ed):
 *  - stop at a blank line — that is a paragraph break, so the `$$` was never
 *    a formula opener;
 *  - stop at a fence marker (``` / ~~~) and never look past it, so a `$$`
 *    living inside a code block can never be mistaken for the closer;
 *  - require at least one LaTeX-ish glyph in the body, so runs of plain prose
 *    are not silently rendered as math.
 *
 * Returning null makes the caller emit the `$$` as literal text, which is what
 * the user typed and what every other markdown renderer does.
 */
internal fun findDisplayMathClose(lines: List<String>, from: Int): Int? {
    var j = from
    val body = StringBuilder()
    while (j < lines.size) {
        val l = lines[j]
        val t = l.trimStart()
        // A fence starts/ends a code region — a `$$` beyond it is not our closer.
        if (t.startsWith("```") || t.startsWith("~~~")) return null
        // Blank line = paragraph break; real display math has no interior blank.
        if (t.isBlank()) return null
        val close = l.indexOf("$$")
        if (close >= 0) {
            body.append(l.substring(0, close))
            val text = body.toString()
            // A closer sitting alone on its own line is the conventional
            // `$$ … $$` block shape and is accepted unconditionally — that
            // covers glyph-free but perfectly valid math like "1 + 2 = 3",
            // which an "always require a LaTeX glyph" rule would wrongly
            // demote to plain text.
            if (t == "$$") return j
            // Degenerate empty body is harmless.
            if (text.isBlank()) return j
            // Otherwise the closer is mid-line (e.g. "… foo $$ bar"), which is
            // the shape a stray delimiter in prose produces. Only accept it
            // when the body actually looks like a formula.
            return if (text.any { it == '\\' || it == '^' || it == '_' || it == '{' || it == '}' }) j else null
        }
        body.append(l).append('\n')
        j++
    }
    return null
}


internal fun MutableList<MdBlock>.addParagraphChunks(text: String) {
    if (text.isEmpty()) return
    for (chunk in BoundedText.splitForCompose(text)) {
        add(MdBlock.Paragraph(chunk))
    }
}

internal fun MutableList<MdBlock>.addCodeChunks(raw: String, lang: String, code: String) {
    val chunks = BoundedText.splitForCompose(code)
    if (chunks.size <= 1) {
        add(MdBlock.CodeBlock(raw, lang, code))
        return
    }
    for ((idx, c) in chunks.withIndex()) {
        add(MdBlock.CodeBlock(c, if (idx == 0) lang else "", c))
    }
}

// [T-u6-smt-merge] Upstream's GFM column alignment, adopted. `ParsedTable`
// keeps `component1`/`component2` so the two callers that only want the grid
// (StreamingMarkdownParse.kt destructures `val (headers, rows) = parseTable(…)`)
// keep compiling unchanged — upstream's own call site reads `parsed.headers` /
// `.rows` / `.alignments`, and TableColumnAlignTest asserts `.alignments`.
/**
 * [T-android-table-column-align] GFM column alignment, from the separator row:
 * `:---:` centre, `---:` end, `---` / `:---` start (the default, as on iOS).
 */
enum class MdTableAlign { START, CENTER, END }

internal class ParsedTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<MdTableAlign> = emptyList(),
) {
    /** [T-u6-smt-merge] Grid-only destructuring: `val (headers, rows) = …`. */
    operator fun component1(): List<String> = headers
    operator fun component2(): List<List<String>> = rows
}

internal fun parseTableAlignments(separator: String): List<MdTableAlign> =
    separator.trim().removePrefix("|").removeSuffix("|").split("|").map { raw ->
        val cell = raw.trim()
        val left = cell.startsWith(':')
        val right = cell.endsWith(':') && cell.length > 1
        when {
            left && right -> MdTableAlign.CENTER
            right -> MdTableAlign.END
            else -> MdTableAlign.START
        }
    }

internal fun parseTable(lines: List<String>): ParsedTable {
    val headers = mutableListOf<String>()
    val rows = mutableListOf<List<String>>()
    var alignments: List<MdTableAlign> = emptyList()
    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.matches(tableSeparatorRegex)) {
            // Only the separator right under the header defines alignment; a stray
            // separator-looking row further down is skipped as before.
            if (alignments.isEmpty() && headers.isNotEmpty() && rows.isEmpty()) {
                alignments = parseTableAlignments(trimmed)
            }
            continue
        }
        // T308: Strip the leading/trailing pipe (if present) before splitting.
        // The previous `.filter { isNotEmpty() }` swallowed legitimate empty
        // cells like the first column of `| | Manus | TikTok |`, leaving the
        // header with fewer columns than body rows and breaking alignment.
        val core = trimmed.removePrefix("|").removeSuffix("|")
        val cells = core.split("|").map { it.trim() }
        if (headers.isEmpty()) headers.addAll(cells) else rows.add(cells)
    }
    return ParsedTable(headers, rows, alignments)
}

// ─── Math (KaTeX) ───────────────────────────────────────────────────────────

/**
 * T208-4 part 4: Per-latex InlineTextContent registry.
 *
 * Compose's Placeholder API requires a fixed size at construction — there
 * is no way to resize a placeholder after the inline text has been laid
 * out. The previous design used a single shared placeholder sized to
 * `fontSize * 6 × fontSize * 1.4` (≈ 96 × 22.5 dp at 16 sp); KaTeX
 * routinely produces ~26 dp tall bitmaps (subscript descenders), and any
 * formula wider than 96 dp simply did not fit. ContentScale.Fit then
 * shrank every formula to ~85 % to make it fit the slot, producing the
 * "everything looks shrunken" output the user reported in T208-4.
 *
 * The fix: each unique latex string registers its OWN InlineTextContent
 * with its own placeholder, sized via `estimateInlineMathSize` based on
 * the latex's character count and structural triggers. Compose draws the
 * KaTeX bitmap at its natural dp size centered inside that slot — no
 * shrink, no clip. Extra padding inside an over-estimated slot is
 * harmless; under-estimating would re-introduce the shrink, so the
 * estimator is intentionally generous.
 *
 * The list of latex strings comes from `collectInlineMathLatex`, which
 * runs the same delimiter scanner as `parseInline` over the raw text.
 */
@Composable
internal fun rememberKatexInlineContent(
    fontSize: TextUnit,
    latexList: List<String>,
): Map<String, androidx.compose.foundation.text.InlineTextContent> {
    if (latexList.isEmpty()) return emptyMap()
    return remember(fontSize, latexList) {
        val map = HashMap<String, androidx.compose.foundation.text.InlineTextContent>(latexList.size)
        for (latex in latexList.toSet()) {
            val (w, h) = estimateInlineMathSize(latex, fontSize)
            map[katexInlineTagFor(latex)] = androidx.compose.foundation.text.InlineTextContent(
                placeholder = androidx.compose.ui.text.Placeholder(
                    width = w,
                    height = h,
                    // [T-android-math-baseline] TextCenter, was AboveBaseline.
                    // The slot is over-estimated AND the KaTeX bitmap carries
                    // its own top/bottom whitespace, so an above-baseline slot
                    // put the formula's optical center well ABOVE the line's
                    // ("N(100) 渲染偏上"). Centering the slot on the line and
                    // the bitmap in the slot (CenterStart below) aligns the
                    // two optical centers instead — robust against both the
                    // generous estimate and the bitmap padding.
                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
                ),
            ) { _ ->
                // Compose passes the alternative-text to the children lambda;
                // we already keyed the slot per-latex so we use the closure's
                // `latex` directly to avoid any tag/text mismatch.
                RenderInlineMath(latex = latex, fontSize = fontSize)
            }
        }
        map
    }
}

// ─── Broken image placeholder ───────────────────────────────────────────────

/**
 * T148: Visible fallback for `SubcomposeAsyncImage` error state inside
 * markdown — rendered when Coil can't load the source (file deleted,
 * 404, IO error). Without this the slot paints nothing and the user
 * can't tell whether the image is missing or whether the renderer is
 * broken. The outer `imageBaseModifier` already supplies the T146
 * border/shadow/rounded-corner frame; here we just fill the inside
 * with a subtle tool-bg, a broken-image glyph, and the alt text.
 */
@Composable
internal fun BrokenImagePlaceholder(alt: String?) {
    val palette = com.openminis.app.ui.theme.LocalChatPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(palette.toolBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = palette.secondaryText,
            )
            Text(
                text = alt?.takeIf { it.isNotBlank() } ?: "Image not available",
                fontSize = 12.sp,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal fun formatMdMediaMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * [T-android-table-hscroll-preserve] Process-level cache of table horizontal
 * ScrollStates, keyed by a STABLE table identity (message/shard/headers).
 *
 * Why not a plain `rememberScrollState()`: remember is positional. A streaming
 * publish re-parses the fragment, and when the fragment freezes the render
 * moves from the live branch to the frozen-cache branch — a different position
 * in the composition tree — so the anonymous ScrollState was recreated and the
 * user's horizontal offset snapped back to 0 on wide tables (the Android
 * sibling of iOS T-ios-table-hscroll-offset-lost, fixed by a persistent
 * per-attachment offset there). Handing out the SAME ScrollState instance for
 * the same table identity keeps the offset across recomposition, branch moves,
 * and re-parses.
 *
 * Key uses the header row (stable from the moment a table starts streaming —
 * rows append below it) rather than full content, which would change on every
 * appended row. Two tables with an identical header row in the SAME shard
 * would share an offset — acceptable: they'd have identical column layouts.
 * LRU-bounded so long sessions can't accumulate states without limit.
 */
internal object TableHScrollStates {
    private const val MAX_ENTRIES = 64

    // Access-ordered LinkedHashMap LRU (pure Kotlin — android.util.LruCache is
    // a throwing stub in JVM unit tests, and this object is unit-tested).
    private val states = object : LinkedHashMap<String, ScrollState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrollState>): Boolean =
            size > MAX_ENTRIES
    }

    fun stateFor(key: String): ScrollState =
        synchronized(states) {
            states.getOrPut(key) { ScrollState(0) }
        }
}

// ─── [T-android-inline-parse-offmain] Parse caches ──────────────────────────
//
// RenderBlock used to call parseInline / collectInlineMathLatex DIRECTLY in
// composition — on the main thread, un-remembered, so every recomposition of a
// visible block re-ran the inline scan, and the LIVE block re-ran it on every
// streaming publish. That is the main-thread regex/ICU load in the
// minis-2026-06-10 ANR stack. All call sites now go through these process-wide
// LRUs; the streaming parse paths PREWARM them on Dispatchers.Default before
// publishing blocks, so the subsequent main-thread composition is a pure cache
// hit. Inputs are pure functions of (text [, colors]) and the outputs
// (AnnotatedString / List<String> / List<MdBlock>) are immutable, so
// cross-thread sharing is safe. MdColors is a data class → structural key;
// theme switches simply mint new entries and old ones age out.
/**
 * [T-android-stream-render-profile] Always-on, low-overhead aggregate profiler
 * for the live streaming markdown render. Accumulates the per-tick off-main
 * parse time (block split + prewarm/incremental inline+math) of the live tail
 * and emits ONE summary line every [FLUSH_TICKS] ticks (not per tick — keeps
 * log volume + the logging cost itself negligible). Gives a directly-comparable
 * "how heavy is streaming render per tick" number for benchmarking (e.g.
 * incremental on vs off) and a standing signal in real-device use. Verified on
 * a Pixel 4a with DeepSeek V4 Flash at a 30432-char single reply: parse avg
 * 3-8ms / max ~15ms even as the live fragment grew, native heap flat 55-88MB
 * (vs the pre-fix 42↔207MB GC storm), 0 hangs.
 */
internal object StreamRenderProfiler {
    private const val FLUSH_TICKS = 20
    private var ticks = 0
    private var parseMsSum = 0.0
    private var parseMsMax = 0.0
    private var lastFragLen = 0
    private var maxFragLen = 0

    /** One off-main live-tick: block split + prewarm/incremental inline+math. */
    @Synchronized
    fun recordParse(fragLen: Int, ms: Double) {
        parseMsSum += ms
        if (ms > parseMsMax) parseMsMax = ms
        lastFragLen = fragLen
        if (fragLen > maxFragLen) maxFragLen = fragLen
        ticks++
        if (ticks < FLUSH_TICKS) return
        val n = ticks
        com.openminis.app.logging.AppLogger.info(
            "StreamRender",
            "[StreamRender] ticks=$n fragLen=$lastFragLen(max=$maxFragLen) " +
                "parseMs avg=${"%.1f".format(parseMsSum / n)} max=${"%.1f".format(parseMsMax)}",
        )
        ticks = 0; parseMsSum = 0.0; parseMsMax = 0.0; maxFragLen = 0
    }
}

// ─── Inline markdown parser → AnnotatedString ───────────────────────────────

/**
 * T156: find the closing backtick for an inline-code span starting at
 * [from]. Streaming chunks can deliver an odd backtick ahead of its
 * real partner; if a naive `indexOf` walks past a hard line break to
 * pair it with a backtick on a later line, the intervening prose gets
 * highlighted as code (the user-reported "first half of the sentence turns into code" bug). The
 * CommonMark spec already disallows newlines inside inline code, so
 * stopping at `\n` matches the canonical parser AND defends against
 * mid-stream pairings — the orphan backtick falls back to a literal
 * character until the real closer streams in.
 *
 * Returns -1 when no close is available before the next newline,
 * mirroring `indexOf` so the call site falls into the existing
 * "literal backtick" branch.
 */
internal fun findInlineCodeClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '`') return k
        if (c == '\n') return -1
        k++
    }
    return -1
}

// ─── T155: inline math helpers ──────────────────────────────────────────────

/** Compose annotation tag for inline KaTeX placeholders. */
/**
 * T208-4 part 4: per-latex inline-content tag. Each unique latex span gets
 * its own InlineTextContent slot so the placeholder can be sized to the
 * formula's predicted dimensions instead of one fixed-size slot for all
 * formulas (which forced ContentScale.Fit to shrink every taller-than-slot
 * formula to ~85% scale and clipped wider-than-slot ones).
 */
internal const val KATEX_INLINE_TAG_PREFIX = "katex_inline:"

internal fun katexInlineTagFor(latex: String): String = KATEX_INLINE_TAG_PREFIX + latex

/**
 * T208-4 part 4: estimate the on-screen dp size of an inline KaTeX render
 * BEFORE it has actually rendered, so we can size the InlineTextContent
 * placeholder appropriately. Compose's Placeholder API requires a size at
 * construction time and the inline Text layout reserves exactly that
 * amount of space — so we have to predict.
 *
 * Heuristics calibrated against the T208-DBG logs collected from a real
 * device: KaTeX produces ~`fontSize * 1.6` dp wide per visible character
 * for ordinary glyphs at 16 sp / density 2.625, and ~`fontSize * 1.65` dp
 * tall for one-line formulas (descenders + sub/superscript whitespace).
 * Stacked constructs (\frac, \begin, \sqrt with fraction inside) need
 * 2-3× the height. These numbers are intentionally generous — Compose
 * will draw the bitmap at its natural dp size centered inside the slot,
 * so an over-sized slot just produces extra whitespace, but an
 * under-sized slot triggers shrink/clip.
 */
internal fun estimateInlineMathSize(latex: String, fontSize: TextUnit): Pair<TextUnit, TextUnit> {
    val visibleCharCount = run {
        var c = 0
        var i = 0
        while (i < latex.length) {
            val ch = latex[i]
            if (ch == '\\' && i + 1 < latex.length) {
                // Skip a TeX command name; count the command as ~1.5 visible chars.
                i++
                while (i < latex.length && latex[i].isLetter()) i++
                c += 1
                continue
            }
            if (ch == '{' || ch == '}' || ch == ' ') { i++; continue }
            c++
            i++
        }
        c.coerceAtLeast(1)
    }

    // Width: ~0.95 em per visible char for typical math glyphs. KaTeX's
    // measured widths run ~0.95 em/char for ordinary symbols and >1 em/char
    // when `\text{...}` switches to a proportional sans/serif body face;
    // tuning down to 0.65 underestimated formulas like `W_c^{\text{non-private}}`
    // (244 dp natural wide vs 166 dp slot → Image got clipped/shrunk).
    // Cap at a generous upper bound — wide-math splitter has already
    // promoted truly wide formulas (length>30 OR `\begin{...}` etc.) to
    // display blocks, so anything reaching this estimator is short-ish
    // inline math; the cap is just defensive against pathological input.
    val charWidthEm = 0.95f
    val widthEm = (visibleCharCount * charWidthEm).coerceIn(1.5f, 22f)

    // Height: ~1.7 em base (matches measured ~26 dp for 16 sp).
    // Stacked constructs need vertical room for numerator+bar+denominator.
    var heightEm = 1.7f
    if (latex.contains("\\frac") || latex.contains("\\binom") ||
        latex.contains("\\sum") || latex.contains("\\int") ||
        latex.contains("\\prod") || latex.contains("\\sqrt[")
    ) heightEm = 3.2f
    if (latex.contains("\\begin{") || latex.contains("\\\\")) heightEm = 4.5f

    return (fontSize * widthEm) to (fontSize * heightEm)
}

/**
 * T208-4 part 4: scan a markdown line for inline-math spans (same delimiter
 * logic as parseInline) so we can pre-register a sized placeholder for
 * each unique latex BEFORE the AnnotatedString is laid out.
 */
internal fun collectInlineMathLatex(text: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length && text[i + 1] != '(' && text[i + 1] != '[') {
            i += 2; continue
        }
        if (c == '\\' && i + 1 < text.length && text[i + 1] == '(') {
            val end = text.indexOf("\\)", i + 2)
            if (end != -1) {
                out.add(text.substring(i + 2, end))
                i = end + 2; continue
            }
        }
        if (c == '$' && i + 1 < text.length && text[i + 1] != '$' && text[i + 1] != ' ') {
            val end = findInlineMathClose(text, i + 1)
            if (end != -1) {
                val latex = text.substring(i + 1, end)
                if (looksLikeMath(latex) && !isTablePipeArtifact(latex)) {
                    out.add(latex)
                    // Consumed a real span — jump past its closing `$`.
                    i = end + 1; continue
                }
                // [T-latex-inline] Rejected (currency / artifact): DON'T skip past
                // the closing `$`. Advancing to end+1 here would swallow the `$`
                // that legitimately OPENS the next span (`cost $5, and $x+y$` lost
                // `x+y` because the `$` before `x` got consumed as the first span's
                // close). Fall through to i++ so that `$` stays available. Matches
                // parseInline, which appends the char and advances by 1 on reject.
            }
        }
        i++
    }
    return out
}

/**
 * Find the closing `$` for an inline-math span starting at [from].
 * Mirrors [findInlineCodeClose]: stop at a newline so streaming chunks
 * never pair a stray `$` with the next dollar that arrives later, and
 * skip `\$` (escaped) and `$$` (which would be display math).
 */
internal fun findInlineMathClose(text: String, from: Int): Int {
    var k = from
    while (k < text.length) {
        val c = text[k]
        if (c == '\n') return -1
        if (c == '\\' && k + 1 < text.length) { k += 2; continue }
        if (c == '$') {
            // `$$` here is the start of display math, not a single-dollar close.
            if (k + 1 < text.length && text[k + 1] == '$') return -1
            return k
        }
        k++
    }
    return -1
}

/**
 * Crude heuristic to skip plain currency like `$5`, `$1,000` and avoid
 * turning prose dollar signs into KaTeX renders. Real LaTeX math nearly
 * always carries a backslash command, a brace, a math operator, or a
 * superscript/subscript marker. iOS uses the same idea
 * (MinisMarkdownParser.looksLikeMath).
 */
internal fun looksLikeMath(latex: String): Boolean {
    if (latex.isBlank()) return false
    if (latex.contains('\\')) return true
    if (latex.contains('{') || latex.contains('}')) return true
    if (latex.contains('^') || latex.contains('_')) return true
    val mathChars = "=+-*/<>≤≥≠∑∫∏√∞αβγθπφλμωΔΩ"
    if (latex.any { it in mathChars }) return true
    // [T-latex-inline] Bare short spans like `$x$`, `$pi$`, `$abc$` carry no
    // LaTeX glyph but ARE math. Mirror iOS MinisMarkdownParser.looksLikeMath,
    // which accepts `count > 2`, and additionally accept a single alphanumeric
    // token (`$x$`, `$n$`) — the strict "needs a math char" rule was the drift
    // that made single-variable inline math leak as literal `$…$`. Currency
    // (`$5`, `$1,000`) is filtered by the leading-digit guard, and `$$`/space
    // openers never reach here (gated by the caller).
    if (latex[0].isDigit()) return false            // currency: `$5`, `$10.99`
    if (latex.first().isWhitespace() || latex.last().isWhitespace()) return false
    if (latex.length <= 30 && latex.all { it.isLetterOrDigit() }) return true
    return latex.length > 2
}

/**
 * [T-latex-inline] True when a candidate inline-math span is really a markdown
 * table-cell artifact (a `$` that paired across `|` column separators) rather
 * than a formula. Mirrors iOS MinisMarkdownParser.isTablePipeArtifact so a row
 * like `| 月付 | $20|$ **3** |` doesn't capture `20|` as fake math (which would
 * eat the bold `**3**`). Two signals a real formula avoids: an unescaped pipe
 * with whitespace on a side (the ` | ` column separator), or an ODD number of
 * unescaped pipes (abs-value / norm bars always come in balanced pairs).
 * Escaped `\|` (LaTeX norm) is never counted.
 */
internal fun isTablePipeArtifact(content: String): Boolean {
    var bareCount = 0
    for (idx in content.indices) {
        if (content[idx] != '|') continue
        if (idx > 0 && content[idx - 1] == '\\') continue      // escaped norm bar
        bareCount++
        val prevIsSpace = idx > 0 && content[idx - 1].isWhitespace()
        val nextIsSpace = idx + 1 < content.length && content[idx + 1].isWhitespace()
        if (prevIsSpace || nextIsSpace) return true
    }
    return bareCount % 2 == 1
}

