package com.ticksync.clock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ticksync.clock.overlay.OverlayService
import com.ticksync.clock.settings.SettingsRepository
import com.ticksync.clock.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机 / 应用更新后按设置恢复悬浮窗。
 *
 * 读取 DataStore 属于异步操作，因此使用 [goAsync] 延长广播处理时间，
 * 完成后必须调用 `pendingResult.finish()`，否则系统会判定广播超时。
 *
 * 注意：Android 12+ 起后台启动前台服务受限，`BOOT_COMPLETED` 属于豁免场景，
 * 但 `MY_PACKAGE_REPLACED` 不在豁免列表中，因此启动动作需容错处理。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository.get(appContext).settings()
                if (settings.restoreOnBoot && PermissionUtils.hasOverlayPermission(appContext)) {
                    runCatching { OverlayService.start(appContext) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
