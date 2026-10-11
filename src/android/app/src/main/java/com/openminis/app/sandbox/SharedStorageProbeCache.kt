package com.openminis.app.sandbox

/**
 * [T-b4-#6] `/sdcard` 绑定可用性探测的缓存。
 *
 * 每条 `ExecutionCoordinator.execute` 的挂载陈旧检测都会走到
 * `PRootKernel.sharedStoragePlan`，那里原先无条件执行
 * `Environment.isExternalStorageManager()`（appops / 权限查询）+ `/storage/emulated/0`
 * 的 `canRead()` stat——挂载集合一个字节都没变时，这就是每条命令的固定开销。
 *
 * 故意不加 TTL：只在已知的时点失效（[PRootKernel.applyMountedFoldersSnapshot]——
 * 启动 / 开机 / 挂载 CRUD——以及应用回到前台，见 `MinisApp`）。缓存住一个过期的
 * "已授权"是无害的：真正的访问权限仍由内核 / FUSE 视图把关；缓存住一个过期的
 * "未授权"却会让 `/sdcard` 被摘掉（offload 路径刚授权就是这种情况），所以负数命中
 * 额外做一次实时权限复检，见 [resolve] 的 [T-b4-#6-negative]。
 */
internal object SharedStorageProbeCache {

    /** 探测结果：宿主共享存储根目录（可能为 null）+ 当前是否真的可读。 */
    class Probe(val host: String?, val granted: Boolean)

    @Volatile
    private var cached: Probe? = null

    /**
     * 命中缓存直接返回，否则探测一次并写入。并发的首次调用可能探测两次，但两次结果
     * 等价（探测本身无副作用），因此不需要加锁。
     *
     * [T-b4-#6-negative] "未授权"的答案不能无条件缓存住。缓存原先只在 [invalidate]
     * 的那几个入口失效（挂载 CRUD / 应用回到前台），于是 offload 路径上用户答完系统
     * 权限弹窗（`OffloadPermissionManager.requestAndroidPermission` → UI 应答）之后，
     * 缓存里仍是 `granted = false`：`/sdcard` 会被 [SharedStorageBindPlan] 一直摘掉，
     * 直到应用下一次回到前台才恢复——授权成功了却看不见共享存储。
     *
     * 所以负数命中要先做一次**廉价**的权限复检 [permissionGranted]（appops /
     * checkSelfPermission 级别，**不含** `canRead()` stat）：权限已经给了就强制重探，
     * 权限还没给才继续用缓存值。稳态（已授权）下仍然是零探测；只有"当前确实没权限"
     * 的状态每条命令多一次权限位查询——而那正是最需要立刻发现授权的状态。
     */
    fun resolve(permissionGranted: () -> Boolean, probe: () -> Probe): Probe {
        cached?.let { hit ->
            if (hit.granted || !permissionGranted()) return hit
        }
        return probe().also { cached = it }
    }

    fun invalidate() {
        cached = null
    }
}
