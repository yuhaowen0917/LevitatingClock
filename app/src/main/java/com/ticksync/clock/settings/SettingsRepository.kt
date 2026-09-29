package com.ticksync.clock.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ticksync.clock.time.CountdownConfig
import com.ticksync.clock.time.SyncState
import com.ticksync.clock.time.TimeSource
import com.ticksync.clock.time.TimeSourceCatalog
import com.ticksync.clock.time.TimeSourceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 进程内唯一的 DataStore 实例（委托属性必须声明在文件顶层） */
private val Context.settingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "ticksync_settings")

/**
 * 设置与校准状态持久化。
 *
 * 使用 DataStore(Preferences) 替代 SharedPreferences：协程友好、无 apply 丢写风险。
 * 时间源列表需保序，因此以换行符拼接存为单个字符串，而非 stringSet。
 */
class SettingsRepository private constructor(private val store: DataStore<Preferences>) {

    val settingsFlow: Flow<AppSettings> = store.data.map { prefs -> prefs.toAppSettings() }

    /** 读取当前设置的快照（一次性） */
    suspend fun settings(): AppSettings = settingsFlow.first()

    /**
     * 写入时间源列表。
     *
     * 每个源编码成一行，字段用 `|` 分隔，例如：
     * ```
     * NTP|1|ntp.ntsc.ac.cn|国家授时中心
     * HTTP|0|https://www.damai.cn/|大麦（秒级）
     * DEVICE|1||设备时间
     * ```
     *
     * 字段顺序不是随意的：名称是唯一可能包含分隔符的自由文本，所以放在最后，
     * 解析时用 `split(limit = 4)` 让最后一段吸收多余的 `|`，从而完全不需要转义。
     */
    suspend fun setTimeSources(sources: List<TimeSource>) = store.edit { prefs ->
        prefs[KEY_TIME_SOURCES] = sources.joinToString(LINE_SEPARATOR) { it.encode() }
    }

    suspend fun setCountdown(config: CountdownConfig) = store.edit { prefs ->
        prefs[KEY_COUNTDOWN_ENABLED] = config.enabled
        prefs[KEY_COUNTDOWN_LABEL] = config.label
        // 时分秒打包成一个 int：三者总是一起读写，拆成三个 key 只会徒增不一致的可能
        prefs[KEY_COUNTDOWN_SECOND_OF_DAY] =
            config.hour * SECONDS_PER_HOUR + config.minute * SECONDS_PER_MINUTE + config.second
    }

    suspend fun setAutoSyncInterval(minutes: Int) = store.edit { prefs ->
        prefs[KEY_AUTO_SYNC_INTERVAL] = minutes
    }

    suspend fun setOverlaySize(size: OverlaySize) = store.edit { prefs ->
        prefs[KEY_OVERLAY_SIZE] = size.name
    }

    suspend fun setOverlayAlpha(alpha: Float) = store.edit { prefs ->
        prefs[KEY_OVERLAY_ALPHA] = alpha.coerceIn(MIN_ALPHA, 1f)
    }

    suspend fun setOverlayTextColor(color: Int) = store.edit { prefs ->
        prefs[KEY_OVERLAY_TEXT_COLOR] = color
    }

    suspend fun setShowMillis(show: Boolean) = store.edit { prefs ->
        prefs[KEY_SHOW_MILLIS] = show
    }

    suspend fun setHideInFullscreen(hide: Boolean) = store.edit { prefs ->
        prefs[KEY_HIDE_FULLSCREEN] = hide
    }

    suspend fun setRestoreOnBoot(restore: Boolean) = store.edit { prefs ->
        prefs[KEY_RESTORE_ON_BOOT] = restore
    }

    suspend fun setPassthroughMode(enabled: Boolean) = store.edit { prefs ->
        prefs[KEY_PASSTHROUGH] = enabled
    }

    /** 持久化最近一次成功校准的结果 */
    suspend fun saveSyncState(state: SyncState) = store.edit { prefs ->
        prefs[KEY_SYNC_OFFSET] = state.offsetMs
        prefs[KEY_SYNC_TIME] = state.syncTimeMs
        prefs[KEY_SYNC_SERVER] = state.serverName
        prefs[KEY_SYNC_RTT] = state.rttMs
        prefs[KEY_SYNC_SAMPLES] = state.sampleCount
        prefs[KEY_SYNC_SOURCE_TYPE] = state.sourceType.name
        prefs[KEY_SYNC_PRECISION] = state.precisionMs
    }

