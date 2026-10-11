package com.openminis.app.sandbox

import kotlinx.coroutines.runBlocking

/**
 * [T-a11y-cli-guest] Host-side tools must not run `android-*` CLIs through
 * `ProcessBuilder("/bin/bash", …)`: stock Android has no `/bin/bash` in the app
 * process (the app is not a Linux userland), and every `android-*` CLI
 * (`android-a11y-cli`, `android-shizuku-cli`, `android-open`, …) is only a shim
 * inside the PRoot **guest** rootfs — `/usr/local/bin/<name>` talking to the
 * host implementation over [NativeOffloadServer]'s abstract socket. So the
 * guest shell is the only execution domain where these commands exist.
 *
 * Same shape as `McpToolDispatcher.executeInGuest`, shared so the agent tools
 * do not each re-implement the bridge.
 *
 * The command runs in the session's persistent shell
 * ([ExecutionCoordinator.execute]), which sources `/etc/minis-offload-exit.sh`
 * and therefore resolves `<name>` to a wrapper that strips the
 * `__MINIS_OFFLOAD_RC=` marker and returns the CLI's REAL exit code. Callers
 * can and should judge success by `exitCode == 0` alone — do not sniff the
 * output text (a successful `ui dump` legitimately contains arbitrary
 * on-screen text).
 */
object GuestScriptRunner {
    /**
     * `ExecutionCoordinator.execute`'s `timeout` is advisory today (the armed
     * wall clock comes from `BudgetClassifier.classify(command)`); it is passed
     * through so the intent stays visible at the call site. 30s matches the MCP
     * channel's `executeInGuest(script, 30)` and sits below the a11y CLI's own
     * 75s offload deadline.
     */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    data class GuestResult(val output: String, val exitCode: Int)

    suspend fun run(
        sessionId: String,
        script: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): GuestResult {
        val r = ExecutionCoordinator.execute(
            sessionId = sessionId,
            command = script,
            timeout = timeoutMs,
        )
        return GuestResult(r.output, r.exitCode)
    }

    /**
     * Sync entry for the few call sites on a non-suspending API (a11y skill
     * replay). They already run on a background thread; `runBlocking` is the
     * established pattern in this repo for bridging coroutine APIs into them
     * (see `McpToolDispatcher.executeInGuest`, `ExecuteCodeTool.Bridge`).
     */
    fun runSync(
        sessionId: String,
        script: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): GuestResult = runBlocking { run(sessionId, script, timeoutMs) }
}
