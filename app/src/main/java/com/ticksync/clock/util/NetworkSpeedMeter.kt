package com.ticksync.clock.util

import android.net.TrafficStats
import android.os.SystemClock

/**
 * 一次网速采样结果。
 *
 * @param downKBps 下行速率（KB/s）
 * @param upKBps   上行速率（KB/s）
 */
data class NetworkSpeed(val downKBps: Long, val upKBps: Long) {
    companion object {
        val ZERO = NetworkSpeed(0L, 0L)
    }
}

/**
 * 实时网速采样器。
 *
 * ## 为什么统计的是整机流量
 * 本应用自己的收发量极小（一次 NTP 探测只有 48 字节），用"本应用的网速"来判断
 * 网络状况毫无参考价值。用户真正想知道的是"这台手机现在网通不通、快不快"，
 * 所以取的是设备总流量。
 *
 * ## 为什么要有最小采样间隔
 * 悬浮窗 30fps 刷新，若每帧都采样，相邻两次间隔只有 33ms，
 * 计数器的一点点抖动会被放大成剧烈跳动的数字。限制为 1 秒、期间复用上次结果，
 * 读数才稳定可用。
 */
object NetworkSpeedMeter {

    /** 两次真实采样之间的最小间隔 */
    private const val MIN_INTERVAL_MS = 1000L

    private const val BYTES_PER_KB = 1024L
    private const val MILLIS_PER_SECOND = 1000L

    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastSampleMs = 0L
    private var lastSpeed = NetworkSpeed.ZERO

    /**
     * 采一次样。
     *
     * 距上次真实采样不足 [MIN_INTERVAL_MS] 时直接返回上次结果，
     * 因此可以放心地按帧调用。
     */
    @Synchronized
    fun sample(): NetworkSpeed {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()

        // 部分设备与模拟器不支持该统计，会返回 -1；此时显示 0 好过显示一串错误数字
        if (rx < 0L || tx < 0L) return NetworkSpeed.ZERO

        val now = SystemClock.elapsedRealtime()
        if (lastSampleMs == 0L) {
            // 首次采样只建立基准，此刻算不出速率
            lastRxBytes = rx
            lastTxBytes = tx
            lastSampleMs = now
            return NetworkSpeed.ZERO
        }

        val elapsedMs = now - lastSampleMs
        if (elapsedMs < MIN_INTERVAL_MS) return lastSpeed

        // 计数器可能因接口重置而回退，用 coerceAtLeast 兜底避免出现负数速率
        val down = (rx - lastRxBytes).coerceAtLeast(0L) * MILLIS_PER_SECOND / elapsedMs / BYTES_PER_KB
        val up = (tx - lastTxBytes).coerceAtLeast(0L) * MILLIS_PER_SECOND / elapsedMs / BYTES_PER_KB

        lastRxBytes = rx
        lastTxBytes = tx
        lastSampleMs = now
        lastSpeed = NetworkSpeed(downKBps = down, upKBps = up)
        return lastSpeed
    }
}
