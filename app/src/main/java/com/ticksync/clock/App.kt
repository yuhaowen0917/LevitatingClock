package com.ticksync.clock

import android.app.Application
import com.ticksync.clock.settings.SettingsRepository
import com.ticksync.clock.time.ClockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 启动时立即用持久化的 offset 恢复 [ClockEngine] 锚点，
 * 使冷启动首屏就能显示校准时间，无需等待网络同步完成。
 */
class App : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val repository = SettingsRepository.get(this)
        appScope.launch {
            val saved = repository.syncState()
            if (saved.syncTimeMs > 0L) {
                ClockEngine.restoreFromOffset(saved.offsetMs)
            }
        }
    }
}
