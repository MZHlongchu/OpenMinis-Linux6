package com.openminis.app.offload

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private const val BRIDGE_TAG = "PermissionDialog"

private typealias PermissionRequest = OffloadPermissionManager.AndroidPermissionRequest
private typealias PermissionResult = OffloadPermissionManager.AndroidPermissionResult

/**
 * [T-android-prompt-launch-ledger] 系统运行时权限对话框的**唯一驱动方**。
 *
 * 为什么必须只有一处、且必须挂在 Activity 一级：
 *  - 对话框回调拿不到 requestCode（见 [AndroidPermissionLaunchLedger]），身份只能自己记。
 *    旧实现有两个收集器（MainActivity 与 ChatScreen）会为同一张提示各弹一次系统对话框，
 *    后弹的那次覆写"这次结果属于哪张提示"的字段 —— 用户答 A、B 拿到 GRANTED；
 *  - 两处的判定口径还不一样：Activity 那份收 `all`，聊天页那份收 `any`（多权限里只要有一个
 *    已授予就回 GRANTED）。现在只剩 [AndroidPermissionOutcome.allGranted] 一处口径；
 *  - 触发方不只在聊天页：离屏的 `android-*` CLI（OffloadGate / OffloadHandler）同样会请求
 *    系统权限，所以驱动方挂在 Activity 上，而不是某个 Compose 页面里。
 */
internal class AndroidPermissionDialogBridge(private val activity: ComponentActivity) {

    private lateinit var launcher: ActivityResultLauncher<Array<String>>

    private val ledger = AndroidPermissionLaunchLedger<PermissionRequest, PermissionResult>(
        currentPrompt = { OffloadPermissionManager.pendingAndroidPermission.value },
        launch = { prompt -> launcher.launch(prompt.request.permissions.toTypedArray()) },
        deliver = { id, result -> OffloadPermissionManager.respondToAndroidPermission(id, result) },
        // launch 抛异常时按拒绝收口：调用方会退到"去设置页"那条流程，而不是白等一次超时。
        launchFailedResult = PermissionResult.DENIED,
    )

    /**
     * 注册系统对话框 launcher 并开始收集提示；必须在 `onCreate` 里调用（STARTED 之前）。
     *
     * [restoredInFlightId] 是 `onSaveInstanceState` 带回来的"在飞那次"的身份（没有就传 null，
     * 例如冷启动 / 进程死亡后重建），认领与否则由 [AndroidPermissionLaunchLedger.restore]
     * 与金库核对（对不上就不认领）。
     */
    fun register(restoredInFlightId: Long? = null) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { results ->
            ledger.onResult { request ->
                val granted = AndroidPermissionOutcome.allGranted(request.permissions, results) {
                    ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED
                }
                if (granted) PermissionResult.GRANTED else PermissionResult.DENIED
            }
        }
        // 必须在收集器启动之前认领：否则收集器会把同一张仍在等应答的提示再登记一次，
        // 在旧对话框的结果补投回来之前弹出第二个对话框。
        ledger.restore(restoredInFlightId ?: 0L)
        activity.lifecycleScope.launch {
            OffloadPermissionManager.pendingAndroidPermission.filterNotNull()
                .collect { pending -> drive(pending) }
        }
    }

    /** 供 `onSaveInstanceState` 用：在飞那次的身份（Activity 重建后靠它认领回调）。 */
    val inFlightPromptId: Long get() = ledger.inFlightPromptId

    /** 一张提示上屏前的预检，然后交给账本排队（一次只有一个在飞）。 */
    private fun drive(pending: PendingPrompt<PermissionRequest>) {
        val permissions = pending.request.permissions
        // 永久拒绝（对话框会是空操作）→ 直接回 DENIED，让调用方退到 in-app 的"去设置页"门。
        val permanentlyDenied = permissions.any { permission ->
            !granted(permission) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, permission) &&
                OffloadPermissionManager.hasAskedForPermission(activity, permission)
        }
        if (permanentlyDenied) {
            respond(pending.id, PermissionResult.DENIED)
            return
        }
        for (permission in permissions) {
            OffloadPermissionManager.markPermissionAsked(activity, permission)
        }
        // 全部已授予：弹出去只会闪一下空的系统对话框 —— 直接按"全部授予"回。判据是 all，
        // 不是旧聊天页那份 any（多权限请求里 any 会把"只给了一个"当成"全给了"）。
        if (permissions.isNotEmpty() && permissions.all { granted(it) }) {
            respond(pending.id, PermissionResult.GRANTED)
            return
        }
        ledger.submit(pending)
    }

    private fun respond(id: Long, result: PermissionResult) {
        if (!OffloadPermissionManager.respondToAndroidPermission(id, result)) {
            AppLogger.warning(BRIDGE_TAG, "prompt id=$id already gone — $result dropped")
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
}
