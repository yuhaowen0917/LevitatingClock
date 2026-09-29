package com.ticksync.clock.time

/**
 * 单次时间探测样本。
 *
 * NTP 与 HTTP 两类客户端都产出该结构，融合层不关心样本从哪来，只按数值处理。
 *
 * @param server      产出该样本的源名称（用于展示，如「国家授时中心」「淘宝 / 天猫」）
 * @param offsetMs    标准时间 - 本机系统时间（毫秒）
 * @param rttMs       网络往返延迟（毫秒）
 * @param sourceType  样本来源类型，用于展示与「设备时间」的特判
 * @param precisionMs 该源的时间分辨率（毫秒）
 */
data class TimeSample(
    val server: String,
    val offsetMs: Long,
    val rttMs: Long,
    val sourceType: TimeSourceType = TimeSourceType.NTP,
    val precisionMs: Long = PRECISION_MILLIS
) {
    companion object {
        /** 毫秒级：NTP 与返回毫秒时间戳的平台接口 */
        const val PRECISION_MILLIS = 1L

        /** 秒级：HTTP 响应头 `Date` 只精确到整秒，量化误差最大 1000ms */
        const val PRECISION_SECONDS = 1000L
    }
}

/**
 * 校准结果，持久化到 DataStore。
 *
 * 注意：本类不保存"标准时间戳"，只保存 offset。
 * 跨重启后 elapsedRealtime 会归零，无法复用旧锚点，
 * 因此重启时用 offset 重建锚点（详见 [ClockEngine.restoreFromOffset]）。
 */
data class SyncState(
    /** 标准时间与本机系统时间的差值（毫秒） */
    val offsetMs: Long,
    /** 完成本次校准的墙钟时刻（标准时间），用于展示"上次校准于 x 分钟前" */
    val syncTimeMs: Long,
    /** 实际采用的源名称（可信样本中 RTT 最小的那个） */
    val serverName: String,
    /** 可信样本中的最小 RTT */
    val rttMs: Long,
    /** 参与最终中位数计算的样本数 */
    val sampleCount: Int,
    /** 实际采用的源类型，用于区分"NTP/平台校准"与"设备时间" */
    val sourceType: TimeSourceType = TimeSourceType.NTP,
    /** 实际采用的源的时间分辨率，用于向用户提示精度上限 */
    val precisionMs: Long = TimeSample.PRECISION_MILLIS
) {
    companion object {
        val EMPTY = SyncState(
            offsetMs = 0L,
            syncTimeMs = 0L,
            serverName = "",
            rttMs = 0L,
            sampleCount = 0
        )
    }
}

/** 校准状态分级，驱动 UI 颜色与文案 */
enum class SyncQuality {
    /** 已校准且新鲜 */
    TRUSTED,

    /** 已校准但超过阈值未重新同步 */
    STALE,

    /** 网络不可用，沿用历史 offset */
    OFFLINE,

    /** 从未校准或全部源失败 */
    UNSYNCED,

    /**
     * 当前只启用了设备时间源。
     *
     * 这种状态下 offset 恒为 0，时间未经任何外部校准，
     * 展示成「已校准」会误导用户，因此单列一档。
     */
    DEVICE
}
