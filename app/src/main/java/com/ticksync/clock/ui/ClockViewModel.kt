package com.ticksync.clock.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ticksync.clock.overlay.OverlayService
import com.ticksync.clock.settings.AppSettings
import com.ticksync.clock.settings.OverlaySize
import com.ticksync.clock.settings.SettingsRepository
import com.ticksync.clock.time.ClockEngine
import com.ticksync.clock.time.CountdownConfig
import com.ticksync.clock.time.SyncQuality
import com.ticksync.clock.time.SyncState
import com.ticksync.clock.time.TimeSource
import com.ticksync.clock.time.TimeSourceCatalog
import com.ticksync.clock.time.TimeSyncManager
import com.ticksync.clock.util.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 主界面状态与操作。
 *
 * 所有时间展示都取自 [ClockEngine]，本类不做任何时间计算（倒计时的日期换算除外，
 * 它只是把"标准时间戳"翻译成"还剩多久"，不产生第二个时间来源），
 * 避免界面与悬浮窗显示不一致。
 */
class ClockViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SettingsRepository.get(application)
    private val syncManager = TimeSyncManager(repository)

    val settings: StateFlow<AppSettings> = repository.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _syncState = MutableStateFlow(SyncState.EMPTY)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _online = MutableStateFlow(true)

    /**
     * 悬浮窗服务是否运行。
     *
     * 使用轮询而非事件总线：服务可能被系统静默回收，事件通知在这种场景下不可靠，
     * 500ms 的轮询开销可以忽略。
     */
    val overlayRunning: StateFlow<Boolean> = flow {
        while (true) {
            emit(OverlayService.isRunning)
            delay(OVERLAY_STATE_POLL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), OverlayService.isRunning)

    /** 当前标准时间，约 30fps 推进；无订阅者时自动停止，避免后台空转 */
    val nowMs: StateFlow<Long> = flow {
        while (true) {
            emit(ClockEngine.nowMs())
            delay(TICK_INTERVAL_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), ClockEngine.nowMs())

    /**
     * 倒计时专用时基。
     *
     * 与 [nowMs] 分开是为了压低重组频率：时间显示要 30fps 才不抖，
     * 而倒计时最小刻度是十分之一秒，100ms 刷新一次足够，
     * 也顺带把日期换算（[CountdownConfig.nextTargetMs]）的开销降到每帧的三分之一。
     */
    private val countdownTickMs: StateFlow<Long> = flow {
        while (true) {
            emit(ClockEngine.nowMs())
            delay(COUNTDOWN_TICK_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), ClockEngine.nowMs())

    /** 距下一次倒计时目标的剩余毫秒；未启用时为 [NO_COUNTDOWN] */
    val countdownRemainMs: StateFlow<Long> = combine(countdownTickMs, settings) { now, current ->
        if (!current.countdown.enabled) NO_COUNTDOWN
        else (current.countdown.nextTargetMs(now) - now).coerceAtLeast(0L)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), NO_COUNTDOWN)

    /** 校准状态分级，驱动状态卡片配色 */
    val quality: StateFlow<SyncQuality> = combine(_syncState, nowMs, _online) { state, now, online ->
        syncManager.qualityOf(state, now, online)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), SyncQuality.UNSYNCED)

    init {
        viewModelScope.launch {
            // 先用持久化结果恢复，保证首屏立刻可显示校准时间
            _syncState.value = syncManager.restore()
            refreshNetworkState()
            // 从未校准过则自动同步一次
            if (_syncState.value.syncTimeMs <= 0L) {
                syncNow()
            }
        }
    }

    /** 手动校准 */
    fun syncNow() {
        if (_isSyncing.value) return
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                val state = syncManager.sync()
                if (state != null) {
                    _syncState.value = state
                }
                refreshNetworkState()
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun refreshNetworkState() {
        _online.value = NetworkUtils.isOnline(getApplication())
    }

    // ------------------------------------------------------------ 悬浮窗

    fun startOverlay() = OverlayService.start(getApplication())

    fun stopOverlay() = OverlayService.stop(getApplication())

    // ------------------------------------------------------------ 时间源

    fun setTimeSources(sources: List<TimeSource>) = update { repository.setTimeSources(sources) }

    /** 追加一个时间源；与现有源重复时忽略 */
    fun addTimeSource(source: TimeSource) {
        val current = settings.value.timeSources
        if (current.any { it.id == source.id }) return
        setTimeSources(current + source)
    }

    /**
     * 按 id 替换一个时间源（允许同时修改地址与名称）。
     *
     * 改地址会改变 [TimeSource.id]，所以必须显式传入原 id 才能定位到被编辑的那一项；
     * 若新地址与其它已有源撞车，则整体放弃，避免列表里出现两个身份相同的源。
     */
    fun replaceTimeSource(id: String, updated: TimeSource) {
        val current = settings.value.timeSources
        if (current.any { it.id != id && it.id == updated.id }) return
        setTimeSources(current.map { if (it.id == id) updated else it })
    }

    /** 按 id 删除；设备时间源是最后的兜底，永不允许删除 */
    fun removeTimeSource(id: String) {
        val next = settings.value.timeSources.filterNot { it.id == id }
        if (next.none { it.isDevice }) return
        setTimeSources(next)
    }

    fun setTimeSourceEnabled(id: String, enabled: Boolean) {
        val next = settings.value.timeSources.map { if (it.id == id) it.copy(enabled = enabled) else it }
        setTimeSources(next)
    }

    fun resetTimeSources() = setTimeSources(TimeSourceCatalog.DEFAULT)

    // ------------------------------------------------------------ 倒计时

    fun setCountdown(config: CountdownConfig) = update { repository.setCountdown(config) }

    /** 就地更新倒计时配置，避免调用方重复读当前值 */
    fun updateCountdown(transform: (CountdownConfig) -> CountdownConfig) {
        setCountdown(transform(settings.value.countdown))
    }

    // ------------------------------------------------------------ 其它设置

    fun setAutoSyncInterval(minutes: Int) = update { repository.setAutoSyncInterval(minutes) }

    fun setOverlaySize(size: OverlaySize) = update { repository.setOverlaySize(size) }

    fun setOverlayAlpha(alpha: Float) = update { repository.setOverlayAlpha(alpha) }

    fun setShowMillis(show: Boolean) = update { repository.setShowMillis(show) }

    fun setRestoreOnBoot(restore: Boolean) = update { repository.setRestoreOnBoot(restore) }

    fun setPassthroughMode(enabled: Boolean) = update { repository.setPassthroughMode(enabled) }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() } }
    }

    companion object {
        /** 33ms ≈ 30fps，毫秒位已视觉流畅 */
        private const val TICK_INTERVAL_MS = 33L

        /** 倒计时刷新间隔：十分之一秒的显示精度，100ms 足够 */
        private const val COUNTDOWN_TICK_MS = 100L

        private const val OVERLAY_STATE_POLL_MS = 500L

        private const val SUBSCRIBE_TIMEOUT_MS = 1_000L

        /** [countdownRemainMs] 的"未启用"哨兵值 */
        const val NO_COUNTDOWN = -1L
    }
}
