package com.ticksync.clock.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 权限检测与跳转。
 *
 * 涉及两类权限：
 * - `SYSTEM_ALERT_WINDOW`：悬浮窗，属「特殊权限」，只能跳系统设置页由用户手动开启，无法用运行时弹窗申请；
 * - `POST_NOTIFICATIONS`：Android 13+ 的运行时权限，拒绝后前台服务通知不显示（服务仍可运行）。
 */
object PermissionUtils {

    /** 悬浮窗权限是否已授予 */
    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** 跳转到本应用的悬浮窗权限设置页 */
    fun overlayPermissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
    )

    /** 通知权限是否已授予（Android 13 以下恒为 true） */
    fun hasNotificationPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    /** 需要运行时申请的通知权限名（供 Activity 发起请求） */
    val notificationPermission: String
        get() = Manifest.permission.POST_NOTIFICATIONS

    /** 是否需要申请通知权限 */
    fun needsNotificationRequest(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * 跳转到电池优化设置页。
     *
     * 国产 ROM 上「电池优化白名单」是后台存活的关键，此处跳转到系统电池优化列表页，
     * 由用户自行将本应用设为「不优化」（直接申请豁免的接口各厂商支持不一致，易被拦截）。
     */
    fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** 跳转到本应用的系统设置详情页（厂商额外权限通常在此页） */
    fun appDetailIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    )
}
