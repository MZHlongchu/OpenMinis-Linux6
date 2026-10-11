package com.openminis.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.openminis.app.util.IsoTime
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Main-thread hang watchdog.
 *
 * Posts a heartbeat task to the main looper at a fixed cadence and waits on a
 * background thread for it to fire. If the heartbeat fails to land within
 * [HANG_THRESHOLD_MS], the main thread is considered hung — capture its stack
 * trace, append a record to a daily `stall-<date>.log` file under the app's
 * logs/ dir, increment a persisted hang counter, and continue.
 *
 * After [HANG_LIMIT_FOR_BREAKER] hangs accumulate the [shouldForceHomeOnLaunch]
 * gate flips true. AppNavigation reads it on cold start and overrides the
 * user's "open last session" / "open new chat" launch preference to "open
 * home" (mode 3) so the user isn't trapped in a loop where every cold start
 * lands on a session that hangs the UI again.
 *
 * The counter resets when the user successfully runs a chat session for
 * [RESET_AFTER_QUIET_MS] without another hang firing — see [markHealthyTick].
 *
 * [T-android-hang-false-positive] Gaps are measured on the MONOTONIC uptime
 * clock, the same clock the heartbeat's postDelayed runs on. The wall clock
 * used before kept running through deep sleep and jumped on time sync, so a
 * phone waking up (09-25: "41509s") or correcting its clock after boot
 * (09-28: "16521299s", main thread idle in nativePollOnce) was recorded and
 * COUNTED as a hang. Two more guards keep a false positive out of the count:
 * a main thread found idle at trip time, and a gap no real hang can reach.
 *
 * [T-a7-hang-clock] 上面这段在合并后一度只是"文档"：本文件自己的 nowElapsed()
 * 返回的是 SystemClock.elapsedRealtime()（含深睡时间），而 postDelayed 与
 * 上游用的是 SystemClock.uptimeMillis()（不含深睡）。两者混用会把一次深睡
 * 算成 41509s 的"间隙"，正是这里要修掉的假阳性。已按上游统一回
 * SystemClock.uptimeMillis()：进程被冻结仍会体现在该时钟上（冻结闸门照常
 * 生效），只有深睡不再计入。
 *
 * No iOS counterpart yet (intentional — iOS only has the DEBUG-mode RPC hang
 * detector at src/ios/Debug/DebugRPCHangDetector.swift; this is a production
 * circuit breaker).
 */
object HangDetector {

    private const val TAG = "HangDetector"

    /** Heartbeat cadence — how often the watchdog pings the main thread. */
    private const val HEARTBEAT_INTERVAL_MS = 1_000L

    /** A hang fires once the main thread has missed a heartbeat for this long. */
    private const val HANG_THRESHOLD_MS = 3_000L

    /**
     * [T-android-hangdetector-midhang-sample] While a hang episode is still
     * ongoing, re-sample the main-thread stack this often. Multiple samples
     * across one long hang show whether the thread is stuck in ONE frame
     * (a single blocking call) or churning through related frames (a loop)
     * — and guarantee samples land while the work is actually on the stack.
     * Replaces the old MIN_GAP_BETWEEN_LOGS_MS single-shot dedupe, whose one
     * trip-time snapshot was frequently an idle stack (background process
     * freezes resume with a huge heartbeat gap but an already-idle main
     * thread — that's why historical stall logs were full of
     * nativePollOnce frames).
     */
    private const val MID_HANG_RESAMPLE_MS = 3_000L

    /**
     * [T-android-hangdetector-full-dump] Episodes at or above this duration
     * also persist a full all-threads dump (names + states + stacks). The
     * 19:48 episode on 2026-10-05 lasted 62 minutes with the main thread
     * parked in nativePollOnce — its stack shows WHERE it is waiting but
     * not WHAT it is waiting on (a lock, a binder call, a frozen IO
     * thread); the holder only appears in the full dump. 15s is comfortably
     * above any GC/sync hiccup and well below the episodes that motivated
     * this.
     */
    private const val FULL_DUMP_THRESHOLD_MS = 15_000L

    /** Once `count >= this`, AppNavigation forces launch mode = home. */
    private const val HANG_LIMIT_FOR_BREAKER = 3

