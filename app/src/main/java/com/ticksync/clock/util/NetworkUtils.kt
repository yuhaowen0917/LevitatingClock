package com.ticksync.clock.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 网络状态工具 */
object NetworkUtils {

    /**
     * 当前是否存在可用网络。
     *
     * 仅用于区分「离线」与「同步失败」两种状态展示，
     * 不用于拦截同步请求——实际能否到达 NTP 服务器由 [com.ticksync.clock.time.NtpClient] 判定。
     */
    fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
