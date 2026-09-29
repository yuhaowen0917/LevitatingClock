package com.ticksync.clock.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ticksync.clock.MainActivity
import com.ticksync.clock.R
import com.ticksync.clock.settings.AppSettings
import com.ticksync.clock.settings.OverlaySize
import com.ticksync.clock.settings.SettingsRepository
import com.ticksync.clock.time.ClockEngine
import com.ticksync.clock.time.SyncState
import com.ticksync.clock.time.TimeFormat
import com.ticksync.clock.time.TimeSyncManager
import com.ticksync.clock.util.NetworkSpeedMeter
import com.ticksync.clock.util.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗前台服务。
 *
 * 以 `specialUse` 类型的前台服务运行，保证进程优先级，使悬浮窗在应用退到后台后持续存在。
 * 生命周期要点：
 * - [onStartCommand] 返回 [START_STICKY]，被系统回收后自动重建；
 * - 每次启动自检窗口挂载状态，被第三方清理工具移除后自动重建；
 * - 锁屏时把刷新频率从 30fps 降到 1fps，避免无谓功耗；
 * - 服务运行期间负责按设置周期执行自动校准（大尺寸档位下改为 60 秒轻量校准）。
 *
 * 倒计时的推进与到点提醒也在这里：它跟着悬浮窗的刷新循环走，
 * 因此只要悬浮窗还挂在屏幕上，提醒就一定会触发，不需要额外的定时器。
 */