    /**
     * [T-android-hangdetector-freeze-gate] Heartbeat gaps above this ceiling are
     * process-freeze / deep-sleep artifacts, not live ANRs: a genuinely stuck
     * main thread is killed by the system (or by the 600s read watchdog upstream)
     * long before a gap this large can accumulate, and 6/6 recorded stall episodes
     * (48s, 6.6min, 63min, 11.4min) were all cached-app-freezer resumes — single
     * mid-hang sample, post-recovery idle ~0.5s later, zero escalation re-samples.
     * Such episodes are still LOGGED (the thaw stack is diagnostic gold) but no
     * longer COUNTED, so an overnight freeze cannot trip the render breaker (>=2)
     * or the force-home breaker (>=3).
     */
    private const val FREEZE_GAP_CEILING_MS = 30_000L

    // [T-a7-render-breaker-removed] 上游 1.14 的 [T-android-hang-false-positive]
    // 把"按全局 hang 计数降级流式渲染"的 render breaker 从看门狗与渲染器一起
    // 删掉了：它会在任何屏幕的任何 3s 主线程间隙（首页等锁、手机深睡醒来）之后
    // 把后续每一条流式回复都降级，简单回复也不例外，而记录到的 33 个样本没有
    // 一个落在流式渲染里。合并时渲染端（LargeContentGuard）已经改成只看体积，
    // 看门狗侧的计数阈值常量与那个布尔状态却残留下来，成了一个没有任何读取方的
    // 信号（RemediationLoop 只是往里写）。这里随上游一并删除；沙盒侧的降级/止损
    // 仍由 RemediationLoop 的 DegradeFlags + kill 路径负责，不受影响。

    /**
     * [T-android-hang-false-positive] A heartbeat gap longer than this is not a
     * hang the watchdog can have observed: the system ANR-kills a main thread
     * blocked for ~5 s of input, and the longest real stall on record here is
     * ~33 s. Anything this long is the process having been frozen or
     * suspended as a whole (cached-app freezer, debugger), so it is logged and
     * not counted.
     */
    private const val MAX_PLAUSIBLE_GAP_MS = 5 * 60_000L

    /** Other app threads written per episode, and frames per thread. */
    private const val PEER_THREAD_LIMIT = 16
    private const val PEER_STACK_DEPTH = 30

    /** Quiet period (no hang firing) after which the count resets. */
    private const val RESET_AFTER_QUIET_MS = 10_000L

    private const val PREFS_NAME = "hang_detector_prefs"
    private const val KEY_HANG_COUNT = "hang_count"
    private const val KEY_LAST_HANG_AT = "last_hang_at_ms"

    private const val STALL_LOG_DIR = "logs"
    private const val STALL_LOG_PREFIX = "stall-"
    // [T-u12-merge] Upstream 1.14's caller-attribution block is adopted as-is:
    // a stall's JankDiag line now names the screen and the first app frame, and
    // STALL_STACK_DEPTH 25 → 120 is what lets a Compose text-layout stall leave
    // the framework frames. Its two SimpleDateFormat vals are deliberately NOT
    // adopted — the merged file carries no java.text/java.util imports, ours
    // already formats these stamps with IsoTime (see writeStallSample), and a
    // shared SimpleDateFormat is not thread-safe on a watchdog that runs beside
    // the main thread.
    /**
     * [T-android-hang-caller-attribution] Frames persisted per sample into
     * stall-<date>.log. Was 25, which is not enough to leave Compose's text
     * stack: StaticLayout → TextLayout → AndroidParagraph → MultiParagraph →
     * TextStringSimpleNode → LayoutNode measure/remeasure alone runs past 25,
     * so a text-layout stall never showed which UI laid the text out. A
     * 12.4 s stall on a user device (zzz, 2026-09-22 14:04:40) could not be
     * attributed to any composable for exactly this reason.
     */
    internal const val STALL_STACK_DEPTH = 120

    /** Package prefix that marks a frame as ours rather than framework. */
    internal const val APP_FRAME_PREFIX = "com.openminis.app."

    /**
     * [T-android-hang-caller-attribution] Navigation route of the screen on top,
     * published by MainActivity's back-stack collector. Written on the main
     * thread, read by the watchdog thread — hence @Volatile. A stall's
     * JankDiag line carries it so the daily log (the file users actually send)
     * says which screen hung without needing the separate stall log.
     */
    @Volatile
    var currentScreen: String = "unknown"
        private set

