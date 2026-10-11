package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * [T-composer-clipboard-image] Native clipboard-image paste for the composer.
 *
 * Compose 1.9's TextField paste gating is clip-ENTRY based —
 * `canPaste = isEditable && clipEntry != null` — so the system long-press
 * menu already offers Paste while the clipboard holds an image-only clip
 * (screenshot copy, "copy image" in browsers). No chip, no polling, no
 * description probing: the affordance is the field's own paste button.
 *
 * The only missing piece is the paste ACTION: the default toolbar callback
 * pastes text, and an image clip has none. [toolbar] wraps the default
 * [TextToolbar] and rewrites the paste callback — image in clipboard →
 * [pasteImage] (attach as a durable private copy); otherwise the field's
 * own text paste runs. Copy/cut/select-all, positioning and hide delegate
 * untouched.
 *
 * Privacy posture is unchanged from the old chip design: the clipboard
 * DATA is only read when the user actually taps Paste (the description
 * probe inside [ClipboardImagePaste.currentImageUri] rejects text clips
 * before any data access, so ordinary text pastes never trigger the
 * Android 12+ clipboard-access toast).
 */
object ComposerImagePaste {

    /**
     * Wrap [delegate] so its Paste action tries the image path first.
     *
     * [hasImageClip] is the cheap clipboard-description probe: it runs on the
     * UI thread while the menu item is handled and must return synchronously.
     * [pasteImage] performs the byte copy and is expected to dispatch that
     * work off the UI thread itself — [T-b1-paste-io] the whole image used to
     * be read, written to a temp file and re-staged inside this click.
     */
    fun toolbar(
        delegate: TextToolbar,
        hasImageClip: () -> Boolean,
        pasteImage: () -> Unit,
    ): TextToolbar = PasteInterceptingTextToolbar(delegate, hasImageClip, pasteImage)

    private class PasteInterceptingTextToolbar(
        private val delegate: TextToolbar,
        private val hasImageClip: () -> Boolean,
        private val pasteImage: () -> Unit,
    ) : TextToolbar {

        override val status: TextToolbarStatus
            get() = delegate.status

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            val interceptedPaste = onPasteRequested?.let { fallback ->
                {
                    // Image wins: a clip carrying an image is a copy-image
                    // gesture. Text riding in the same clip is deliberately
                    // not co-pasted. With no image in the clipboard the normal
                    // text paste runs — synchronously, exactly as before.
                    if (hasImageClip()) pasteImage() else fallback()
                }
            }
            delegate.showMenu(rect, onCopyRequested, interceptedPaste, onCutRequested, onSelectAllRequested)
        }

        override fun hide() {
            delegate.hide()
        }
    }
}
