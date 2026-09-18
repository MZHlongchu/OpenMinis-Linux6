package com.openminis.app.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Overlay mini-composer → ChatViewModel. Sticky so a prompt survives until
 * the target session's ViewModel is alive.
 */
object OverlayComposeBus {

    data class Request(val sessionId: String, val text: String)

    private val _pending = MutableSharedFlow<Request>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val pending: SharedFlow<Request> = _pending.asSharedFlow()

    @Volatile
    private var sticky: Request? = null

    fun submit(sessionId: String, text: String) {
        val sid = sessionId.trim()
        val body = text.trim()
        if (sid.isEmpty() || body.isEmpty()) return
        val req = Request(sid, body)
        sticky = req
        _pending.tryEmit(req)
    }

    fun consumeSticky(sessionId: String): String? {
        val s = sticky ?: return null
        if (s.sessionId != sessionId) return null
        sticky = null
        return s.text
    }

    fun peekStickySessionId(): String? = sticky?.sessionId
}