class OverlayService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var controller: OverlayController
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var syncManager: TimeSyncManager

    private var currentSettings: AppSettings = AppSettings()
    private var latestSyncState: SyncState = SyncState.EMPTY

    private var screenOn = true
    private var networkOnline = true
    private var lastNetworkCheckElapsedMs = 0L
    private var receiverRegistered = false

    /** 已排入定时任务的参数，与最新设置比对以决定是否重排 */
    private var scheduledSize: OverlaySize? = null
    private var scheduledIntervalMin: Int = -1

    /** 本轮倒计时的目标时刻；<= 0 表示尚未推算 */
    private var countdownTargetMs = 0L

    /** 已提醒过的目标时刻，避免同一轮重复震动 */
    private var notifiedTargetMs = 0L

    // ---------------------------------------------------------- 刷新循环

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderFrame()
            handler.postDelayed(this, if (screenOn) REFRESH_INTERVAL_MS else SCREEN_OFF_INTERVAL_MS)
        }
    }

    private val autoSyncRunnable = object : Runnable {
        override fun run() {
            serviceScope.launch {
                performSync()
                scheduleAutoSync()
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    restartRefreshLoop()
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    restartRefreshLoop()
                }
            }
        }
    }

    // ---------------------------------------------------------- 生命周期

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        controller = OverlayController(applicationContext)
        controller.onSizeToggleRequested = { serviceScope.launch { toggleOverlaySize() } }
        controller.onOpenAppRequested = { openMainActivity() }
        controller.onCloseRequested = { stopSelf() }

        settingsRepository = SettingsRepository.get(this)
        syncManager = TimeSyncManager(settingsRepository)

        createNotificationChannel()
        startForegroundCompat()
        registerScreenReceiver()

        serviceScope.launch {
            latestSyncState = syncManager.restore()
            currentSettings = settingsRepository.settings()
            controller.attach(currentSettings)
            controller.setTouchable(!currentSettings.passthroughMode)
        }

        // 订阅设置变更：时间源、尺寸、透明度、穿透模式、倒计时等改动即时生效
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                // 倒计时配置变了，本轮目标作废，下一帧重新推算
                if (settings.countdown != currentSettings.countdown) {
                    countdownTargetMs = 0L
                    notifiedTargetMs = 0L
                }
                currentSettings = settings
                controller.applySettings(settings)
                controller.setTouchable(!settings.passthroughMode)
                // 尺寸档位或校准间隔变化都需要重排定时任务
                if (settings.overlaySize != scheduledSize ||
                    settings.autoSyncIntervalMin != scheduledIntervalMin
                ) {
                    scheduleAutoSync()
                }
            }
        }

        handler.post(refreshRunnable)
        scheduleAutoSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 悬浮窗可能被系统或清理工具移除而服务仍在运行，此处自检重建
        controller.ensureAttached()
        if (!controller.isAttached) {
            controller.attach(currentSettings)
        }
        restartRefreshLoop()
        return START_STICKY
    }

    /**
     * 用户从最近任务划掉应用时触发。
     *
     * 这是明确的"我不要了"信号，悬浮窗必须一起退出：前台服务默认脱离任务独立存活，
     * 若只靠 [START_STICKY]，划掉后悬浮窗会一直留在屏幕上，用户只能再进应用手动关闭它。
     * 主动 [stopSelf] 之后系统不会再按 START_STICKY 把它拉起来。
     *
     * 注意：由开机自启拉起、当时任务栈里还没有本应用的场景不会走到这里，
     * 那种情况下也不存在"划掉"这一动作。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        controller.detach()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------- 帧渲染

    private fun renderFrame() {
        if (!controller.isAttached) return

        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - lastNetworkCheckElapsedMs >= NETWORK_CHECK_INTERVAL_MS) {
            lastNetworkCheckElapsedMs = nowElapsed
            networkOnline = NetworkUtils.isOnline(this)
        }

        val now = ClockEngine.nowMs()
        val countdownTarget = advanceCountdown(now)

        controller.render(
            epochMs = now,
            statusText = buildStatusText(),
            statusColor = statusColor(),
            countdownText = countdownTarget.takeIf { it > 0L }?.let { formatCountdown(it, now) },
            networkSpeedText = buildNetworkSpeedText()
        )
    }

    /**
     * 网速文案。
     *
     * SMALL 档不显示这一行，那就连采样都省掉——每帧读一次流量计数器没有意义。
     */
    private fun buildNetworkSpeedText(): String? {
        if (currentSettings.overlaySize == OverlaySize.SMALL) return null
        val speed = NetworkSpeedMeter.sample()
        return getString(R.string.network_speed_format, speed.downKBps, speed.upKBps)
    }

    private fun buildStatusText(): String {
        if (!ClockEngine.isCalibrated || latestSyncState.syncTimeMs <= 0L) {
            return getString(R.string.overlay_status_unsynced)
        }
        val base = TimeFormat.offsetHumanText(ClockEngine.currentOffsetMs())
        return if (networkOnline) base else "离线 · $base"
    }

    private fun statusColor(): Int = ContextCompat.getColor(
        this,
        when {
            !ClockEngine.isCalibrated -> R.color.status_failed
            !networkOnline -> R.color.status_stale
            else -> R.color.overlay_text_offset
        }
    )

    private fun restartRefreshLoop() {
        handler.removeCallbacks(refreshRunnable)
        handler.post(refreshRunnable)
    }

    // ---------------------------------------------------------- 倒计时

    /**
     * 推进倒计时到当前应显示的那一轮，返回本轮的目标时刻（未启用时返回 0）。
     *
     * 目标时刻只在「首次推算」和「本轮归零」时重算：目标一旦确定，剩余时间只是减法，
     * 没必要每帧做一次日期换算。
     */
    private fun advanceCountdown(nowStandardMs: Long): Long {
        val config = currentSettings.countdown
        if (!config.enabled) {
            countdownTargetMs = 0L
            return 0L
        }

        val current = countdownTargetMs
        if (current > 0L && nowStandardMs < current) return current

        // 目标已到：先提醒（每轮只提醒一次），再翻到下一轮
        if (current > 0L && notifiedTargetMs != current) {
            notifiedTargetMs = current
            vibrateOnce()
        }
        return config.nextTargetMs(nowStandardMs).also { countdownTargetMs = it }
    }

    private fun formatCountdown(targetMs: Long, nowStandardMs: Long): String {
        val config = currentSettings.countdown
        val label = config.label.ifBlank { config.timeText }
        return getString(
            R.string.overlay_countdown_format,
            label,
            TimeFormat.countdown(targetMs - nowStandardMs)
        )
    }

    /**
     * 归零震动一次。
     *
     * 抢票时用户的注意力全在票务应用的按钮上，悬浮窗只占余光，
     * 一次短震动是唯一不依赖视觉的"到点了"信号。
     */
    private fun vibrateOnce() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return

        // 部分 ROM 在无震动权限或免打扰下会抛异常，提醒失败不应影响悬浮窗
        runCatching {
            vibrator.vibrate(
                VibrationEffect.createOneShot(VIBRATE_DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    // ---------------------------------------------------------- 自动校准

    /**
     * 安排下一次自动校准。
     *
     * 大尺寸档位下悬浮窗同时展示倒计时与状态，对时间新鲜度要求更高，
     * 因此使用 60 秒的轻量校准周期；其余情况按用户设置的间隔执行。
     */
    private fun scheduleAutoSync() {
        scheduledSize = currentSettings.overlaySize
        scheduledIntervalMin = currentSettings.autoSyncIntervalMin

        handler.removeCallbacks(autoSyncRunnable)
        val intervalMs = if (currentSettings.overlaySize == OverlaySize.LARGE) {
            LIGHT_SYNC_INTERVAL_MS
        } else {
            currentSettings.autoSyncIntervalMin.coerceAtLeast(1) * 60_000L
        }
        handler.postDelayed(autoSyncRunnable, intervalMs)
    }

    private suspend fun performSync() {
        if (!NetworkUtils.isOnline(this)) return
        val state = runCatching { syncManager.sync() }.getOrNull() ?: return
        latestSyncState = state
        networkOnline = true
    }

    private suspend fun toggleOverlaySize() {
        val next = when (currentSettings.overlaySize) {
            OverlaySize.SMALL -> OverlaySize.MEDIUM
            OverlaySize.MEDIUM -> OverlaySize.LARGE
            OverlaySize.LARGE -> OverlaySize.SMALL
        }
        settingsRepository.setOverlaySize(next)
    }

    /**
     * 从操作面板打开主界面。
     *
     * 服务在后台，启动 Activity 必须带 NEW_TASK；Android 10+ 对后台启动有约束，
     * 但这里的触发源是用户刚刚点击悬浮窗，属于前台交互，正常不会被拦。
     */
    private fun openMainActivity() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            )
        }
    }

    // ---------------------------------------------------------- 前台服务

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .build()
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    companion object {
        private const val CHANNEL_ID = "floating_clock_channel"
        private const val NOTIFICATION_ID = 1001

        /** 亮屏刷新间隔：约 30fps，毫秒位视觉流畅且远低于 60fps 的功耗 */
        private const val REFRESH_INTERVAL_MS = 33L

        /** 锁屏刷新间隔：1fps，屏下无人观看时无需高频刷新 */
        private const val SCREEN_OFF_INTERVAL_MS = 1000L

        /** 大尺寸档位下的轻量校准周期 */
        private const val LIGHT_SYNC_INTERVAL_MS = 60_000L

        /** 网络状态检查周期（避免每帧查询系统服务） */
        private const val NETWORK_CHECK_INTERVAL_MS = 5_000L

        /** 倒计时归零时的震动时长 */
        private const val VIBRATE_DURATION_MS = 400L

        /**
         * 服务是否存活。
         *
         * 供主界面展示悬浮窗开关状态。使用进程内静态标志而非查询
         * ActivityManager（该接口已废弃且开销更大）；进程被杀后标志随进程一起重置，
         * 不存在残留脏值。
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }
}
