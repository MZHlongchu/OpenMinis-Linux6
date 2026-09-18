package com.openminis.app.share

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.FileProvider
import com.openminis.app.ui.chat.ChatMessage
import java.io.File
import java.io.FileOutputStream

data class ConversationCardOptions(
    val theme: Theme = Theme.DARK,
    val hideTools: Boolean = true,
    val paginate: Boolean = false,
) {
    enum class Theme { DARK, LIGHT, PAPER }
}

/**
 * Render the current chat as a shareable image card (WeChat / Moments style),
 * plus a short markdown transcript in EXTRA_TEXT.
 */
object ConversationCardShare {
    private const val WIDTH = 1080
    private const val PAD = 56f
    private const val MAX_MESSAGES = 12
    private const val MAX_CHARS = 420

    fun share(
        context: Context,
        title: String,
        messages: List<ChatMessage>,
        options: ConversationCardOptions = ConversationCardOptions(),
    ) {
        val visible = selectVisible(messages, options)
        val pages = paginate(visible, options.paginate)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val uris = ArrayList<android.net.Uri>()
        pages.forEachIndexed { i, page ->
            val bitmap = render(title.ifBlank { "OpenMinis-Linux" }, page, options.theme)
            val file = File(dir, if (pages.size == 1) "conversation-card.jpg" else "conversation-card-$i.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            bitmap.recycle()
            uris += FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file,
            )
        }
        if (uris.isEmpty()) return
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }.apply {
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, transcript(title, visible))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, title).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        Handler(Looper.getMainLooper()).post {
            context.startActivity(chooser)
        }
    }

    internal fun selectVisible(
        messages: List<ChatMessage>,
        options: ConversationCardOptions = ConversationCardOptions(),
    ): List<ChatMessage> {
        val base = messages.filter { msg ->
            !msg.isQueued &&
                msg.role != "system" &&
                !ChatMessage.isInternalBridgeText(msg.content)
        }
        val mapped = base.mapNotNull { msg ->
            val content = if (options.hideTools) stripToolSections(msg.content) else msg.content
            if (content.isBlank()) null
            else msg.copy(content = content, toolBlocks = if (options.hideTools) emptyList() else msg.toolBlocks)
        }
        return mapped.takeLast(MAX_MESSAGES)
    }

    internal fun stripToolSections(content: String): String {
        var s = content
        s = s.replace(Regex("(?s)\\[tool-output-spill\\].*?(?=\\n\\n|$)"), "")
        s = s.replace(Regex("(?m)^\\s*Running:.*$"), "")
        s = s.replace(Regex("(?s)```tool[\\s\\S]*?```"), "")
        return s.replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    internal fun paginate(messages: List<ChatMessage>, paginate: Boolean): List<List<ChatMessage>> {
        if (messages.isEmpty()) return emptyList()
        if (!paginate || messages.size <= 6) return listOf(messages)
        return messages.chunked(6)
    }

    internal fun transcript(title: String, messages: List<ChatMessage>): String = buildString {
        appendLine("# $title")
        appendLine()
        for (msg in messages) {
            val who = if (msg.role == "user") "User" else "Assistant"
            appendLine("**$who**")
            appendLine(msg.content.take(MAX_CHARS).trim())
            appendLine()
        }
        appendLine("— OpenMinis-Linux")
    }

    private data class Palette(
        val bg: Int,
        val title: Int,
        val brand: Int,
        val body: Int,
        val userText: Int,
        val userBubble: Int,
        val assistantBubble: Int,
    )

    private fun palette(theme: ConversationCardOptions.Theme): Palette = when (theme) {
        ConversationCardOptions.Theme.DARK -> Palette(
            bg = 0xFF0B1220.toInt(),
            title = 0xFFE8EEFF.toInt(),
            brand = 0xFF8BA3C7.toInt(),
            body = 0xFFD5DFF0.toInt(),
            userText = 0xFFF4F7FF.toInt(),
            userBubble = 0xFF1F4B8F.toInt(),
            assistantBubble = 0xFF162033.toInt(),
        )
        ConversationCardOptions.Theme.LIGHT -> Palette(
            bg = 0xFFF6F7FB.toInt(),
            title = 0xFF111827.toInt(),
            brand = 0xFF6B7280.toInt(),
            body = 0xFF1F2937.toInt(),
            userText = 0xFF111827.toInt(),
            userBubble = 0xFFDCEBFF.toInt(),
            assistantBubble = 0xFFE8E8EE.toInt(),
        )
        ConversationCardOptions.Theme.PAPER -> Palette(
            bg = 0xFFF4EBD0.toInt(),
            title = 0xFF3B2F1A.toInt(),
            brand = 0xFF8A7048.toInt(),
            body = 0xFF3B2F1A.toInt(),
            userText = 0xFF2C2114.toInt(),
            userBubble = 0xFFE7D3A1.toInt(),
            assistantBubble = 0xFFEDE0C0.toInt(),
        )
    }

    private fun render(
        title: String,
        messages: List<ChatMessage>,
        theme: ConversationCardOptions.Theme = ConversationCardOptions.Theme.DARK,
    ): Bitmap {
        val pal = palette(theme)
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.title
            textSize = 42f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.brand
            textSize = 28f
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.body
            textSize = 32f
        }
        val userPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.userText
            textSize = 32f
        }
        val inner = (WIDTH - PAD * 2).toInt()
        val layouts = ArrayList<Pair<Boolean, StaticLayout>>()
        var contentH = 0
        val titleLayout = staticLayout(title, titlePaint, inner)
        val brandLayout = staticLayout("OpenMinis-Linux", brandPaint, inner)
        contentH += titleLayout.height + 8 + brandLayout.height + 36
        for (msg in messages) {
            val isUser = msg.role == "user"
            val text = msg.content.replace(Regex("\\s+"), " ").take(MAX_CHARS)
            val layout = staticLayout(text, if (isUser) userPaint else bodyPaint, (inner * 0.86f).toInt())
            layouts += isUser to layout
            contentH += layout.height + 48
        }
        contentH += 48
        val height = (PAD * 2 + contentH).toInt().coerceIn(640, 4096)
        val bmp = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(pal.bg)
        var y = PAD
        titleLayout.draw(canvas, PAD, y)
        y += titleLayout.height + 8
        brandLayout.draw(canvas, PAD, y)
        y += brandLayout.height + 36
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for ((isUser, layout) in layouts) {
            val bw = layout.width.toFloat() + 36f
            val bh = layout.height.toFloat() + 28f
            val left = if (isUser) WIDTH - PAD - bw else PAD
            bubblePaint.color = if (isUser) pal.userBubble else pal.assistantBubble
            canvas.drawRoundRect(RectF(left, y, left + bw, y + bh), 28f, 28f, bubblePaint)
            layout.draw(canvas, left + 18f, y + 14f)
            y += bh + 20f
        }
        return bmp
    }

    private fun staticLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .build()

    private fun StaticLayout.draw(canvas: Canvas, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        draw(canvas)
        canvas.restore()
    }
}
