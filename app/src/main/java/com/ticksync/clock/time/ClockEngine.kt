package com.ticksync.clock.time

import android.os.SystemClock

/**
 * 时钟引擎 —— 全应用唯一的"当前时间"来源。
 *
 * ## 为什么不能直接显示 System.currentTimeMillis() + offset
 *
 * 1. 系统时钟可能被系统自动校时（NITZ/NTP）或用户手动修改而**跳变**，
 *    每帧重新读取并加上 offset，会让秒/毫秒位出现跳帧甚至倒走。
 * 2. 显示层需要平滑推进，直接用系统时间无法保证单调性。
 *
 * ## 正确做法：校准锚点 + 单调时钟推进
 *
 * 每次校准成功时记录一对锚点：
 *  - [anchorElapsedMs]  当时的 SystemClock.elapsedRealtime()（单调，不受系统时间调整影响）
 *  - [anchorCalibratedMs] 当时的标准时间戳
 *
 * 之后任意时刻：标准时间 = 锚点标准时间 + (当前 elapsedRealtime - 锚点 elapsedRealtime)
 *
 * 本机晶振存在漂移（通常每天 < 1 秒），由定时自动校准修正，属于可接受误差。
 *
 * ## 跨重启恢复
 *
 * elapsedRealtime 在设备重启后归零，旧锚点失效。因此重启后用持久化的 offset
 * 重建锚点（见 [restoreFromOffset]）：本次运行期内依然保持单调不跳变，
 * 跨重启的准确性由 offset 的新鲜度决定，UI 需展示"上次校准于 x 分钟前"告知用户。
 */
object ClockEngine {

    private const val ANCHOR_UNSET = 0L

    @Volatile
    private var anchorElapsedMs: Long = ANCHOR_UNSET

    @Volatile
    private var anchorCalibratedMs: Long = ANCHOR_UNSET

    /** 是否已建立校准锚点。未校准时 [nowMs] 退化为本机系统时间。 */
    val isCalibrated: Boolean
        get() = anchorElapsedMs != ANCHOR_UNSET

    /**
     * 校准成功回调。由 [TimeSyncManager] 在同步成功后调用。
     *
     * @param standardEpochMs 该校准时刻的标准时间戳（本机时间 + offset）
     */
    fun onSynced(standardEpochMs: Long) {
        anchorCalibratedMs = standardEpochMs
        anchorElapsedMs = SystemClock.elapsedRealtime()
    }

    /**
     * 用持久化的偏差重建锚点（App 重启时调用）。
     *
     * @param offsetMs 上次校准得到的「标准时间 - 系统时间」
     */
    fun restoreFromOffset(offsetMs: Long) {
        val standardNow = System.currentTimeMillis() + offsetMs
        anchorCalibratedMs = standardNow
        anchorElapsedMs = SystemClock.elapsedRealtime()
    }

    /** 当前标准时间（毫秒）。未校准时返回本机系统时间。 */
    fun nowMs(): Long {
        val anchor = anchorElapsedMs
        if (anchor == ANCHOR_UNSET) return System.currentTimeMillis()
        return anchorCalibratedMs + (SystemClock.elapsedRealtime() - anchor)
    }

    /** 当前「标准时间 - 本机系统时间」的实时偏差，用于状态展示。 */
    fun currentOffsetMs(): Long = nowMs() - System.currentTimeMillis()

    /** 清空锚点（用于调试或强制回到未校准态）。 */
    fun reset() {
        anchorElapsedMs = ANCHOR_UNSET
        anchorCalibratedMs = ANCHOR_UNSET
    }
}
