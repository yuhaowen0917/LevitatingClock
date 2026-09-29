package com.ticksync.clock.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ticksync.clock.settings.SettingsRepository
import com.ticksync.clock.time.TimeFormat
import com.ticksync.clock.time.TimeSyncManager
import com.ticksync.clock.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 系统时间 / 时区变更响应。
 *
 * 两个动作的处理不同：
 * - `TIMEZONE_CHANGED`：需要重建时间格式化实例（[TimeFormat.reset]），否则沿用旧时区；
 * - `TIME_SET`（用户或系统修改了时钟）：[com.ticksync.clock.time.ClockEngine] 基于单调时钟
 *   推进，显示不会跳变，但已保存的 offset 基准已失去意义，因此立即触发一次重新校准。
 */
class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED -> TimeFormat.reset()

            Intent.ACTION_TIME_CHANGED -> {
                TimeFormat.reset()
                triggerResync(context)
            }
        }
    }

    private fun triggerResync(context: Context) {
        val appContext = context.applicationContext
        if (!NetworkUtils.isOnline(appContext)) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val repository = SettingsRepository.get(appContext)
                val manager = TimeSyncManager(repository)
                runCatching { manager.sync() }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
