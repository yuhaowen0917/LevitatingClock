package com.ticksync.clock.time

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间格式化工具。
 *
 * 刷新频率为 30fps，**禁止每帧 new SimpleDateFormat**（构造开销远大于格式化本身），
 * 因此复用实例并加锁保证线程安全。
 *
 * 时区或系统时间变更后需调用 [reset] 重建实例，否则会沿用旧时区。
 */
object TimeFormat {

    /** 一位毫秒对应的毫秒数：只保留十分位 */
    private const val MILLIS_PER_TENTH = 100

    @Volatile
    private var tenthsFormatter = build(withTenths = true)

    @Volatile
    private var secondFormatter = build(withTenths = false)

    private fun build(withTenths: Boolean) = SimpleDateFormat(
        if (withTenths) "HH:mm:ss.S" else "HH:mm:ss",
        Locale.getDefault()
    )

    /** 时区 / 系统时间变更后调用，强制重建格式化实例。 */
    @Synchronized
    fun reset() {
        tenthsFormatter = build(withTenths = true)
        secondFormatter = build(withTenths = false)
    }

    /**
     * 格式化为 `HH:mm:ss.S`。
     *
     * 毫秒只保留一位：抢票的决策粒度是零点几秒，后两位纯粹是视觉噪声，
     * 省下来的宽度还能换成更大的字号。
     */
    @Synchronized
    fun hmsS(epochMs: Long): String = tenthsFormatter.format(Date(epochMs))

    /** 格式化为 `HH:mm:ss` */
    @Synchronized
    fun hms(epochMs: Long): String = secondFormatter.format(Date(epochMs))

    /** 仅十分位，一位数字如 `4`。用于拼接小字号的小数位。 */
    fun tenthsPart(epochMs: Long): String {
        val raw = (epochMs % 1000).toInt()
        val v = if (raw < 0) raw + 1000 else raw
        return (v / MILLIS_PER_TENTH).toString()
    }

    /** 格式化倒计时 `HH:mm:ss.S`，不足一小时时省略小时段。 */
    fun countdown(remainMs: Long): String {
        val safe = remainMs.coerceAtLeast(0L)
        val totalSeconds = safe / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val tenths = (safe % 1000) / MILLIS_PER_TENTH
        val head = if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
        return "$head.$tenths"
    }

    /** 偏差文案：`+128ms` / `-45ms` */
    fun offsetText(offsetMs: Long): String =
        if (offsetMs >= 0) "+${offsetMs}ms" else "${offsetMs}ms"

    /** 悬浮窗状态行文案：`已校准 +32ms` / `系统慢 128ms` / `未同步` */
    fun offsetHumanText(offsetMs: Long): String = when {
        offsetMs > 0 -> "系统慢 ${offsetMs}ms"
        offsetMs < 0 -> "系统快 ${-offsetMs}ms"
        else -> "时间已校准"
    }

    /** 相对时间：`刚刚` / `x 分钟前` */
    fun agoText(nowMs: Long, thenMs: Long): String {
        if (thenMs <= 0L) return "—"
        val seconds = ((nowMs - thenMs).coerceAtLeast(0L)) / 1000
        return when {
            seconds < 10 -> "刚刚"
            seconds < 60 -> "${seconds} 秒前"
            seconds < 3600 -> "${seconds / 60} 分钟前"
            else -> "${seconds / 3600} 小时前"
        }
    }
}
