package com.ticksync.clock.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.doOnLayout
import com.ticksync.clock.R
import com.ticksync.clock.time.TimeFormat
import kotlin.math.abs

/**
 * 悬浮窗视图：只负责渲染、触摸意图判定、操作面板显隐，不直接操作 WindowManager。
 *
 * 拖动位移通过 [Listener] 回调交给 [OverlayController]，由后者统一更新布局参数，
 * 避免视图持有窗口管理职责导致的状态不一致。
 *
 * ## 触摸分发的重要约束
 * Android 的触摸分发规则是：**DOWN 事件返回 false 后，该 View 不会再收到 MOVE / UP**。
 * 因此「松开手指仍能收到后续事件」与「不消费 DOWN」无法同时成立。
 * 本实现的取舍是：窗口接收触摸，并额外提供「穿透模式」——
 * 开启后由 [OverlayController] 给窗口加 `FLAG_NOT_TOUCHABLE`，
 * 使悬浮窗完全不参与命中测试，真正做到零干扰下层应用。
 *
 * ## 手势约定
 * - **拖动**：按下后位移超过 [MOVE_TOLERANCE_PX] 即开始拖动，不需要长按。
 *   悬浮窗的常态是压在某处不动，"按住就拖"比"按住等 300ms 再拖"更符合直觉；
 * - **单击**：展开操作面板（尺寸 / 打开 / 关闭），再次单击收起；
 * - **双击**：切换尺寸档位。
 */
class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface Listener {
        fun onDragStart()

        /** @param dx 本次移动的横向增量（像素） @param dy 纵向增量（像素） */
        fun onDragBy(dx: Float, dy: Float)

        fun onDragEnd()

        /** 切换尺寸档位（双击或操作面板触发） */
        fun onToggleSize()

        /** 操作面板展开/收起导致视图尺寸变化，需要重新约束窗口位置 */
        fun onSizeChanged()

        /** 打开主界面 */
        fun onOpenApp()

