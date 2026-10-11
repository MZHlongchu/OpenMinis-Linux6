package com.openminis.app.scheduled

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray

/**
 * [T-android-scheduled-tasks-design] SharedPreferences-backed JSON array of
 * [ScheduledTask] rows. Same pattern as [com.openminis.app.offload.AlarmOffloadManager]
 * — small dataset, low write frequency, no Room migration cost.
 */
class ScheduledTaskStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun all(): List<ScheduledTask> {
        val raw = prefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    runCatching { ScheduledTask.fromJson(o) }
                        .onSuccess { add(it) }
                        .onFailure { AppLogger.warning(TAG, "skip malformed row: ${it.message}") }
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "load failed: ${t.message}")
            emptyList()
        }
    }

    fun get(taskId: String): ScheduledTask? = all().firstOrNull { it.id == taskId }

    // [T-android-scheduled-fire-claim] Every write is a read-modify-write of
    // the WHOLE list, and writers run on several threads (the alarm receiver,
    // a run's completion on IO, the task screens, the CLI). Unserialised, two
    // writes that overlap lose one of them — a count or anchor that just moved
    // silently moves back, and an interval then overshoots its --count.
    // The write below is commit(), i.e. synchronous: the in-memory map is
    // updated before it returns, so the next read under the lock already sees
    // the previous write (and the file is durable, so a crash cannot resurrect
    // a row a delete just removed).
    fun upsert(task: ScheduledTask) {
        mutate { cur ->
            cur.removeAll { it.id == task.id }
            cur.add(task)
        }
    }

    /**
     * Removes the row and reports whether it is *actually* gone afterwards.
     * [T-android-scheduled-fire-claim] The post-check makes the success signal
     * truthful: callers (the CLI's `cron rm`, the task screen) branch on the
     * return value, and a write that silently failed must not look like a
     * successful delete.
     */
    fun delete(taskId: String): Boolean {
        var removed = false
        mutate { cur ->
            removed = cur.removeAll { it.id == taskId }
        }
        return removed && get(taskId) == null
    }

    /** Exact id, or a unique prefix of at least 4 characters. */
    fun resolveId(idOrPrefix: String): String? {
        val tasks = all()
        tasks.find { it.id == idOrPrefix }?.let { return it.id }
        if (idOrPrefix.length < 4) return null
        return tasks.filter { it.id.startsWith(idOrPrefix) }.singleOrNull()?.id
    }

    /**
     * [T-android-scheduled-fire-claim] Atomic read-modify-write of one row:
     * [transform] sees the row as stored right now and returns its new value,
     * or null to leave it unchanged. Returns what was written (null when the
     * row is missing or nothing was written).
     */
    fun update(taskId: String, transform: (ScheduledTask) -> ScheduledTask?): ScheduledTask? = synchronized(LOCK) {
        val tasks = all()
        val cur = tasks.firstOrNull { it.id == taskId } ?: return null
        val next = transform(cur) ?: return null
        write(tasks.map { if (it.id == taskId) next else it })
        next
    }

    /**
     * [T-android-scheduled-fire-claim] Serialised read-modify-write used by
     * every whole-list mutation above. The lock is process-wide ([LOCK], not a
     * per-instance field): stores are constructed per call site — the provider
     * repository, the session list, the manager, the CLI tools — and they all
     * mutate the same prefs file, so a per-instance monitor would not exclude
     * anything.
     */
    private fun mutate(block: (MutableList<ScheduledTask>) -> Unit) {
        synchronized(LOCK) {
            val cur = all().toMutableList()
            block(cur)
            write(cur)
        }
    }

    /** Null model pins that name entries removed by a provider model-list clear. */
    fun dropEntryRefs(removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        mutate { cur ->
            for (i in cur.indices) {
                val task = cur[i]
                val binding = task.modelBinding
                val bindingHit = binding != null && removedIds.any { it.isNotEmpty() && binding.contains(it) }
                val modelHit = task.modelId != null && task.modelId in removedIds
                if (!bindingHit && !modelHit) continue
                cur[i] = task.copy(
                    modelId = if (modelHit) null else task.modelId,
                    modelBinding = if (bindingHit) null else binding,
                )
            }
        }
    }

    fun clear() {
        prefs.edit().remove(KEY_TASKS).commit()
    }

    private fun write(tasks: List<ScheduledTask>) {
        val arr = JSONArray()
        for (t in tasks) arr.put(t.toJson())
        // apply() returns before the file is durable. A later delete then looked
        // successful while a stale flush restored the task under a new id.
        prefs.edit().putString(KEY_TASKS, arr.toString()).commit()
    }

    /**
     * Cold flow that emits the current task list whenever the prefs file
     * changes. Used by [ScheduledTasksViewModel] to keep the list screen
     * live.
     */
    fun observe(): Flow<List<ScheduledTask>> = callbackFlow {
        trySend(all())
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_TASKS || key == null) {
                trySend(all())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        private const val TAG = "ScheduledTaskStore"

        /** Process-wide: stores are constructed per call site, the file is one. */
        private val LOCK = Any()
        private const val PREFS_NAME = "minis_scheduled_tasks_prefs"
        private const val KEY_TASKS = "tasks_json"
    }
}
