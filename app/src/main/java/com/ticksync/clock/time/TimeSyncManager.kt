package com.ticksync.clock.time

import com.ticksync.clock.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * 多源时间同步策略层。
 *
 * ## 为什么不是「连上哪个算哪个」
 * 单个源可能因劫持、网络抖动或服务器异常返回错误的 offset，
 * 串行取首个成功源无法识别这种异常，会把错误时间当成标准时间。
 *
 * ## 策略
 * 1. 并发请求所有已启用的网络源（NTP 与 HTTP 一视同仁），收集成功样本；
 * 2. 计算 offset 粗中位数，剔除与中位数偏差超过 [OUTLIER_THRESHOLD_MS] 的离群样本；
 * 3. 剩余样本取中位数作为最终 offset（中位数比均值抗噪）；
 * 4. 取剩余样本中 RTT 最小的源作为「本次采用的源」，其 RTT 作为可信度指标；
 * 5. 结果写入 [ClockEngine] 锚点并持久化。
 *
 * ## 设备时间的位置
 * 设备时间的 offset 恒为 0，若把它丢进中位数会系统性拉偏结果，
 * 因此它**不参与融合**：只在所有网络源都失败、且用户保留了该源时，
 * 降级为「未校准」并如实标注（见 [SyncQuality.DEVICE]）。
 *
 * @param repository 设置与校准状态仓储
 */
class TimeSyncManager(private val repository: SettingsRepository) {

    /**
     * 执行一次完整校准。
     *
     * @return 成功返回持久化后的 [SyncState]；全部源失败且无设备时间兜底时返回 null（保留旧值）
     */
    suspend fun sync(): SyncState? = withContext(Dispatchers.IO) {
        val enabled = repository.settings().timeSources.filter { it.enabled }
        if (enabled.isEmpty()) return@withContext null

        val networkSources = enabled.filterNot { it.isDevice }
        val deviceFallbackAvailable = enabled.any { it.isDevice }

        val samples = coroutineScope {
            networkSources.map { source -> async { probe(source) } }.awaitAll()
        }.filterNotNull()

        if (samples.isEmpty()) {
            return@withContext if (deviceFallbackAvailable) applyDeviceFallback() else null
        }

        // 第一步：用粗中位数识别离群样本
        val roughMedian = medianOf(samples.map { it.offsetMs })
        val kept = samples.filter { abs(it.offsetMs - roughMedian) <= OUTLIER_THRESHOLD_MS }
        val trusted = kept.ifEmpty { samples }

        // 第二步：可信样本取中位数作为最终偏移量
        val finalOffset = medianOf(trusted.map { it.offsetMs })
        val best = trusted.minByOrNull { it.rttMs } ?: trusted.first()

        val standardNow = System.currentTimeMillis() + finalOffset
        val state = SyncState(
            offsetMs = finalOffset,
            syncTimeMs = standardNow,
            serverName = best.server,
            rttMs = best.rttMs,
            sampleCount = trusted.size,
            sourceType = best.sourceType,
            // 中位数可能由秒级源贡献，精度按参与样本里最差的那个标注才诚实
            precisionMs = trusted.maxOf { it.precisionMs }
        )

        ClockEngine.onSynced(standardNow)
        repository.saveSyncState(state)
        state
    }

    /**
     * 用持久化的上次结果恢复时钟锚点（App 启动时调用）。
     *
     * 恢复后立即可显示校准时间，无需等待网络；准确性由 offset 新鲜度决定，
     * UI 应据 [SyncState.syncTimeMs] 展示「上次校准于 x 分钟前」。
     *
     * @return 上次校准结果；从未校准时返回 [SyncState.EMPTY]
     */
    suspend fun restore(): SyncState {
        val saved = repository.syncState()
        if (saved.syncTimeMs > 0L) {
            ClockEngine.restoreFromOffset(saved.offsetMs)
        }
        return saved
    }

    /**
     * 根据校准结果的新鲜度与网络状态判定展示质量。
     *
     * 设计为纯函数（不依赖 Context、不查网络），便于 UI 层用响应式流派生状态。
     *
     * @param state  当前校准结果（[SyncState.EMPTY] 表示从未校准）
     * @param nowMs  当前标准时间，取 [ClockEngine.nowMs]
     * @param online 当前是否有可用网络
     */
    fun qualityOf(state: SyncState, nowMs: Long, online: Boolean): SyncQuality {
        if (state.syncTimeMs <= 0L) return SyncQuality.UNSYNCED
        // 设备时间不依赖网络、也不存在「陈旧」，单独成一档
        if (state.sourceType == TimeSourceType.DEVICE) return SyncQuality.DEVICE

        val ageMs = (nowMs - state.syncTimeMs).coerceAtLeast(0L)

        return when {
            !online -> SyncQuality.OFFLINE
            ageMs <= STALE_THRESHOLD_MS -> SyncQuality.TRUSTED
            else -> SyncQuality.STALE
        }
    }

    /** 按源类型分派探测；设备时间不走网络，在这里返回 null 由兜底逻辑处理 */
    private fun probe(source: TimeSource): TimeSample? = when (source.type) {
        TimeSourceType.NTP -> NtpClient.query(source)
        TimeSourceType.HTTP -> HttpTimeClient.query(source)
        TimeSourceType.DEVICE -> null
    }

    /**
     * 网络源全部失败时的降级：以本机时间为准（offset = 0）。
     *
     * 仍然重建锚点，好处是本次运行期内后续系统校时不会让显示跳变；
     * 状态如实标记为 [TimeSourceType.DEVICE]，UI 不会谎称"已校准"。
     */
    private suspend fun applyDeviceFallback(): SyncState {
        val now = System.currentTimeMillis()
        val state = SyncState(
            offsetMs = 0L,
            syncTimeMs = now,
            serverName = TimeSourceCatalog.DEVICE.name,
            rttMs = 0L,
            sampleCount = 1,
            sourceType = TimeSourceType.DEVICE
        )
        ClockEngine.onSynced(now)
        repository.saveSyncState(state)
        return state
    }

    private fun medianOf(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    companion object {
        /** 与中位数偏差超过该值的样本视为离群值（毫秒） */
        const val OUTLIER_THRESHOLD_MS = 150L

        /** 超过该时长未重新校准则标记为「陈旧」（毫秒） */
        const val STALE_THRESHOLD_MS = 5 * 60 * 1000L
    }
}
