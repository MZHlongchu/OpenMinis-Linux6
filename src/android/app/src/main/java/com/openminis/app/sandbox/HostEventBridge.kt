package com.openminis.app.sandbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Reverse host→sandbox event channel. Complements [HostStatusPublisher]:
 * status JSON is the snapshot; `/run/android-events.jsonl` is the live feed
 * plus optional `minis-on-event` hooks.
 */
object HostEventBridge {

    private const val TAG = "HostEventBridge"
    const val BATTERY_LOW_PCT = 15
    const val BATTERY_OK_PCT = 20

    private val started = AtomicBoolean(false)
    private val lastBattery = AtomicInteger(-1)
    private val lastOnline = AtomicReference<Boolean?>(null)
    private val lastIdle = AtomicReference<Boolean?>(null)
    private var rootfsDir: File? = null
    private var app: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context, rootfs: File) {
        rootfsDir = rootfs
        app = context.applicationContext
        if (!started.compareAndSet(false, true)) return
        val app = this.app ?: return
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky = app.registerReceiver(batteryReceiver, batteryFilter)
        sticky?.let { onBattery(app, it, heartbeat = true) }
        app.registerReceiver(idleReceiver, IntentFilter(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED))
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.registerDefaultNetworkCallback(networkCallback)
    }

    fun emit(type: String, extras: JSONObject = JSONObject()) {
        val root = rootfsDir ?: return
        val event = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("type", type)
        extras.keys().forEach { event.put(it, extras.get(it)) }
        appendJsonl(File(root, "run"), "android-events.jsonl", event)
        HostEventHooks.dispatch(type)
        app?.let { HostStatusPublisher.writeOnce(it, root) }
    }

    private fun appendJsonl(runDir: File, name: String, event: JSONObject) {
        runDir.mkdirs()
        val file = File(runDir, name)
        try {
            if (file.length() > 256 * 1024) {
                val keep = file.readLines().takeLast(80).joinToString("\n")
                file.writeText(if (keep.isBlank()) "" else "$keep\n")
            }
            file.appendText(event.toString() + "\n")
        } catch (t: Throwable) {
            Log.w(TAG, "append failed: ${t.message}")
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onBattery(context, intent, heartbeat = false)
        }
    }

    private fun onBattery(context: Context, intent: Intent, heartbeat: Boolean) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val pct = (level * 100) / scale
        val prev = lastBattery.getAndSet(pct)
        if (heartbeat || prev < 0) {
            HostStatusPublisher.writeOnce(context, rootfsDir ?: return)
            return
        }
        if (prev > BATTERY_LOW_PCT && pct <= BATTERY_LOW_PCT) {
            emit("battery_low", JSONObject().put("percent", pct))
        } else if (prev < BATTERY_OK_PCT && pct >= BATTERY_OK_PCT) {
            emit("battery_ok", JSONObject().put("percent", pct))
        }
    }

    private val idleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val idle = pm.isDeviceIdleMode
            val prev = lastIdle.getAndSet(idle)
            if (prev != null && prev != idle) {
                emit(if (idle) "doze_enter" else "doze_exit")
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val prev = lastOnline.getAndSet(true)
            if (prev == false) emit("network_available")
        }

        override fun onLost(network: Network) {
            val prev = lastOnline.getAndSet(false)
            if (prev == true) emit("network_lost")
        }
    }

    fun launch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }
}
