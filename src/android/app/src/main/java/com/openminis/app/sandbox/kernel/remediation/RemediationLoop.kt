package com.openminis.app.sandbox.kernel.remediation

import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * The only kill authority for a stalled main thread. It does not run on that
 * thread, and it does not kill a protected terminal. A counted hang of 8s, or
 * two counted hangs, stops unprotected guests. The ceiling that used to drop
 * long gaps is not consulted here.
 */
object RemediationLoop {
    private const val TAG = "RemediationLoop"
    private const val STALL_MS = 3_000L
    private const val KILL_MS = 8_000L

    @Volatile private var started = false
    @Volatile private var stops = 0

    fun start(filesDir: File, killUnprotected: (String) -> Unit) {
        if (started) return
        started = true
        thread(name = "RemediationLoop", isDaemon = true) {
            var trips = 0
            var killed = false
            while (true) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val gap = StallSignal.gap()
                if (gap < STALL_MS) {
                    trips = 0
                    killed = false
                    continue
                }
                trips++
                // [T-a7-render-breaker-removed] 这里原本还调用
                // HangDetector.tripRenderBreaker()。上游 1.14 的
                // [T-android-hang-false-positive] 已把 render breaker 从看门狗与
                // 渲染器一起删掉（它会因为任何屏幕上的 3s 主线程间隙，把之后每条
                // 流式回复都降级，而那 33 个样本没有一个落在渲染里），渲染端也早已
                // 改成只看体积，那个信号已无读取方。本循环的降级仍由上一行的
                // DegradeFlags + 下面的 killUnprotected 完成，语义不变。
                DegradeFlags.trip(filesDir, "stall gap=${gap}ms trips=$trips")
                if (!killed && (trips >= 2 || gap >= KILL_MS)) {
                    killed = true
                    stops++
                    val reason = "remediation gap=${gap}ms trips=$trips"
                    Log.w(TAG, reason)
                    runCatching { killUnprotected(reason) }
                        .onFailure { Log.w(TAG, "kill failed: ${it.message}") }
                }
            }
        }
    }

    fun stopCount(): Int = stops
}
