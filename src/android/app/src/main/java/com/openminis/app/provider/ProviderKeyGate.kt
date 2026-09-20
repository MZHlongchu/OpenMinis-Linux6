package com.openminis.app.provider

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * One in-flight HTTP call per rate-limit **bucket**:
 * `(relay host, credential fingerprint, model id)`.
 *
 * Relays typically shard the token bucket by model name, but the same model
 * name on **different keys** is a different bucket (another account / another
 * channel pool). Same key + same model still queues so title/compact/sub-agent
 * cannot stampede one window.
 *
 * The permit is held only for a single HTTP stream/request. The agent tool
 * loop runs *after* the stream completes, so nested spawn_agent calls cannot
 * deadlock waiting on the parent.
 *
 * Nested [withPermit] on the same coroutine and key is a no-op so
 * OpenAIProvider.sendMessageClamped → streamMessage cannot self-deadlock.
 */
object ProviderKeyGate {
    const val DEFAULT_PERMITS = 1

    private val gates = ConcurrentHashMap<String, Semaphore>()

    private class HeldKeys(val keys: Set<String>) : AbstractCoroutineContextElement(HeldKeys) {
        companion object Key : CoroutineContext.Key<HeldKeys>
    }

    fun key(host: String, secret: String?, modelId: String? = null): String {
        val h = hostOf(host)
        val fp = fingerprint(secret)
        val m = normalizeModel(modelId)
        return "$h|$fp|$m"
    }

    fun normalizeModel(modelId: String?): String =
        modelId?.trim()?.lowercase().orEmpty()

    fun fingerprint(secret: String?): String {
        val s = secret?.trim().orEmpty()
        if (s.isEmpty()) return "anon"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(s.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
    }

    fun sameBucket(a: String?, b: String?): Boolean =
        !a.isNullOrBlank() && a == b

    fun hostOf(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""
        return try {
            val uri = java.net.URI(if ("://" in t) t else "https://$t")
            (uri.host ?: t).trimEnd('.').lowercase()
        } catch (_: Exception) {
            t.trimEnd('/').lowercase()
        }
    }

    suspend fun <T> withPermit(key: String, block: suspend () -> T): T {
        if (key.isBlank()) return block()
        val held = coroutineContext[HeldKeys]?.keys
        if (held != null && key in held) return block()
        val sem = gates.getOrPut(key) { Semaphore(DEFAULT_PERMITS) }
        return sem.withPermit {
            withContext(HeldKeys((held ?: emptySet()) + key)) {
                block()
            }
        }
    }
}
