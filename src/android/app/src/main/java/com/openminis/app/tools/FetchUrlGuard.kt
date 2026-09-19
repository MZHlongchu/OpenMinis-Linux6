package com.openminis.app.tools

import java.net.URI

/**
 * SSRF guard for web_fetch. Blocks non-http(s) schemes and obvious
 * loopback / link-local / RFC1918 targets. Hostname-only (no extra DNS
 * round-trip) — redirects are re-checked by the caller.
 */
object FetchUrlGuard {

    fun blockedReason(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return "url is required"
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return "invalid URL"
        }
        val scheme = uri.scheme?.lowercase() ?: return "URL must include a scheme"
        if (scheme != "http" && scheme != "https") {
            return "only http/https URLs are allowed"
        }
        val host = uri.host?.lowercase()?.trim('.') ?: return "URL is missing a host"
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") ||
            host == "0.0.0.0" || host == "::1" || host == "[::1]" ||
            host == "metadata.google.internal" || host.endsWith(".internal") ||
            host.endsWith(".local")
        ) {
            return "blocked host: $host"
        }
        if (isPrivateOrLoopbackIp(host)) return "blocked private/loopback address: $host"
        return null
    }

    internal fun isPrivateOrLoopbackIp(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) {
            return h.contains(':')
        }
        val parts = h.split('.')
        if (parts.size != 4) return false
        val nums = parts.map { it.toIntOrNull() ?: return false }
        if (nums.any { it !in 0..255 }) return false
        val a = nums[0]
        val b = nums[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) || (a == 169 && b == 254) || a == 0
    }
}