    fun noteScreen(route: String?) {
        currentScreen = route?.takeIf { it.isNotBlank() } ?: "unknown"
    }

    /**
     * First frame that belongs to this app, formatted `Class.method:line`, or
     * null when the whole stack is framework / library code.
     *
     * The top of a hung main thread is almost always framework (Paint,
     * LineBreaker, StaticLayout…) — true, but it names what was slow, not who
     * asked for it. The first app frame below it is the caller that matters.
     */
    internal fun firstAppFrame(stack: Array<StackTraceElement>): String? {
        val frame = stack.firstOrNull { it.className.startsWith(APP_FRAME_PREFIX) } ?: return null
        return "${frame.className.removePrefix(APP_FRAME_PREFIX)}.${frame.methodName}:${frame.lineNumber}"
    }

    /**
     * The compact attribution appended to every JankDiag line. Pure so it can
     * be tested on the JVM; [writeStallSample] supplies the live values.
     */
    internal fun attributionFields(screen: String, stack: Array<StackTraceElement>): String =
        " screen=$screen appFrame=${firstAppFrame(stack) ?: "none"}"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    /** Heartbeat ticks bumped by the main-thread side of each ping. */
    private val lastHeartbeatAt = AtomicLong(0L)
    private val lastLogAt = AtomicLong(0L)

    private var appContext: Context? = null

    /**
     * [T-android-mainthread-prefs-hang] Prefs wrapper used by [markHealthyTick],
     * the only HangDetector prefs touch on the MAIN thread (ChatScreen calls
     * it on a healthy cadence). A wedged filesystem on that first read would
     * re-trip the detector via the very recovery path that exists to clear
     * it. All other HangDetector prefs access runs on the watchdog thread or
     * cold-start paths, where the blocking read is acceptable.
     */
    private val asyncPrefs by lazy {
        com.openminis.app.crash.AsyncPrefs.create(
            appContext ?: throw IllegalStateException("HangDetector.start not called"),
            PREFS_NAME,
        )
    }

    /**
     * Set by the application after start. Diagnostics must not import the
     * sandbox package; the hook is how a counted hang kills a live guest
     * instead of only degrading markdown.
     */
    @Volatile var hasLiveWorkload: () -> Boolean = { false }
    @Volatile var onCountedHang: (count: Int, durationMs: Long) -> Unit = { _, _ -> }

