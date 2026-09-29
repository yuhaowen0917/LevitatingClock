package com.ticksync.clock.time

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * SNTP 协议客户端（RFC 4330 简化实现，仅客户端模式）。
 *
 * ## 协议要点
 * 向服务器 UDP 123 端口发送 48 字节请求包，首字节为 `0x1B`
 * （LI=0 无告警、VN=3 版本 3、Mode=3 客户端模式），其余字节置 0。
 * 服务器返回 48 字节，其中：
 *  - 字节 40~43：Transmit Timestamp 秒部分（自 1900-01-01 起算，NTP 纪元）
 *  - 字节 44~47：Transmit Timestamp 小数部分（1/2^32 秒为单位）
 *
 * ## 偏移量计算（四时间戳法）
 * ```
 * t1 = 客户端发送时刻（本机墙钟）
 * t3 = 服务器发送时刻（从响应包解析）
 * t4 = 客户端接收时刻（本机墙钟）
 * offset = t3 - (t1 + t4) / 2
 * rtt    = t4 - t1
 * ```
 *
 * ## 可信度控制
 * RTT 超过 [MAX_RTT_MS] 的结果网络抖动过大，直接丢弃，不参与最终计算。
 */
object NtpClient {

    private const val NTP_PORT = 123
    private const val NTP_PACKET_SIZE = 48

    /** LI=0, VN=3, Mode=3（客户端） */
    private const val LI_VN_MODE_CLIENT = 0x1B

    private const val DEFAULT_TIMEOUT_MS = 1500
    private const val RETRY_TIMES = 1

    /** 超过该 RTT 视为不可信，丢弃 */
    private const val MAX_RTT_MS = 800L

    /** NTP 纪元（1900-01-01）到 Unix 纪元（1970-01-01）的秒差 */
    private const val NTP_EPOCH_DIFF_SECONDS = 2208988800L

    /** 小数部分满量程 2^32 */
    private const val FRACTION_SCALE = 0x100000000L

    private const val TRANSMIT_TS_INDEX = 40

    /**
     * 查询单个 NTP 时间源。
     *
     * 阻塞方法，必须在 IO 线程调用。
     *
     * @param source 时间源，仅取其 [TimeSource.endpoint] 作为主机名；
     *               名称与类型回填进 [TimeSample]，避免上层再做一次转换
     * @return 成功返回 [TimeSample]；超时、RTT 过大或解析失败返回 null（不抛异常）
     */
    fun query(source: TimeSource, timeoutMs: Int = DEFAULT_TIMEOUT_MS): TimeSample? {
        repeat(RETRY_TIMES + 1) {
            queryOnce(source, timeoutMs)?.let { return it }
        }
        return null
    }

    private fun queryOnce(source: TimeSource, timeoutMs: Int): TimeSample? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            val address = InetAddress.getByName(source.endpoint)

            val buffer = ByteArray(NTP_PACKET_SIZE)
            buffer[0] = LI_VN_MODE_CLIENT.toByte()

            val t1 = System.currentTimeMillis()
            val t1Elapsed = SystemClock.elapsedRealtime()
            socket.send(DatagramPacket(buffer, buffer.size, address, NTP_PORT))

            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)

            val t4 = System.currentTimeMillis()
            val t4Elapsed = SystemClock.elapsedRealtime()

            // 用单调时钟复核 RTT：若等待期间系统时间被调整，墙钟差值会失真
            val rtt = (t4Elapsed - t1Elapsed).coerceAtLeast(0L)
            if (rtt > MAX_RTT_MS) return null

            val t3 = parseTransmitTimestamp(buffer) ?: return null
            val offset = t3 - (t1 + t4) / 2

            TimeSample(
                server = source.name,
                offsetMs = offset,
                rttMs = rtt,
                sourceType = TimeSourceType.NTP
            )
        } catch (e: Exception) {
            // 网络不可达、DNS 失败、超时等一律视为本次探测失败，绝不能向上抛
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** 解析 Transmit Timestamp（字节 40~47）为 Unix 毫秒时间戳。 */
    private fun parseTransmitTimestamp(buffer: ByteArray): Long? {
        var seconds = 0L
        for (i in TRANSMIT_TS_INDEX until TRANSMIT_TS_INDEX + 4) {
            seconds = (seconds shl 8) or (buffer[i].toInt() and 0xFF).toLong()
        }
        if (seconds == 0L) return null // 服务器未填充时间戳

        var fraction = 0L
        for (i in TRANSMIT_TS_INDEX + 4 until TRANSMIT_TS_INDEX + 8) {
            fraction = (fraction shl 8) or (buffer[i].toInt() and 0xFF).toLong()
        }

        val secondsSinceUnix = seconds - NTP_EPOCH_DIFF_SECONDS
        val fractionMillis = fraction * 1000 / FRACTION_SCALE
        return secondsSinceUnix * 1000 + fractionMillis
    }
}
