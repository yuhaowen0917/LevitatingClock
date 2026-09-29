package com.ticksync.clock.settings

import com.ticksync.clock.time.CountdownConfig
import com.ticksync.clock.time.TimeSource
import com.ticksync.clock.time.TimeSourceCatalog

/** 悬浮窗尺寸档位 */
enum class OverlaySize {
    /** 仅 `HH:mm:ss` */
    SMALL,

    /** `HH:mm:ss.SSS` */
    MEDIUM,

    /** 时间 + 倒计时 + 校准状态 */
    LARGE
}

/**
 * 应用设置。
 */
data class AppSettings(
    /**
     * 时间源列表（保序）。
     *
     * 默认值见 [TimeSourceCatalog.DEFAULT]，包含设备时间、预置 NTP 与预置平台源；
     * 平台源默认停用——它们是各平台的业务接口，只在用户明确开启后才访问。
     */
    val timeSources: List<TimeSource> = TimeSourceCatalog.DEFAULT,
    val countdown: CountdownConfig = CountdownConfig.DISABLED,
    val autoSyncIntervalMin: Int = DEFAULT_AUTO_SYNC_INTERVAL_MIN,
    val overlaySize: OverlaySize = OverlaySize.MEDIUM,
    val overlayAlpha: Float = DEFAULT_OVERLAY_ALPHA,
    val overlayTextColor: Int = DEFAULT_TEXT_COLOR,
    val showMillis: Boolean = true,
    val hideInFullscreen: Boolean = false,

    /**
     * 开机自动恢复悬浮窗。
     *
     * 默认关闭：在用户没有明确要求时，不应于开机后自行拉起前台服务、占用悬浮窗权限。
     * 需要的人去设置里打开一次即可，选择权留给用户。
     */
    val restoreOnBoot: Boolean = false,

    /**
     * 穿透模式。
     *
     * false（默认）：悬浮窗参与命中测试，可长按拖动、双击切换尺寸，但覆盖区域内的触摸
     * 不会落到下层应用。这是调整位置时必须的状态。
     *
     * true：窗口带上 FLAG_NOT_TOUCHABLE，完全不参与命中测试，实现零干扰。
     * 摆好位置后建议开启。
     */
    val passthroughMode: Boolean = false
) {
    companion object {
        const val DEFAULT_AUTO_SYNC_INTERVAL_MIN = 10
        const val DEFAULT_OVERLAY_ALPHA = 0.8f

        /** 0xFF00FF00：荧光绿，与悬浮窗默认时间文字色一致 */
        val DEFAULT_TEXT_COLOR: Int = 0xFF00FF00.toInt()

        /** 可选的自动校准间隔（分钟） */
        val SYNC_INTERVAL_OPTIONS = listOf(1, 5, 10, 30)
    }
}
