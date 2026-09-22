package com.openminis.app.sandbox

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-session locks so parallel sub-agents / chats cannot deadlock the
 * shared PRoot guest (one gradle daemon, one SDK tree, one apt dpkg lock).
 */
object SandboxResourceGate {
    private val apkLock = Mutex()
    /**
     * Shared with [RootfsManager] so boot-time `minis-mirror` / dpkg-world
     * restore cannot race an agent `apt-get` / `minis-dev-setup` on the guest
     * dpkg lock files.
     */
    val aptMutex = Mutex()
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
            c.contains("minis-dev-setup") || c.contains("minis-android-sdk-setup") ||
            c.contains("minis-mirror")
    }

    /**
     * Wrap a command with resource locks to serialize conflicting operations.
     * 
     * APK builds and package managers (apt/dpkg/sdkmanager/minis-dev-setup)
     * are serialized to prevent dpkg lock conflicts. Shell commands that don't
     * touch package state run concurrently.
     * 
     * Lock timeout: apt/dpkg commands have a 5-minute timeout. If the lock
     * cannot be acquired within that time, the operation fails with a clear
     * error message instead of hanging indefinitely.
     */
    suspend fun <T> withCommandLock(command: String, block: suspend () -> T): T {
        return when {
            isApkBuild(command) -> apkLock.withLock { block() }
            isPackageManager(command) -> {
                try {
                    withTimeout(5 * 60 * 1000L) { // 5 minutes
                        aptMutex.withLock { block() }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw RuntimeException(
                        "apt/dpkg is busy for 5+ minutes (likely minis-dev-setup or long apt-get). " +
                        "Command aborted to prevent deadlock. Kill the apt process and retry.", e
                    )
                }
            }
            else -> block()
        }
    }

    suspend fun <T> withNamedLock(name: String, block: suspend () -> T): T {
        val mutex = named.getOrPut(name) { Mutex() }
        return mutex.withLock { block() }
    }
}
