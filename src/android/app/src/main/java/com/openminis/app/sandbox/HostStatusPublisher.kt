package com.openminis.app.sandbox

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes `/run/minis-host-status.json` inside the Ubuntu rootfs so guest
 * scripts can `cat` host extras (temperature, free storage, fg/bg, wakelock)
 * without walking `ubuntu-rootfs` and without a NativeOffload round-trip.
 *
 * Refreshed every [INTERVAL_MS]. Uses StatFs only — never recursive dirSize.
 */
object HostStatusPublisher {

    private const val TAG = "HostStatusPublisher"
    private const val INTERVAL_MS = 30_000L
    private const val RELATIVE_PATH = "run/minis-host-status.json"

    private val started = AtomicBoolean(false)
    private var job: Job? = null

    fun guestPath(): String = "/$RELATIVE_PATH"

    fun start(context: Context, rootfsDir: File) {
        writeOnce(context, rootfsDir)
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                delay(INTERVAL_MS)
                writeOnce(app, rootfsDir)
            }
        }
    }

    fun writeOnce(context: Context, rootfsDir: File) {
        if (!rootfsDir.isDirectory) return
        val json = snapshot(context).toString()
        val runDir = File(rootfsDir, "run").also { it.mkdirs() }
        val target = File(runDir, "minis-host-status.json")
        val tmp = File(runDir, "minis-host-status.json.tmp")
        try {
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                target.writeText(json)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "write failed: ${t.message}")
        }
    }

    fun snapshot(context: Context): JSONObject {
        val json = JSONObject()
        json.put("timestamp_ms", System.currentTimeMillis())
        json.put("app_foreground", (context.applicationContext as? MinisApp)?.isAppForeground() == true)
        json.put("wakelock_held", AgentForegroundService.wakeLockHeld)
        json.put("offload_handlers", JSONArray(NativeOffloadServer.registeredHandlers.sorted()))

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val pct = if (level >= 0) (level * 100) / scale else JSONObject.NULL
            json.put("battery_percent", pct)
            val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != Int.MIN_VALUE) {
                val c = tenths / 10.0
                if (c in -20.0..80.0) json.put("temperature_celsius", c)
                else json.put("temperature_celsius_raw", tenths)
            }
        }

        try {
            val s = StatFs(Environment.getDataDirectory().path)
            json.put("storage_free_bytes", s.availableBytes)
            json.put("storage_total_bytes", s.totalBytes)
        } catch (t: Throwable) {
            json.put("storage_error", t.message ?: "StatFs unavailable")
        }
        return json
    }
}
