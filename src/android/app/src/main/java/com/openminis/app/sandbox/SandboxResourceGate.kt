package com.openminis.app.sandbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-session locks so parallel sub-agents / chats cannot deadlock the
 * shared PRoot guest (one gradle daemon, one SDK tree, one apt dpkg lock).
 */
object SandboxResourceGate {
    private val apkLock = Mutex()
    private val aptLock = Mutex()
    private val named = ConcurrentHashMap<String, Mutex>()

    fun isApkBuild(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("gradlew") ||
            c.contains("gradle ") ||
            Regex("""(^|\s|\./)gradle(\s|$)""").containsMatchIn(c) ||
            c.contains("assembledebug") ||
            c.contains("assemblerelease") ||
            c.contains("bundleRelease".lowercase()) ||
            c.contains("aapt2") ||
            c.contains("bundletool")
    }

    fun isPackageManager(command: String): Boolean {
        val c = command.lowercase()
        return c.contains("apt-get") || c.contains("apt ") ||
            c.contains("dpkg") || c.contains("sdkmanager") ||
            c.contains("minis-dev-setup") || c.contains("minis-android-sdk-setup")
    }

    suspend fun <T> withCommandLock(command: String, block: suspend () -> T): T {
        return when {
            isApkBuild(command) -> apkLock.withLock { block() }
            isPackageManager(command) -> aptLock.withLock { block() }
            else -> block()
        }
    }

    suspend fun <T> withNamedLock(name: String, block: suspend () -> T): T {
        val mutex = named.getOrPut(name) { Mutex() }
        return mutex.withLock { block() }
    }
}
