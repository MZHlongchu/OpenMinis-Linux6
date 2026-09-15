package com.openminis.app.sandbox.offload

import android.content.Context
import android.util.Log
import com.openminis.app.data.RootPassThroughSettings
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RootPassThroughHandler(private val context: Context) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        val argv = request.argv.drop(1)

        if (argv.isEmpty() || argv[0] == "--help" || argv[0] == "-h" || argv[0] == "help") {
            return ok(HELP)
        }
        if (argv[0] == "--version") return ok("android-root-cli 1.0 (T-root-passthrough)")

        if (!RootPassThroughSettings.isEnabled()) {
            return errEnvelope(
                "PASSTHROUGH_DISABLED",
                "root passthrough is disabled. Enable it in Settings → Devstack → Root Passthrough. Risk: Agent can execute arbitrary su commands.",
                request,
            )
        }

        val sub = argv[0]
        val rest = argv.drop(1)

        return when (sub) {
            "exec", "run" -> handleExec(rest, request)
            "audit" -> handleAudit()
            else -> NativeOffloadResult(2, "android-root-cli: unknown subcommand '$sub'.\n$HELP")
        }
    }

    private fun handleExec(rest: List<String>, request: NativeOffloadRequest): NativeOffloadResult {
        if (rest.isEmpty()) {
            return errEnvelope("INVALID_ARGS", "exec requires a command. Usage: android-root-cli exec <command> [args...]", request)
        }

        val cmd = rest.joinToString(" ")
        val timeoutMs = request.env["ROOT_PASSTHROUGH_TIMEOUT"]?.toLongOrNull() ?: 120_000L

        auditLog(request, cmd, "exec")

        val proc = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()

        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val exited = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        val exitCode = if (!exited) {
            proc.destroy()
            auditLog(request, cmd, "timeout")
            return errEnvelope("ROOT_TIMEOUT", "su command timed out after ${timeoutMs}ms", request)
        } else proc.exitValue()

        auditLog(request, cmd, "done", exitCode)

        val preview = output.take(2048)
        val envelope = JSONObject()
            .put("ok", exitCode == 0)
            .put("exitCode", exitCode)
            .put("output", output)
            .put("outputPreview", preview)
            .put("truncated", output.length > 2048)

        return NativeOffloadResult(exitCode, OffloadOutput.formatBody(envelope.toString(2), OffloadArgs(request.argv.drop(1))) + "\n")
    }

    private fun handleAudit(): NativeOffloadResult {
        val auditDir = File(context.filesDir, "audit")
        val files = auditDir.listFiles { f -> f.name.startsWith("root-") && f.name.endsWith(".log") }
            ?: return ok("no audit logs found\n")

        val recent = files.sortedByDescending { it.lastModified() }.take(5)
        val sb = StringBuilder()
        for (f in recent) {
            sb.append("=== ${f.name} (${f.length()} bytes) ===\n")
            val lines = f.readLines().takeLast(20).joinToString("\n")
            sb.append(lines)
            sb.append("\n")
        }
        return ok(sb.toString())
    }

    private fun auditLog(request: NativeOffloadRequest, cmd: String, phase: String, exitCode: Int? = null) {
        try {
            val auditDir = File(context.filesDir, "audit")
            auditDir.mkdirs()
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val f = File(auditDir, "root-${date.format(Date())}.log")
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val obj = org.json.JSONObject()
                .put("ts", ts)
                .put("pid", request.pid)
                .put("cmd", cmd)
                .put("phase", phase)
                .put("sessionId", request.sessionId)
            exitCode?.let { obj.put("exit", it) }
            f.appendText(obj.toString() + "\n")
        } catch (t: Exception) {
            Log.w(TAG, "auditLog failed: ${t.message}")
        }
    }

    private fun ok(body: String) = NativeOffloadResult(0, body)

    private fun errEnvelope(code: String, message: String, request: NativeOffloadRequest): NativeOffloadResult {
        val args = OffloadArgs(request.argv.drop(1))
        val obj = JSONObject().put("ok", false)
            .put("error", JSONObject().put("code", code).put("message", message))
        return NativeOffloadResult(1, OffloadOutput.formatBody(obj.toString(2), args) + "\n")
    }

    companion object {
        private const val TAG = "RootPassThrough"
        private const val HELP = """android-root-cli — execute commands via su passthrough.

Usage:
  android-root-cli exec <command> [args...]
    Run a command as root via su -c.
  android-root-cli audit
    Show recent root execution audit logs.

Environment:
  ROOT_PASSTHROUGH_TIMEOUT — max wait in ms (default 120000)

Examples:
  android-root-cli exec ls /system
  android-root-cli exec id
  android-root-cli audit
"""
    }
}