    /** Start the watchdog. Idempotent; safe to call from MinisApp.onCreate(). */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        // [T-a7-hang-clock] nowElapsed() 与 postDelayed / 心跳写入端同一时钟
        // （SystemClock.uptimeMillis()，不含深睡）。合并后它一度被实现成
        // elapsedRealtime()，深睡时间会被算进心跳间隙 —— 那正是本文件要修掉的
        // 41509s 假阳性来源。
        lastHeartbeatAt.set(nowElapsed())
        scheduleHeartbeat()
        // Use a non-daemon thread so the watchdog isn't reaped while the app
        // is still alive but scheduled out. Daemon threads also die earlier
        // when the JVM is winding down, which can suppress the very stalls
        // we want to capture.
        thread(name = "HangDetector-watch", isDaemon = false) { watchLoop() }
        registerUnfreezeHeartbeatReset(context.applicationContext)
        // [T-HANG-DIAG] echo via stdout *and* logcat so the start banner
        // shows up regardless of whether the user has Settings → Logging
        // enabled. AppLogger replaces System.out with its file-writing
        // PrintStream when logging is on; when logging is off this still
        // surfaces under `adb logcat`. Same pattern is used by recordHang
        // so its output is also captured both ways.
        val banner = "[T-HANG-DIAG] HangDetector started: threshold=${HANG_THRESHOLD_MS}ms " +
            "interval=${HEARTBEAT_INTERVAL_MS}ms limit=$HANG_LIMIT_FOR_BREAKER"
        println(banner)
        Log.i(TAG, banner)
    }

    /**
     * Called by long-running healthy UI surfaces (e.g. ChatScreen) to confirm
     * the main thread has been responsive for [RESET_AFTER_QUIET_MS] since the
     * last hang. Cheap on the hot path — early-returns when the count is
     * already 0.
     */
    fun markHealthyTick() {
        if (appContext == null) return
        // [T-android-mainthread-prefs-hang] AsyncPrefs keeps this off the
        // disk on the main thread. Before the async load lands, getInt
        // returns 0 → early-return — the correct conservative answer (a
        // hang count written this process is mirrored in the in-memory
        // overrides, so a real count is never hidden).
        if (asyncPrefs.getInt(KEY_HANG_COUNT, 0) == 0) return
        val lastHangAt = asyncPrefs.getLong(KEY_LAST_HANG_AT, 0L)
        if (lastHangAt > 0 && System.currentTimeMillis() - lastHangAt < RESET_AFTER_QUIET_MS) return
        // [T-u12-merge] Ours is kept: this is the main-thread path, so the reset
        // goes through AsyncPrefs ([T-android-mainthread-prefs-hang]) instead of
        // a second getSharedPreferences handle — upstream's `prefs.edit()` here
        // also referenced a `prefs` local that does not exist in the merged
        // function.
        asyncPrefs.putInt(KEY_HANG_COUNT, 0)
        asyncPrefs.putLong(KEY_LAST_HANG_AT, 0L)
        Log.i(TAG, "hang count reset after quiet period")
    }

    /**
     * AppNavigation calls this on cold start. Returns true once the breaker
     * threshold is hit, asking the launch resolver to ignore the user's
     * "open last session / open new chat" preference and land on the home
     * screen instead — the only safe destination when the previous launches
     * have been hanging.
     */
    fun shouldForceHomeOnLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HANG_COUNT, 0) >= HANG_LIMIT_FOR_BREAKER
    }

    /** Manual reset (Settings → "Reset hang counter") — clears immediately. */
    fun resetHangCount(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HANG_COUNT, 0)
            .putLong(KEY_LAST_HANG_AT, 0L)
            .apply()
    }

    fun currentHangCount(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_HANG_COUNT, 0)

    // -- internals -----------------------------------------------------------

    /**
     * [T-a7-hang-clock] 与 [scheduleHeartbeat] 里 postDelayed 的计时基准、以及
     * [watchLoop] 读的必须是同一个时钟：SystemClock.uptimeMillis()（不含深睡）。
     * 合并后它一度被写成 elapsedRealtime()，深睡会被算成巨型心跳间隙。
     */
    private fun nowElapsed(): Long = SystemClock.uptimeMillis()

    /**
     * API 35+: when the UID is unfrozen, reset the heartbeat so the first
     * watchdog tick after thaw does not see a multi-minute gap. The 30s
     * freeze gate remains as a belt-and-suspenders for the race where
     * [watchLoop] runs before this callback.
     */
    private fun registerUnfreezeHeartbeatReset(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val myUid = Process.myUid()
            // Reflect so compileSdk stubs that omit the API 35 freeze
            // listener still compile; no-op if the method is missing.
            val listenerClass = Class.forName(
                "android.app.ActivityManager\$OnUidFrozenStateChangedListener",
            )
            val unfrozen = try {
                ActivityManager::class.java.getField("UID_FROZEN_STATE_UNFROZEN").getInt(null)
            } catch (_: Throwable) {
                2
            }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onUidFrozenStateChanged" && args != null && args.size >= 2) {
                    val uids = args[0] as IntArray
                    val states = args[1] as IntArray
                    for (i in uids.indices) {
                        if (uids[i] == myUid && states[i] == unfrozen) {
                            lastHeartbeatAt.set(nowElapsed())
                            Log.i(TAG, "UID unfrozen — heartbeat reset")
                        }
                    }
                }
                null
            }
            ActivityManager::class.java.getMethod(
                "addOnUidFrozenStateChangedListener",
                java.util.concurrent.Executor::class.java,
                listenerClass,
            ).invoke(am, context.mainExecutor, proxy)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to register freeze listener: ${t.message}")
        }
    }

    private fun scheduleHeartbeat() {
        mainHandler.postDelayed({
            // [T-a7-hang-clock] 与 postDelayed 的计时基准同源（上游 1.14 的
            // 字面写法）：写入端换成别的时钟就会凭空产生间隙。
            lastHeartbeatAt.set(SystemClock.uptimeMillis())
            scheduleHeartbeat()
        }, HEARTBEAT_INTERVAL_MS)
    }

    private fun watchLoop() {
        // [T-HANG-DIAG] one-line confirmation that the watchdog thread itself
        // actually entered its loop — distinct from start() which only proves
        // the thread was *spawned*.
        println("[T-HANG-DIAG] HangDetector watchLoop entered")
        var ticks = 0L
        // [T-android-hangdetector-midhang-sample] Episode state: a hang
        // episode starts when the heartbeat gap first crosses the threshold
        // and ends when a heartbeat lands again. The episode is COUNTED once
        // (breakers depend on count semantics) but SAMPLED repeatedly.
        var hangActive = false
        var lastSampleAt = 0L
        var escalation = 0
        var episodePeakSinceMs = 0L
        // [T-android-hang-false-positive] One log line per skipped gap, not one per tick.
        var skipLogged = false
        while (true) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            }
            ticks++
            // [T-a7-hang-clock] 上游 1.14 的字面写法：读的是 heartbeat 写入端与
            // postDelayed 同一个时钟 SystemClock.uptimeMillis()（不含深睡）。
            // 合并后这里一度用 nowElapsed()（当时是 elapsedRealtime，含深睡）——
            // 那才会让间隙带上深睡增量并记成假阳性。
            val now = SystemClock.uptimeMillis()
            val since = now - lastHeartbeatAt.get()
            com.openminis.app.sandbox.kernel.remediation.StallSignal.observe(since)
            // [T-HANG-DIAG] every 30 ticks (~15s) emit a liveness ping so we
            // can confirm the watchdog is alive even when nothing hangs.
            // Volume is intentionally tiny (~4 lines / minute).
            if (ticks % 30L == 0L) {
                println("[T-HANG-DIAG] HangDetector tick=$ticks sinceHeartbeat=${since}ms")
            }
            if (since < HANG_THRESHOLD_MS) {
                // [T-u12-merge] Both sides here: ours clears the sandbox stall
                // signal (the Linux guest's remediation hook), upstream's
                // skipLogged reset re-arms the one-line-per-skipped-gap
                // throttle used by the freeze-artifact branch below.
                com.openminis.app.sandbox.kernel.remediation.StallSignal.clear()
                skipLogged = false
                if (hangActive) {
                    // Episode over — the heartbeat landed. One labeled
                    // post-recovery snapshot closes the record (its stack is
                    // expectedly idle; it documents WHEN the thread came
                    // back and the episode's peak gap).
                    hangActive = false
                    writeStallSample("post-recovery", episodePeakSinceMs, escalation)
                    println(
                        "[T-HANG-DIAG] hang episode ENDED peak=${episodePeakSinceMs}ms midHangSamples=${escalation + 1}",
                    )
                }
                continue
            }
            if (!hangActive) {
                // [T-android-hang-false-positive] Not a hang: the process was
                // frozen or suspended as a whole, or the main thread is idle
                // and simply has not run the heartbeat yet. Logged, never
                // counted — a false positive in the count is what used to
                // trip the launch breaker and (until it was removed) degrade
                // every streaming reply.
                val notAHang = when {
                    since > MAX_PLAUSIBLE_GAP_MS -> "gap beyond ${MAX_PLAUSIBLE_GAP_MS}ms (process frozen/suspended)"
                    isIdleMainStack(mainThreadStack()) -> "main thread idle in the looper"
                    else -> null
                }
                if (notAHang != null) {
                    if (!skipLogged) {
                        skipLogged = true
                        println("[T-HANG-DIAG] heartbeat gap ${since}ms NOT counted: $notAHang")
                    }
                    continue
                }
                hangActive = true
                escalation = 0
                episodePeakSinceMs = since
                lastSampleAt = now
                lastLogAt.set(now)
                // [T-android-hangdetector-freeze-gate] Freeze/deep-sleep resumes
                // produce elapsedRealtime gaps of minutes; a live main-thread ANR
                // cannot survive that long. Log the episode, but only let it
                // feed the breakers when the gap is plausibly a real hang.
                val workloadLive = hasLiveWorkload()
                val sample = com.openminis.app.sandbox.GuestWorkloadPolicy.shouldSampleHang(
                    since,
                    FREEZE_GAP_CEILING_MS,
                    workloadLive,
                )
                if (!sample) {
                    println(
                        "[T-HANG-DIAG] freeze artifact: gap=${since}ms > ${FREEZE_GAP_CEILING_MS}ms — " +
                            "log sampling reduced; counter still increments",
                    )
                } else if (since > FREEZE_GAP_CEILING_MS) {
                    println(
                        "[T-HANG-DIAG] long gap=${since}ms counted: sandbox work is live",
                    )
                }
                recordHang(
                    durationMs = since,
                    counts = com.openminis.app.sandbox.GuestWorkloadPolicy.countsHang(
                        since,
                        FREEZE_GAP_CEILING_MS,
                        workloadLive,
                    ),
                    sample = sample,
                )
                continue
            }
            episodePeakSinceMs = maxOf(episodePeakSinceMs, since)
            if (now - lastSampleAt >= MID_HANG_RESAMPLE_MS) {
                lastSampleAt = now
                escalation++
                val sample = com.openminis.app.sandbox.GuestWorkloadPolicy.shouldSampleHang(
                    since,
                    FREEZE_GAP_CEILING_MS,
                    hasLiveWorkload(),
                )
                if (sample) writeStallSample("mid-hang", since, escalation)
            }
        }
    }

    /**
     * [T-android-hangdetector-midhang-sample] Capture the MAIN thread's stack
     * right now and persist it: full ~25 frames into stall-<date>.log, a
     * compact top-5 line into stdout/logcat (feeds the daily AppLogger file)
     * tagged [JankDiag] for grep. Thread.getStackTrace on a hung thread is
     * safe and cheap (VM suspends just that thread for the walk); no count /
     * breaker side effects — those live in [recordHang].
     */
    private fun writeStallSample(label: String, durationMs: Long, escalation: Int) {
        val ctx = appContext ?: return
        val mainStack = try {
            Looper.getMainLooper().thread.stackTrace
        } catch (t: Throwable) {
            arrayOf<StackTraceElement>()
        }
        val ts = IsoTime.formatLocalTimeMillis()
        val date = IsoTime.formatLocalDate()
        val builder = StringBuilder()
        builder.append(
            "===== HANG @ $ts (duration ~${durationMs}ms) sample=$label escalation=$escalation =====\n",
        )
        builder.append("thread: main screen=$currentScreen\n")
        for (frame in mainStack.take(STALL_STACK_DEPTH)) builder.append("  at $frame\n")
        builder.append("\n")

        val top5 = mainStack.take(5).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        // [T-android-content-perf-diag] Attach the currently-rendering large
        // message's structural fingerprint (if any) so a Matcher/Pattern stall
        // stack maps straight to "this message, this content shape" from the log.
        val renderFields = ContentDiag.currentRenderLogFields()
        // [T-android-hang-caller-attribution] Screen + first app frame, so the
        // daily log alone names who hung, not just which framework call was slow.
        val attribution = attributionFields(currentScreen, mainStack)
        println(
            "[T-HANG-DIAG][JankDiag] sample=$label escalation=$escalation duration=${durationMs}ms top5: $top5$renderFields$attribution",
        )

        // [T-android-hangdetector-full-dump] Main-thread stack alone cannot
        // identify a lock-holder or a wedged IO thread; on long hangs also
        // persist every thread's name/state/stack so the next stall log
        // names a suspect instead of "main idle in nativePollOnce".
        if (durationMs >= FULL_DUMP_THRESHOLD_MS) {
            builder.append("----- all threads @ $ts (escalation=$escalation) -----\n")
            val all = try {
                Thread.getAllStackTraces()
            } catch (t: Throwable) {
                emptyMap<Thread, Array<StackTraceElement>>()
            }
            for ((thread, stack) in all.toSortedMap(compareBy { it.name })) {
                builder.append("thread: ${thread.name} state=${thread.state} daemon=${thread.isDaemon}\n")
                for (frame in stack.take(12)) builder.append("  at $frame\n")
            }
            builder.append("\n")
        }

        try {
            val dir = File(ctx.filesDir, STALL_LOG_DIR).also { it.mkdirs() }
            val file = File(dir, "$STALL_LOG_PREFIX$date.log")
            FileWriter(file, /* append = */ true).use { it.write(builder.toString()) }
        } catch (t: Throwable) {
            val msg = "[T-HANG-DIAG] FAILED to write stall log: ${t.javaClass.simpleName}: ${t.message}"
            println(msg)
            Log.w(TAG, msg)
        }
    }

    private fun recordHang(durationMs: Long, counts: Boolean, sample: Boolean = true) {
        val ctx = appContext ?: return
        // [T-android-hangdetector-midhang-sample] The trip-time stack IS a
        // mid-hang sample (the heartbeat is 3s stale and the main thread is
        // still stuck); the watchdog keeps re-sampling every
        // MID_HANG_RESAMPLE_MS via writeStallSample while the episode lasts.
        // A freeze-sized gap still counts. Sampling is the only thing the
        // ceiling is allowed to reduce.
        if (sample) writeStallSample("mid-hang", durationMs, escalation = 0)
        if (!counts) return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newCount = prefs.getInt(KEY_HANG_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_HANG_COUNT, newCount)
            .putLong(KEY_LAST_HANG_AT, System.currentTimeMillis())
            .apply()
        // [T-u12-merge] 保留两侧：本移植把计数扇出到 app 的 hook（沙盒据此止损），
        // 上游补上 peer-thread dump —— 主线程卡在锁上时，只有它能点出持锁线程。
        val hook = onCountedHang
        thread(name = "HangDetector-breaker", isDaemon = true) {
            runCatching { hook(newCount, durationMs) }
                .onFailure { Log.w(TAG, "hang breaker hook failed: ${it.message}") }
        }
        // [T-android-hang-false-positive] Who else was running: a main thread
        // WAITING on a lock (09-24/09-25: 21 s in ProviderRepository.getInstances
        // -> synchronized(configLock)) never shows the thread holding it.
        writePeerThreads()
        val tail = "hang detected duration=${durationMs}ms count=$newCount " +
            "launchBreaker=${newCount >= HANG_LIMIT_FOR_BREAKER}"
        println("[T-HANG-DIAG] $tail")
        Log.w(TAG, tail)
    }

    // -- [T-android-hang-false-positive] helpers ----------------------------

    private fun mainThreadStack(): Array<StackTraceElement> = try {
        Looper.getMainLooper().thread.stackTrace
    } catch (t: Throwable) {
        arrayOf()
    }

    /**
     * True when the main thread is parked in the looper waiting for work, i.e.
     * not hung: the top frame is `MessageQueue.nativePollOnce`.
     */
    internal fun isIdleMainStack(stack: Array<StackTraceElement>): Boolean {
        val top = stack.firstOrNull() ?: return false
        return top.className == "android.os.MessageQueue" && top.methodName == "nativePollOnce"
    }

    /**
     * Append the stacks of the app's other threads to today's stall log: every
     * thread that is BLOCKED, plus any with an app frame on its stack, capped.
     * Once per hang episode, so the cost (one getAllStackTraces) is bounded.
     */
    private fun writePeerThreads() {
        val ctx = appContext ?: return
        try {
            val main = Looper.getMainLooper().thread
            val peers = Thread.getAllStackTraces().entries
                .filter { (t, st) ->
                    t !== main && st.isNotEmpty() &&
                        (t.state == Thread.State.BLOCKED || st.any { it.className.startsWith(APP_FRAME_PREFIX) })
                }
                .sortedBy { (t, _) -> if (t.state == Thread.State.BLOCKED) 0 else 1 }
                .take(PEER_THREAD_LIMIT)
            val b = StringBuilder("---- other app threads (${peers.size}) ----\n")
            for ((t, st) in peers) {
                b.append("thread: ${t.name} state=${t.state}\n")
                for (frame in st.take(PEER_STACK_DEPTH)) b.append("  at $frame\n")
            }
            b.append("\n")
            val dir = File(ctx.filesDir, STALL_LOG_DIR).also { it.mkdirs() }
            // [T-u12-merge] Was upstream's DATE_FORMAT.format(Date()); IsoTime is
            // the merged file's only formatter (already used for the JankDiag
            // stamp above) and needs no java.text/java.util imports.
            FileWriter(File(dir, "$STALL_LOG_PREFIX${IsoTime.formatLocalDate()}.log"), true)
                .use { it.write(b.toString()) }
        } catch (t: Throwable) {
            println("[T-HANG-DIAG] peer thread dump failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
