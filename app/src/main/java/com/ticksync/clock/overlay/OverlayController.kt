package com.ticksync.clock.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.ticksync.clock.settings.AppSettings
import com.ticksync.clock.settings.OverlaySize
import kotlin.math.roundToInt

/**
 * 悬浮窗控制器：统一封装窗口的创建、更新、销毁、样式应用与位置约束。
 *
 * 拆分为独立类的目的：让 [OverlayService] 只关心生命周期与前台服务约束，
 * 让 [OverlayView] 只关心渲染与触摸意图，窗口状态只在此处维护，避免三处各改一份。
 *
 * @param context 应用上下文
 */
class OverlayController(private val context: Context) {

    /** 尺寸档位被双击或操作面板切换时回调，由外部持久化 */
    var onSizeToggleRequested: (() -> Unit)? = null

    /** 操作面板点击「打开」时回调，由外部拉起主界面 */
    var onOpenAppRequested: (() -> Unit)? = null

    /** 操作面板点击「关闭」时回调，由外部停止服务 */
    var onCloseRequested: (() -> Unit)? = null

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: OverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var currentSettings: AppSettings = AppSettings()
    private var touchable: Boolean = true

    /** 窗口是否已挂载到 WindowManager */
    val isAttached: Boolean
        get() = overlayView?.isAttachedToWindow == true

    // ------------------------------------------------------------ 生命周期

    /** 创建并挂载悬浮窗。重复调用安全。 */
    fun attach(settings: AppSettings) {
        currentSettings = settings
        if (isAttached) {
            applySettings(settings)
            return
        }

        val view = OverlayView(context).apply { listener = viewListener }
        val params = buildLayoutParams()
        overlayView = view
        layoutParams = params
        applySizeAndStyle(settings)

        runCatching { windowManager.addView(view, params) }
    }

    /** 移除悬浮窗。重复调用安全。 */
    fun detach() {
        val view = overlayView ?: return
        runCatching { windowManager.removeView(view) }
        overlayView = null
        layoutParams = null
    }

