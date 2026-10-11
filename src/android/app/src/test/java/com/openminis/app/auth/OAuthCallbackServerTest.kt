package com.openminis.app.auth

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-b18-oauth-loopback-bind] 回调服务器验收：
 *  - 监听面只绑回环地址（原先 `ServerSocket(p)` 绑通配地址，同网段可直连干扰授权）；
 *  - 绑定改造后回调语义不变（成功 code / `?error=` 拒绝 / 错误路径 404 三条）。
 *
 * 纯 JVM 测试（app 的 `unitTests.isReturnDefaultValues = true` 让 android.util.Log
 * 调用返回默认值），走真实 ServerSocket + Socket 交互，不引入新依赖。
 */
class OAuthCallbackServerTest {

    private val servers = mutableListOf<OAuthCallbackServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        servers.clear()
    }

    private fun startServer(
        onCode: (String, String?) -> Unit,
        onDenied: (() -> Unit)? = null,
    ): OAuthCallbackServer {
        val server = OAuthCallbackServer(
            port = 15937,
            fallbackPorts = listOf(15938, 15939),
            expectedPath = "/callback",
            onCode = { code, state -> onCode(code, state) },
            onDenied = onDenied,
        )
        servers += server
        server.start()
        awaitBound(server)
        return server
    }

    /** start() 在后台线程里绑定；等监听套接字真的出现。 */
    private fun awaitBound(server: OAuthCallbackServer) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (listenersOf(server).isNotEmpty()) return
            Thread.sleep(20)
        }
        throw AssertionError("OAuthCallbackServer did not bind within 5s")
    }

    @Suppress("UNCHECKED_CAST")
    private fun listenersOf(server: OAuthCallbackServer): List<ServerSocket> {
        val field = OAuthCallbackServer::class.java.getDeclaredField("listeners")
        field.isAccessible = true
        return (field.get(server) as List<ServerSocket>).toList()
    }

    private fun get(port: Int, target: String, host: String = "127.0.0.1"): String {
        Socket(host, port).use { socket ->
            socket.soTimeout = 3_000
            socket.getOutputStream().write(
                "GET $target HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray(Charsets.UTF_8),
            )
            socket.getOutputStream().flush()
            return String(socket.getInputStream().readBytes(), Charsets.UTF_8)
        }
    }

    @Test
    fun `listens on loopback addresses only`() {
        val server = startServer(onCode = { _, _ -> })

        val listeners = listenersOf(server)
        assertTrue("至少绑上一个回环监听套接字", listeners.isNotEmpty())
        for (listener in listeners) {
            assertTrue(
                "监听地址必须是回环，实际 ${listener.inetAddress}",
                listener.inetAddress.isLoopbackAddress,
            )
        }
        // 通配绑定会连本机非回环地址一起接受；回环绑定不会。
        val nonLoopback = Collections.list(NetworkInterface.getNetworkInterfaces())
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && it is Inet4Address }
        for (address in nonLoopback) {
            val refused = runCatching {
                Socket().use { probe -> probe.connect(InetSocketAddress(address, server.boundPort), 500) }
            }.isFailure
            assertTrue("非回环地址 $address 不该能连上回调端口", refused)
        }
    }

    @Test
    fun `loopback callback delivers code and state`() {
        val latch = CountDownLatch(1)
        var code: String? = null
        var state: String? = null
        val server = startServer(onCode = { c, s -> code = c; state = s; latch.countDown() })

        val response = get(server.boundPort, "/callback?code=abc123&state=xyz")

        assertTrue("onCode 必须被调用", latch.await(3, TimeUnit.SECONDS))
        assertEquals("abc123", code)
        assertEquals("xyz", state)
        assertTrue("回调响应必须是 200", response.startsWith("HTTP/1.1 200"))
    }

    @Test
    fun `error redirect fires onDenied`() {
        val latch = CountDownLatch(1)
        var code: String? = null
        val server = startServer(
            onCode = { c, _ -> code = c },
            onDenied = { latch.countDown() },
        )

        get(server.boundPort, "/callback?error=access_denied")

        assertTrue("onDenied 必须被调用", latch.await(3, TimeUnit.SECONDS))
        assertNull(code)
    }

    @Test
    fun `wrong path is refused without invoking callbacks`() {
        var invoked = false
        val server = startServer(onCode = { _, _ -> invoked = true })

        val response = get(server.boundPort, "/not-callback?code=abc")

        assertTrue(response.startsWith("HTTP/1.1 404"))
        assertTrue(!invoked)
    }
}
