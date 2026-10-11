package com.openminis.app.data.body

import java.util.concurrent.atomic.AtomicLong

/**
 * One absolute budget shared by chat, export, backup, fork, search,
 * evolution, logs, network responses, and native entry points.
 * [onTrimMemory] may release reservations and refuse new work. It is not
 * the size ceiling.
 */
object Admission {
    private val remaining = AtomicLong(ResourceLimits.ADMIT_BUDGET_BYTES)

    class Refused(val bytes: Long) : IllegalStateException("admission refused: $bytes")

    inline fun <T> nativeCall(text: String, block: () -> T): T {
        val bytes = text.length.toLong() * 2
        if (!tryAdmit(bytes)) throw Refused(bytes)
        try {
            return block()
        } finally {
            release(bytes)
        }
    }

    inline fun <T> occupy(bytes: Long, block: () -> T): T {
        if (!tryAdmit(bytes)) throw Refused(bytes)
        try {
            return block()
        } finally {
            release(bytes)
        }
    }

    fun tryAdmit(bytes: Long): Boolean {
        if (bytes < 0) return false
        if (bytes == 0L) return true
        while (true) {
            val now = remaining.get()
            if (now < bytes) return false
            if (remaining.compareAndSet(now, now - bytes)) return true
        }
    }

    fun release(bytes: Long) {
        if (bytes <= 0) return
        while (true) {
            val now = remaining.get()
            val next = (now + bytes).coerceAtMost(ResourceLimits.ADMIT_BUDGET_BYTES)
            if (remaining.compareAndSet(now, next)) return
        }
    }

    /**
     * [T-admission-not-empty] 先等一下再试一次。
     *
     * 进程级预算只是并发提示，**不是**「这次操作没有数据」：
     * [com.openminis.app.data.repository.ChatRepository.loadBoundedMessageRange] 的
     * 注释记着这个教训——把一次暂时拒绝当成空结果，让 loadSession 清空两端游标、
     * 把长会话的中段永久搁浅。所以本方法返回 false 时，调用方必须继续用**有界**的
     * 查询/逐页读，绝不能返回空结果或静默截断。
     */
    suspend fun awaitAdmit(
        bytes: Long,
        waitMillis: Long = 250L,
        stepMillis: Long = 50L,
    ): Boolean {
        if (tryAdmit(bytes)) return true
        var waited = 0L
        while (waited < waitMillis) {
            kotlinx.coroutines.delay(stepMillis)
            waited += stepMillis
            if (tryAdmit(bytes)) return true
        }
        return false
    }

    fun remaining(): Long = remaining.get()

    /** Drop cached reservations. Does not raise the absolute ceiling. */
    fun onTrim() {
        remaining.set(ResourceLimits.ADMIT_BUDGET_BYTES)
    }

    internal fun resetForTests() {
        remaining.set(ResourceLimits.ADMIT_BUDGET_BYTES)
    }
}