    /**
     * 自检并重建。
     *
     * 悬浮窗可能被系统或第三方清理工具移除而服务仍在运行，
     * 此时 [isAttached] 为 false，需要重新 addView。
     */
    fun ensureAttached() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        if (!view.isAttachedToWindow) {
            runCatching { windowManager.addView(view, params) }
        }
    }

    // ------------------------------------------------------------ 渲染

    /**
     * 渲染一帧内容。
     *
     * 可见性规则分两类：
     * - **状态行与网速行**由尺寸档位决定——SMALL 档的诉求是尽量少占地，辅助行一律不显示；
     * - **倒计时行**只由"是否启用倒计时"决定，不再看档位。倒计时是用户主动开启的，
     *   开了就应该看得见，被档位悄悄藏起来是说不通的。
     */
    fun render(
        epochMs: Long,
        statusText: String,
        statusColor: Int,
        countdownText: String? = null,
        networkSpeedText: String? = null
    ) {
        val view = overlayView ?: return
        val settings = currentSettings

        val showAuxiliary = settings.overlaySize != OverlaySize.SMALL
        val showCountdown = !countdownText.isNullOrEmpty()
        val showTenths = settings.showMillis && showAuxiliary

        view.renderTime(epochMs, showTenths, settings.overlayTextColor)
        view.renderStatus(statusText, showAuxiliary, statusColor)
        view.renderCountdown(countdownText, showCountdown)
        view.renderNetworkSpeed(networkSpeedText, showAuxiliary)
    }

    // ------------------------------------------------------------ 设置应用

    /** 应用设置变更（尺寸、透明度、颜色） */
    fun applySettings(settings: AppSettings) {
        currentSettings = settings
        applySizeAndStyle(settings)
        val params = layoutParams ?: return
        applyTouchableFlag(params)
        val view = overlayView ?: return
        safeUpdate(view, params)
    }

    /**
     * 切换穿透模式。
     *
     * 开启后窗口带上 `FLAG_NOT_TOUCHABLE`，完全不参与命中测试，
     * 悬浮窗覆盖区域内的触摸会原样落到下层应用上，实现零干扰。
     */
    fun setTouchable(value: Boolean) {
        if (touchable == value) return
        touchable = value
        val params = layoutParams ?: return
        applyTouchableFlag(params)
        val view = overlayView ?: return
        safeUpdate(view, params)
    }

    // ------------------------------------------------------------ 内部实现

    private val viewListener = object : OverlayView.Listener {

        override fun onDragStart() {
            // 拖动期间恢复完整透明度，保证拖动过程中看得清
            overlayView?.alpha = currentSettings.overlayAlpha
        }

        override fun onDragBy(dx: Float, dy: Float) {
            val view = overlayView ?: return
            val params = layoutParams ?: return
            params.x += dx.roundToInt()
            params.y += dy.roundToInt()
            clampWithinScreen(params, view)
            safeUpdate(view, params)
        }

        override fun onDragEnd() {
            alignToEdge()
        }

        override fun onToggleSize() {
            onSizeToggleRequested?.invoke()
        }

        override fun onSizeChanged() {
            reclamp()
        }

        override fun onOpenApp() {
            onOpenAppRequested?.invoke()
        }

        override fun onCloseOverlay() {
            onCloseRequested?.invoke()
        }
    }

    /**
     * 内容尺寸变化后重新约束窗口位置。
     *
     * 操作面板展开会让窗口变高变宽，而布局参数里的 x/y 不会跟着变，
     * 贴右边缘或底部时就会被撑出屏幕外。调用点来自 `doOnLayout`，此时新尺寸已生效。
     */
    private fun reclamp() {
        val view = overlayView ?: return
        val params = layoutParams ?: return
        clampWithinScreen(params, view)
        safeUpdate(view, params)
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        // minSdk 26，TYPE_APPLICATION_OVERLAY 全程可用
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        val screen = screenSize()
        params.x = (screen.x - DEFAULT_INITIAL_RIGHT_MARGIN_PX).coerceAtLeast(0)
        params.y = DEFAULT_INITIAL_TOP_MARGIN_PX
        return params
    }

    private fun applyTouchableFlag(params: WindowManager.LayoutParams) {
        params.flags = if (touchable) {
            BASE_FLAGS
        } else {
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
    }

    private fun applySizeAndStyle(settings: AppSettings) {
        val view = overlayView ?: return
        view.alpha = settings.overlayAlpha
        view.applyTimeTextSize(
            when (settings.overlaySize) {
                OverlaySize.SMALL -> TIME_TEXT_SIZE_SMALL_SP
                OverlaySize.MEDIUM -> TIME_TEXT_SIZE_MEDIUM_SP
                OverlaySize.LARGE -> TIME_TEXT_SIZE_LARGE_SP
            }
        )
    }

    /** 限制在屏幕可视范围内：上下边界硬约束，避免拖出屏幕后无法找回 */
    private fun clampWithinScreen(params: WindowManager.LayoutParams, view: OverlayView) {
        val screen = screenSize()
        val width = view.width.coerceAtLeast(0)
        val height = view.height.coerceAtLeast(0)
        params.x = params.x.coerceIn(0, (screen.x - width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (screen.y - height).coerceAtLeast(0))
    }

    /** 松手吸附到最近的左右边缘；若本就贴近边缘，进一步降低透明度弱化视觉存在 */
    private fun alignToEdge() {
        val view = overlayView ?: return
        val params = layoutParams ?: return

        val screen = screenSize()
        val width = view.width.coerceAtLeast(1)

        params.y = params.y.coerceIn(0, (screen.y - view.height.coerceAtLeast(1)).coerceAtLeast(0))

        val centerX = params.x + width / 2
        val alignLeft = centerX < screen.x / 2
        val distanceToEdge = if (alignLeft) {
            params.x
        } else {
            screen.x - (params.x + width)
        }

        params.x = if (alignLeft) 0 else (screen.x - width).coerceAtLeast(0)
        safeUpdate(view, params)

        view.alpha = if (distanceToEdge <= edgeHideThresholdPx()) {
            COLLAPSED_ALPHA
        } else {
            currentSettings.overlayAlpha
        }
    }

    private fun safeUpdate(view: OverlayView, params: WindowManager.LayoutParams) {
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun edgeHideThresholdPx(): Int = (EDGE_HIDE_THRESHOLD_DP * density).roundToInt()

    private val density: Float
        get() = context.resources.displayMetrics.density

    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val metrics = context.resources.displayMetrics
            Point(metrics.widthPixels, metrics.heightPixels)
        }

    companion object {
        /** 不抢焦点、不阻塞下层触摸、允许超出边界（位置由 [clampWithinScreen] 约束） */
        private const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        /** 初始位置：右上角，距顶部分 100px，避开常见抢票按钮 */
        private const val DEFAULT_INITIAL_TOP_MARGIN_PX = 100
        private const val DEFAULT_INITIAL_RIGHT_MARGIN_PX = 220

        /** 贴边弱化的距离阈值 */
        private const val EDGE_HIDE_THRESHOLD_DP = 24
        private const val COLLAPSED_ALPHA = 0.4f

        private const val TIME_TEXT_SIZE_SMALL_SP = 16f
        private const val TIME_TEXT_SIZE_MEDIUM_SP = 18f
        private const val TIME_TEXT_SIZE_LARGE_SP = 20f
    }
}