        /** 关闭悬浮窗 */
        fun onCloseOverlay()
    }

    var listener: Listener? = null

    private val timeView: TextView
    private val statusView: TextView
    private val countdownView: TextView
    private val networkView: TextView
    private val actionBar: LinearLayout

    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    private var dragging = false
    private var lastTapUptime = 0L
    private var actionsVisible = false

    /** 单击动作：延迟一个双击窗口后执行，见 [handleTap] */
    private val tapAction = Runnable { setActionsVisible(!actionsVisible) }

    private val hideActionsAction = Runnable { setActionsVisible(false) }

    init {
        LayoutInflater.from(context).inflate(R.layout.layout_floating_clock, this, true)
        timeView = findViewById(R.id.tv_time)
        statusView = findViewById(R.id.tv_offset)
        countdownView = findViewById(R.id.tv_countdown)
        networkView = findViewById(R.id.tv_network)
        actionBar = findViewById(R.id.action_bar)
        bindActionButtons()
        installTouchHandler()
    }

    // ---------------------------------------------------------------- 渲染

    /**
     * 渲染时间。
     *
     * @param epochMs     标准时间戳
     * @param showTenths  是否显示小数位（十分位）
     * @param textColor   时间文字颜色
     */
    fun renderTime(epochMs: Long, showTenths: Boolean, textColor: Int) {
        timeView.setTextColor(textColor)
        if (showTenths) {
            val head = TimeFormat.hms(epochMs)
            val tenths = TimeFormat.tenthsPart(epochMs)
            val text = "$head.$tenths"
            val spannable = SpannableString(text)
            // 小数位字号略小，降低数值跳动带来的视觉干扰
            spannable.setSpan(
                RelativeSizeSpan(TENTHS_RELATIVE_SCALE),
                head.length,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            timeView.text = spannable
        } else {
            timeView.text = TimeFormat.hms(epochMs)
        }
    }

    /** 渲染状态行（校准偏差 / 同步状态） */
    fun renderStatus(text: String, visible: Boolean, color: Int) {
        statusView.text = text
        statusView.setTextColor(color)
        statusView.visibility = if (visible) View.VISIBLE else View.GONE
    }

    /** 渲染倒计时行 */
    fun renderCountdown(text: String?, visible: Boolean) {
        countdownView.text = text.orEmpty()
        countdownView.visibility = if (visible && !text.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    /** 渲染网速行（上下行） */
    fun renderNetworkSpeed(text: String?, visible: Boolean) {
        networkView.text = text.orEmpty()
        networkView.visibility = if (visible && !text.isNullOrEmpty()) View.VISIBLE else View.GONE
    }

    /** 按尺寸档位设置时间字号 */
    fun applyTimeTextSize(sizeSp: Float) {
        timeView.textSize = sizeSp
    }

    // ---------------------------------------------------------------- 操作面板

    private fun bindActionButtons() {
        actionBar.findViewById<TextView>(R.id.action_size).setOnClickListener {
            setActionsVisible(false)
            listener?.onToggleSize()
        }
        actionBar.findViewById<TextView>(R.id.action_app).setOnClickListener {
            setActionsVisible(false)
            listener?.onOpenApp()
        }
        actionBar.findViewById<TextView>(R.id.action_close).setOnClickListener {
            // 不先收起面板：服务随即停止，整个悬浮窗都会被移除
            listener?.onCloseOverlay()
        }
    }

    /**
     * 展开 / 收起操作面板。
     *
     * 展开后会启动自动收起倒计时：面板遮挡的面积比时间本身大得多，
     * 而抢票时用户不会去点它，一直挂着只会挡住下层内容。
     */
    private fun setActionsVisible(visible: Boolean) {
        if (actionsVisible == visible) return
        actionsVisible = visible
        actionBar.visibility = if (visible) View.VISIBLE else View.GONE

        removeCallbacks(hideActionsAction)
        if (visible) {
            postDelayed(hideActionsAction, ACTIONS_AUTO_HIDE_MS)
        }

        // 窗口是 WRAP_CONTENT，尺寸变化后要请控制器重新约束位置，
        // 否则贴右边缘时展开面板会被撑出屏幕
        doOnLayout { listener?.onSizeChanged() }
    }

    /**
     * 触点是否落在操作面板上。
     *
     * event 的 x/y 已是相对本 View 的坐标，与 actionBar 的 getLeft/Top 同一坐标系，直接比较即可。
     * 命中时不消费 DOWN，事件会照常分发给面板上的按钮——否则父容器的 OnTouchListener
     * 会把所有触摸都截走，按钮永远收不到点击。
     */
    private fun isInsideActionBar(event: MotionEvent): Boolean {
        if (!actionsVisible) return false
        return event.x >= actionBar.left && event.x <= actionBar.right &&
            event.y >= actionBar.top && event.y <= actionBar.bottom
    }

    // ---------------------------------------------------------------- 触摸

    @SuppressLint("ClickableViewAccessibility")
    private fun installTouchHandler() {
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isInsideActionBar(event)) return@setOnTouchListener false

                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && movedBeyondTolerance(event)) {
                        // 拖动时先收起面板，免得拖的过程中被它挡住视线
                        setActionsVisible(false)
                        removeCallbacks(tapAction)
                        dragging = true
                        listener?.onDragStart()
                    }
                    if (dragging) {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        listener?.onDragBy(dx, dy)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        dragging = false
                        listener?.onDragEnd()
                    } else {
                        handleTap()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        listener?.onDragEnd()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /**
     * 点击判定：窗口期内再次抬手算双击（切尺寸），否则算单击（开合面板）。
     *
     * 单击动作**延后一个双击窗口再执行**：否则双击切尺寸时第一次抬手就已经把面板弹了出来，
     * 视觉上像误触。代价是面板的出现比手指抬起晚 [DOUBLE_TAP_TIMEOUT_MS]，这个延迟无感。
     */
    private fun handleTap() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapUptime <= DOUBLE_TAP_TIMEOUT_MS) {
            lastTapUptime = 0L
            removeCallbacks(tapAction)
            listener?.onToggleSize()
            return
        }
        lastTapUptime = now
        postDelayed(tapAction, DOUBLE_TAP_TIMEOUT_MS)
    }

    private fun movedBeyondTolerance(event: MotionEvent): Boolean =
        abs(event.rawX - downRawX) > MOVE_TOLERANCE_PX ||
            abs(event.rawY - downRawY) > MOVE_TOLERANCE_PX

    override fun onDetachedFromWindow() {
        removeCallbacks(tapAction)
        removeCallbacks(hideActionsAction)
        super.onDetachedFromWindow()
    }

    /** 当前视图是否处于拖动中，供控制器判断是否需要恢复透明度 */
    val isDragging: Boolean
        get() = dragging

    companion object {
        /** 位移超过该值即认为用户在拖动，同时也是「点击」与「拖动」的分界 */
        const val MOVE_TOLERANCE_PX = 10f

        /** 双击判定窗口，同时也是单击动作的延迟时长 */
        const val DOUBLE_TAP_TIMEOUT_MS = 320L

        /** 操作面板展开后自动收起的时间 */
        const val ACTIONS_AUTO_HIDE_MS = 4_000L

        /** 小数位相对字号 */
        const val TENTHS_RELATIVE_SCALE = 0.72f
    }
}
