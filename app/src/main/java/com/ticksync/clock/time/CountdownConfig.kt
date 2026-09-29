package com.ticksync.clock.time

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 倒计时目标配置。
 *
 * ## 为什么是「每日重复的时分秒」而不是「某个具体日期时刻」
 * 抢票场景里用户记住的是"10:00 开抢"，而不是"2026-10-01 10:00:00"。
 * 每日重复既贴合这个心智，也让同一份配置在预售、正式开售等多天里都能直接用，
 * 且无需提供日期选择器——一个时分秒就够了。
 *
 * 目标时刻按本机时区换算。这里用的是"标准时间戳 + 本机时区"的组合：
 * 标准时间负责"现在几点"，本机时区负责"这一天怎么切分"，两者互不干扰。
 *
 * @param enabled 是否启用倒计时
 * @param label   倒计时名称（如「大麦 · 周五场」），仅用于展示，可为空
 * @param hour    目标时（0~23）
 * @param minute  目标分（0~59）
 * @param second  目标秒（0~59）
 */
data class CountdownConfig(
    val enabled: Boolean = false,
    val label: String = "",
    val hour: Int = 10,
    val minute: Int = 0,
    val second: Int = 0
) {

    /** 目标时刻文本，形如 `10:00:00` */
    val timeText: String
        get() = String.format(Locale.US, "%02d:%02d:%02d", safeHour, safeMinute, safeSecond)

    /**
     * 计算下一次到达的目标时刻（标准时间戳，毫秒）。
     *
     * 今天该时刻已过则顺延到明天——倒计时永远指向"未来最近的一次"，
     * 因此归零后会自动开始下一轮，不需要用户每天重设。
     */
    fun nextTargetMs(nowStandardMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowStandardMs).atZone(zone)
        val today = now.toLocalDate().atTime(safeHour, safeMinute, safeSecond).atZone(zone)
        val target = if (today.toInstant().toEpochMilli() > nowStandardMs) today else today.plusDays(1)
        return target.toInstant().toEpochMilli()
    }

    companion object {
        val DISABLED = CountdownConfig()

        /** 支持的倒计时跨度上限：超过一天说明配置或时间本身出了问题 */
        const val MAX_SPAN_MS = 24 * 60 * 60 * 1000L
    }

    // 持久化数据可能被手工改坏（或来自旧版本），取值前一律夹取，避免 atTime 抛异常
    private val safeHour: Int get() = hour.coerceIn(0, 23)
    private val safeMinute: Int get() = minute.coerceIn(0, 59)
    private val safeSecond: Int get() = second.coerceIn(0, 59)
}
