package com.openminis.app.auth

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI

class OAuthCallbackServer(
    private val port: Int,
    private val fallbackPorts: List<Int> = emptyList(),
    private val expectedPath: String = "/callback",
    private val onCode: (code: String, state: String?) -> Unit,
    // [T-android-mcp-oauth-timeout] Invoked when the IdP redirects back with
    // ?error=… (consent denied) so the waiter fails fast instead of hanging.
    // Optional + default null: the Claude flow keeps its existing behavior.
    private val onDenied: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "OAuthCallbackServer"
        private const val MAX_REQUEST_LINE = 4096
        private const val MAX_RESPONSE_BYTES = 1024
        private const val ACCEPT_BACKLOG = 8

        /**
         * Hosts whose authorization page is allowed to see a CORS preflight
         * answer — exactly the xAI hosts (see [handlePreflight]). Everything
         * else gets `Access-Control-Allow-Origin: null`, i.e. no cross-origin
         * read access at all.
         */
        private val TRUSTED_CORS_HOSTS = setOf("auth.x.ai", "accounts.x.ai")

        /**
         * The `Origin` header value to echo, or null when [origin] is absent or
         * not one of [TRUSTED_CORS_HOSTS]. The host is compared exactly, on the
         * parsed URI — a substring match would accept
         * `https://evil.example/?x=auth.x.ai`.
         */
        private fun trustedCorsOrigin(origin: String?): String? {
            val value = origin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val host = runCatching { URI(value).host }.getOrNull()?.lowercase() ?: return null
            return value.takeIf { host in TRUSTED_CORS_HOSTS }
        }
    }

    /**
     * [T-b18-oauth-loopback-bind] 已绑定的回环监听套接字（IPv4，可用时加 IPv6）。
     * 每个套接字一条 accept 线程，共享同一套请求处理。
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<ServerSocket>()
    @Volatile private var running = false

    /** The port actually bound (may differ from [port] if fallback was used). */
    var boundPort: Int = port
        private set

    /**
     * Invoked by [stop] when the server is shut down by an external
     * caller (e.g. the user dismisses Chrome Custom Tab and the auth
     * manager wants to abort the in-flight wait without waiting for the
     * 5-minute timeout). The callback is _only_ fired when stop() is
     * called by something other than the success path —
     * [onCode] callers set this to null before calling stop() so they
     * don't fire a cancel after a successful redirect.
     *
     * [T-xai-oauth-stop-resume, port iOS d1dbdd5d]
     */
    @Volatile var onExternalCancel: (() -> Unit)? = null

    // [T-b18-oauth-loopback-bind] 原实现用 `ServerSocket(p)` 绑通配地址（0.0.0.0/::），
    // 同网段任何主机都能直连回调端口：可以用 `?error=access_denied` 打断授权流程
    // （回调路径与端口在各 manager 里是硬编码常量，可被扫描到），构成登录 DoS 与
    // 端口指纹。根因修法是**只绑回环地址**。IPv4 与 IPv6 回环都绑，因为注册的
    // redirect_uri 写的是 `localhost`（部分设备/浏览器解析到 ::1 优先），只绑
    // 127.0.0.1 会让回调连不上、登录静默失败。
    private fun loopbackAddresses(): List<InetAddress> = listOfNotNull(
        runCatching { InetAddress.getByName("127.0.0.1") }.getOrNull(),
        runCatching { InetAddress.getByName("::1") }.getOrNull()?.takeIf { it.isLoopbackAddress },
    )

    /** 在 [p] 上绑定所有可用的回环地址；返回空表示该端口整体不可用。 */
    private fun bindLoopback(p: Int): List<ServerSocket> {
        val bound = mutableListOf<ServerSocket>()
        for (addr in loopbackAddresses()) {
            try {
                bound += ServerSocket(p, ACCEPT_BACKLOG, addr)
            } catch (e: Exception) {
                if (bound.isEmpty()) return emptyList()
                // IPv4 已绑上、IPv6 侧不可用（内核无 v6 回环等）：不致命，降级为只监听 IPv4。
                Log.w(TAG, "loopback ${addr.hostAddress}:$p unavailable: ${e.message}")
            }
        }
        return bound
    }

    fun start() {
        running = true
        Thread {
            try {
                val portsToTry = listOf(port) + fallbackPorts
                var bound: List<ServerSocket> = emptyList()
                for (p in portsToTry) {
                    bound = bindLoopback(p)
                    if (bound.isNotEmpty()) {
                        boundPort = p
                        break
                    }
                    Log.w(TAG, "Port $p in use, trying next...")
                }
                if (bound.isEmpty()) {
                    Log.e(TAG, "All ports unavailable: $portsToTry")
                    return@Thread
                }
                listeners.addAll(bound)
                Log.d(TAG, "Listening on loopback port $boundPort (${bound.size} socket(s))")
                // 第一条套接字在当前线程 accept（与旧实现同形），额外的各起一条
                // 守护线程；所有线程共用 [acceptLoop]，stop() 关闭套接字后一起退出。
                bound.drop(1).forEach { extra ->
                    Thread { acceptLoop(extra) }.apply { isDaemon = true }.start()
                }
                acceptLoop(bound.first())
            } catch (e: Exception) {
                if (running) Log.e(TAG, "Server error", e)
            }
        }.start()
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.message}")
                return
            }
            // 第二道闸：来源必须是回环。绑定已经限死了监听地址，这里再做一次显式
            // 白名单，防止将来有人改回通配绑定又把这条洞放回来。
            val peer = runCatching { socket.inetAddress }.getOrNull()
            if (peer == null || !peer.isLoopbackAddress) {
                Log.w(TAG, "Rejected non-loopback callback from ${peer?.hostAddress}")
                runCatching { socket.close() }
                continue
            }
            handle(socket)
        }
    }

    private fun handle(socket: java.net.Socket) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            if (requestLine.length > MAX_REQUEST_LINE) { writeResponse(socket, 414, "Request URI too long"); return }
            // [T-android-oauth-log-redact, upstream 1.14] 请求行带着 code/state，
            // 只能以脱敏形态进日志（app 日志会随 bug report 导出）。
            Log.d(TAG, "Request: ${OAuthLogRedaction.url(requestLine)}")
            val parts = requestLine.split(' ')
            if (parts.size != 3 || parts[2] != "HTTP/1.1") { writeResponse(socket, 400, "Bad request"); return }
            // [upstream 1.14] xAI 的授权页在跳转回回调前会先对本地址发 OPTIONS
            // 预检；不回预检，浏览器就不跟随跳转，登录会卡死（上游既有行为，
            // 我们的 accept 重写一度把它整段删掉了）。这里按"只对本服务器注册
            // 的回调路径 + 只回显已知 xAI 来源"的最小面恢复，其余来源一律
            // `Access-Control-Allow-Origin: null`，不做通配放行；非回调路径 404。
            if (parts[0] == "OPTIONS") { handlePreflight(socket, reader, parts[1]); return }
            if (parts[0] != "GET") { writeResponse(socket, 405, "Method not allowed"); return }
            val requestTarget = parts[1]
            var headerBytes = 0
            while (true) {
                val header = reader.readLine() ?: break
                headerBytes += header.length
                if (header.isEmpty() || headerBytes > MAX_REQUEST_LINE) break
            }
            if (headerBytes > MAX_REQUEST_LINE) { writeResponse(socket, 431, "Headers too large"); return }

            // Parse only the exact loopback callback path.
            val uri = URI("http://localhost$requestTarget")
            if (uri.rawPath != expectedPath || uri.rawFragment != null || (uri.rawQuery?.length ?: 0) > MAX_REQUEST_LINE) {
                writeResponse(socket, 404, "Not found")
                return
            }
            val params = uri.query?.split("&")?.associate {
                val kv = it.split("=", limit = 2)
                kv[0] to (if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else "")
            } ?: emptyMap()

            val code = params["code"]
            val state = params["state"]

            // Send response
            // [T-android-oauth-foreground-exchange, upstream 1.14] Not "complete":
            // the app still has to exchange the code, and it can only do that
            // once the user is back in Minis (background network is blocked).
            // Say so, so the user returns rather than waiting on a tab that
            // already "succeeded". Kept ASCII-only so the advertised byte
            // length and the rendered text can never disagree.
            val html = "<html><body><h1>Authorization received</h1><p>Return to Minis to finish signing in.</p><script>window.close()</script></body></html>"
            writeResponse(socket, 200, html)
            Log.i(TAG, "OAuth callback accepted: codePresent=${!code.isNullOrEmpty()} statePresent=${!state.isNullOrEmpty()}")

            val err = params["error"]
            if (!code.isNullOrEmpty()) {
                onCode(code, state)
            } else if (!err.isNullOrEmpty()) {
                Log.i(TAG, "OAuth callback denied: $err")
                onDenied?.invoke()
                stop()
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling connection", e)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /**
     * [upstream 1.14] CORS preflight answer for the narrow xAI case described in
     * [handle]. Only the registered callback path is answered, the peer is
     * already known to be loopback (see [acceptLoop]), the header block is
     * size-capped, and only [TRUSTED_CORS_HOSTS] origins are echoed back.
     */
    private fun handlePreflight(socket: java.net.Socket, reader: BufferedReader, requestTarget: String) {
        try {
            var origin: String? = null
            var headerBytes = 0
            while (true) {
                val header = reader.readLine() ?: break
                headerBytes += header.length
                if (header.isEmpty() || headerBytes > MAX_REQUEST_LINE) break
                if (header.lowercase().startsWith("origin:")) origin = header.substringAfter(":").trim()
            }
            val path = runCatching { URI("http://localhost$requestTarget").rawPath }.getOrNull()
            if (path != expectedPath) { writeResponse(socket, 404, "Not found"); return }
            val allowOrigin = trustedCorsOrigin(origin) ?: "null"
            val pre = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: $allowOrigin\r\n" +
                "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Access-Control-Max-Age: 600\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().use { it.write(pre.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling preflight", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun writeResponse(socket: java.net.Socket, status: Int, body: String) {
        val safeBody = body.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_RESPONSE_BYTES) it.copyOf(MAX_RESPONSE_BYTES) else it }
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 414 -> "URI Too Long"; 431 -> "Request Header Fields Too Large"; else -> "Error" }
        val response = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${safeBody.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().use { it.write(response.toByteArray()); it.write(safeBody) }
        socket.close()
    }

    fun stop() {
        val wasRunning = running
        running = false
        // 关闭全部回环监听套接字（IPv4 + 可选 IPv6），accept 线程随之退出。
        for (listener in listeners) {
            try { listener.close() } catch (_: Exception) {}
        }
        listeners.clear()
        // Notify external callers (e.g. XAIOAuthManager) so they can
        // cancel an in-flight suspendCancellableCoroutine instead of
        // hanging until the next inbound connection / the 5-min
        // OAuth wait timeout fires. Consume the callback (set to
        // null before invoking) so re-entrant stop() calls don't
        // double-fire (T-xai-oauth-stop-resume).
        if (wasRunning) {
            val cancel = onExternalCancel
            onExternalCancel = null
            try { cancel?.invoke() } catch (e: Exception) {
                Log.w(TAG, "onExternalCancel threw: ${e.message}")
            }
        }
    }
}