    /** 读取上次校准结果；从未校准时返回 [SyncState.EMPTY] */
    suspend fun syncState(): SyncState {
        val prefs = store.data.first()
        return SyncState(
            offsetMs = prefs[KEY_SYNC_OFFSET] ?: 0L,
            syncTimeMs = prefs[KEY_SYNC_TIME] ?: 0L,
            serverName = prefs[KEY_SYNC_SERVER] ?: "",
            rttMs = prefs[KEY_SYNC_RTT] ?: 0L,
            sampleCount = prefs[KEY_SYNC_SAMPLES] ?: 0,
            sourceType = prefs[KEY_SYNC_SOURCE_TYPE]
                ?.let { name -> TimeSourceType.entries.firstOrNull { it.name == name } }
                ?: TimeSourceType.NTP,
            precisionMs = prefs[KEY_SYNC_PRECISION] ?: 1L
        )
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val size = this[KEY_OVERLAY_SIZE]
            ?.let { name -> OverlaySize.entries.firstOrNull { it.name == name } }
            ?: OverlaySize.MEDIUM

        return AppSettings(
            timeSources = decodeTimeSources(this[KEY_TIME_SOURCES]),
            countdown = decodeCountdown(),
            autoSyncIntervalMin = this[KEY_AUTO_SYNC_INTERVAL]
                ?: AppSettings.DEFAULT_AUTO_SYNC_INTERVAL_MIN,
            overlaySize = size,
            overlayAlpha = this[KEY_OVERLAY_ALPHA] ?: AppSettings.DEFAULT_OVERLAY_ALPHA,
            overlayTextColor = this[KEY_OVERLAY_TEXT_COLOR] ?: AppSettings.DEFAULT_TEXT_COLOR,
            showMillis = this[KEY_SHOW_MILLIS] ?: true,
            hideInFullscreen = this[KEY_HIDE_FULLSCREEN] ?: false,
            restoreOnBoot = this[KEY_RESTORE_ON_BOOT] ?: false,
            passthroughMode = this[KEY_PASSTHROUGH] ?: false
        )
    }

    private fun Preferences.decodeTimeSources(raw: String?): List<TimeSource> =
        raw?.split(LINE_SEPARATOR)
            ?.mapNotNull { decodeTimeSource(it.trim()) }
            ?.takeIf { it.isNotEmpty() }
            ?: TimeSourceCatalog.DEFAULT

    private fun Preferences.decodeCountdown(): CountdownConfig {
        val secondOfDay = this[KEY_COUNTDOWN_SECOND_OF_DAY] ?: DEFAULT_COUNTDOWN_SECOND_OF_DAY
        val hour = secondOfDay / SECONDS_PER_HOUR
        val minute = secondOfDay % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val second = secondOfDay % SECONDS_PER_MINUTE
        return CountdownConfig(
            enabled = this[KEY_COUNTDOWN_ENABLED] ?: false,
            label = this[KEY_COUNTDOWN_LABEL] ?: "",
            hour = hour,
            minute = minute,
            second = second
        )
    }

    private fun TimeSource.encode(): String =
        listOf(type.name, if (enabled) FLAG_ON else FLAG_OFF, endpoint, name)
            .joinToString(FIELD_SEPARATOR)

    private fun decodeTimeSource(line: String): TimeSource? {
        if (line.isEmpty()) return null
        // limit = 4：最后一段吸收名称中出现的所有分隔符，因此无需转义
        val parts = line.split(FIELD_SEPARATOR, limit = FIELD_COUNT)
        if (parts.size < FIELD_COUNT) return null
        val type = TimeSourceType.entries.firstOrNull { it.name == parts[0] } ?: return null
        return TimeSource(
            type = type,
            name = parts[3],
            endpoint = parts[2],
            enabled = parts[1] == FLAG_ON,
            builtin = TimeSourceCatalog.DEFAULT.any { it.id == "$type:${parts[2]}" }
        )
    }

    companion object {
        private const val LINE_SEPARATOR = "\n"
        private const val FIELD_SEPARATOR = "|"
        private const val FIELD_COUNT = 4
        private const val FLAG_ON = "1"
        private const val FLAG_OFF = "0"

        private const val SECONDS_PER_HOUR = 3600
        private const val SECONDS_PER_MINUTE = 60

        /** 10:00:00 */
        private const val DEFAULT_COUNTDOWN_SECOND_OF_DAY = 10 * SECONDS_PER_HOUR

        const val MIN_ALPHA = 0.2f

        private val KEY_TIME_SOURCES = stringPreferencesKey("time_sources")
        private val KEY_COUNTDOWN_ENABLED = booleanPreferencesKey("countdown_enabled")
        private val KEY_COUNTDOWN_LABEL = stringPreferencesKey("countdown_label")
        private val KEY_COUNTDOWN_SECOND_OF_DAY = intPreferencesKey("countdown_second_of_day")
        private val KEY_AUTO_SYNC_INTERVAL = intPreferencesKey("auto_sync_interval_min")
        private val KEY_OVERLAY_SIZE = stringPreferencesKey("overlay_size")
        private val KEY_OVERLAY_ALPHA = floatPreferencesKey("overlay_alpha")
        private val KEY_OVERLAY_TEXT_COLOR = intPreferencesKey("overlay_text_color")
        private val KEY_SHOW_MILLIS = booleanPreferencesKey("show_millis")
        private val KEY_HIDE_FULLSCREEN = booleanPreferencesKey("hide_in_fullscreen")
        private val KEY_RESTORE_ON_BOOT = booleanPreferencesKey("restore_on_boot")
        private val KEY_PASSTHROUGH = booleanPreferencesKey("passthrough_mode")

        private val KEY_SYNC_OFFSET = longPreferencesKey("sync_offset_ms")
        private val KEY_SYNC_TIME = longPreferencesKey("sync_time_ms")
        private val KEY_SYNC_SERVER = stringPreferencesKey("sync_server")
        private val KEY_SYNC_RTT = longPreferencesKey("sync_rtt_ms")
        private val KEY_SYNC_SAMPLES = intPreferencesKey("sync_sample_count")
        private val KEY_SYNC_SOURCE_TYPE = stringPreferencesKey("sync_source_type")
        private val KEY_SYNC_PRECISION = longPreferencesKey("sync_precision_ms")

        @Volatile
        private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext.settingsDataStore)
                    .also { instance = it }
            }
    }
}
