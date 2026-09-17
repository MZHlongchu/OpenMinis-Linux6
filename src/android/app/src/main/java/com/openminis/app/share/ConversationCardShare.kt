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

/**
 * Render the current chat as a shareable image card (WeChat / Moments style),
 * plus a short markdown transcript in EXTRA_TEXT.
 */
object ConversationCardShare {
    private const val WIDTH = 1080
    private const val PAD = 56f
    private const val MAX_MESSAGES = 12
    private const val MAX_CHARS = 420

    fun share(context: Context, title: String, messages: List<ChatMessage>) {
        val visible = messages.filter { msg ->
            !msg.isQueued &&
                msg.role != "system" &&
                msg.content.isNotBlank() &&
                !ChatMessage.isInternalBridgeText(msg.content)
        }.takeLast(MAX_MESSAGES)
        val bitmap = render(title.ifBlank { "OpenMinis-Linux" }, visible)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "conversation-card.jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        bitmap.recycle()
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
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

    private fun render(title: String, messages: List<ChatMessage>): Bitmap {
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE8EEFF.toInt()
            textSize = 42f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val brandPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF8BA3C7.toInt()
            textSize = 28f
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFD5DFF0.toInt()
            textSize = 32f
        }
        val userPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF4F7FF.toInt()
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
        canvas.drawColor(0xFF0B1220.toInt())
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
            bubblePaint.color = if (isUser) 0xFF1F4B8F.toInt() else 0xFF162033.toInt()
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
