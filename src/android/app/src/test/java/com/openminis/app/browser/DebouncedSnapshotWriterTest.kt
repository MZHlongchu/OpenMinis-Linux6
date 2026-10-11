package com.openminis.app.browser

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [T-browser-save-single-writer-android] B6 复核回归的两条都在
 * [DebouncedSnapshotWriter] 上验证（BrowserHistoryStore 与 BrowserTabPool 都用它）：
 * ①debounce 窗口里到达的快照不丢更新；②写盘串行，flush（clear/dispose 的最终
 * 快照）等在飞的写者之后、且是最后一个写者。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebouncedSnapshotWriterTest {

    @Test
    fun snapshotRequestedWhileWritingIsNotLost() = runTest {
        val written = mutableListOf<String>()
        lateinit var writer: DebouncedSnapshotWriter<String>
        var nested = false
        writer = DebouncedSnapshotWriter<String>(
            this, StandardTestDispatcher(testScheduler), 500L,
        ) { snapshot ->
            written += snapshot
            if (!nested) {
                nested = true
                // 复现旧实现的丢更新窗口：job 已越过 debounce、正在写旧快照，此刻
                // 又来一次 request —— 旧代码只置 pending 并看到 job 仍 active 就返回，
                // job 写完旧快照便收尾，于是这份快照永远没人落盘。
                writer.request("B")
            }
        }

        writer.request("A")
        advanceUntilIdle()

        assertEquals(listOf("A", "B"), written)
    }

    @Test
    fun requestsWithinTheDebounceWindowCoalesceIntoOneWrite() = runTest {
        val written = mutableListOf<String>()
        val writer = DebouncedSnapshotWriter<String>(
            this, StandardTestDispatcher(testScheduler), 500L,
        ) { written += it }

        writer.request("A")
        runCurrent()
        advanceTimeBy(200)
        writer.request("B")
        advanceUntilIdle()

        assertEquals(listOf("B"), written)
    }

    @Test
    fun flushSupersedesThePendingSnapshotAndStaysLast() = runTest {
        val written = mutableListOf<String>()
        val writer = DebouncedSnapshotWriter<String>(
            this, StandardTestDispatcher(testScheduler), 500L,
        ) { written += it }

        writer.request("stale")
        runCurrent()
        writer.flush("final")
        advanceUntilIdle()

        assertEquals(listOf("final"), written)
    }

    @Test
    fun flushWaitsForTheInFlightWriteInsteadOfWritingConcurrently() {
        val overlapped = AtomicBoolean(false)
        val inWrite = AtomicBoolean(false)
        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val flushReturned = CountDownLatch(1)
        val written = Collections.synchronizedList(mutableListOf<String>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val writer = DebouncedSnapshotWriter<String>(scope, Dispatchers.IO, 1L) { snapshot ->
            if (!inWrite.compareAndSet(false, true)) overlapped.set(true)
            written += snapshot
            if (snapshot == "slow") {
                writeStarted.countDown()
                releaseWrite.await(10, TimeUnit.SECONDS)
            }
            inWrite.set(false)
        }

        try {
            writer.request("slow")
            assertTrue("debounce write never started", writeStarted.await(10, TimeUnit.SECONDS))

            val flusher = Thread {
                writer.flush("final")
                flushReturned.countDown()
            }
            flusher.isDaemon = true
            flusher.start()
            // 在飞的写者还卡着：flush 必须等它，而不是并发写下去。旧实现没有写锁，
            // flush 会立刻写完返回 —— 同一份 JSON 两个并发写者。
            assertFalse("flush overlapped an in-flight write", flushReturned.await(200, TimeUnit.MILLISECONDS))

            releaseWrite.countDown()
            assertTrue("flush never returned", flushReturned.await(10, TimeUnit.SECONDS))
            flusher.join(10_000)

            assertEquals("final", written.last())
            assertFalse("two writers ran at once", overlapped.get())
        } finally {
            releaseWrite.countDown()
            scope.cancel()
        }
    }
}
