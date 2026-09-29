package com.ticksync.clock.time

import java.net.HttpURLConnection
import java.net.URL

/**
 * 平台 HTTP 接口时间客户端。
 *
 * ## 为什么需要问平台的服务器
 * 抢票跟的是**票务平台自己的服务器时间**：平台页面上显示的"距开抢还有 x 秒"
 * 就是按它自己那台机器算的。国家授时中心的标准时间理论上与之相同，
 * 但平台侧时钟可能有独立偏差，直接问平台等于把这一层不确定性也消掉。
 *
 * ## 取值策略（按精度从高到低回落）
 * 1. 响应体中 13 位、首位为 1 的整数——各平台的时间接口普遍返回毫秒时间戳，精度与 NTP 同级；
 * 2. 响应头 `Date`——只有整秒精度，但任何 HTTP 接口都有，作为最后的兜底。
 *
 * 刻意**不做**"与本地时间比对"的合理性校验：本机时钟本就可能是错的，
 * 而那正是本应用要解决的问题，拿它去否定服务器时间会变成死循环。
 * 误匹配的漏网之鱼交给融合层的中位数与离群剔除兜底（见 [TimeSyncManager]）。
 *
 * 类文档之外还有一点：预置源全部使用 HTTPS。Android 9 起默认禁止明文流量，
 * 用户自定义 `http://` 地址会直接失败——这是预期行为，抢票链路不该走明文。
 */
object HttpTimeClient {

    private const val DEFAULT_TIMEOUT_MS = 2000

    /**
     * 读取响应体的字节上限。
     *
     * 时间戳总在响应最前面，但有些平台地址返回的是整张首页 HTML（如大麦），
     * 无上限读取既慢又费流量。
     */
    private const val MAX_BODY_BYTES = 64 * 1024

    /**
     * 毫秒时间戳特征：13 位且首位为 1。
     *
     * 首位为 1 意味着取值落在 2001-09-09 ~ 2286-11-20 之间，
     * 足以排除订单号、商品 ID 一类的长数字（它们通常更长，或不在该区间）。
     */
    private val EPOCH_MILLIS_REGEX = Regex("""\b1\d{12}\b""")

    /** 部分平台接口对空 UA 会直接拒绝，带上常见的移动端 UA 最稳 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * 查询单个 HTTP 时间源。
     *
     * 阻塞方法，必须在 IO 线程调用。
     *
     * @return 成功返回 [TimeSample]；非 2xx、超时、无可用时间字段均返回 null（不抛异常）
     */
    fun query(source: TimeSource, timeoutMs: Int = DEFAULT_TIMEOUT_MS): TimeSample? {
        var connection: HttpURLConnection? = null
        return try {
            val t1 = System.currentTimeMillis()

            connection = (URL(source.endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }

            if (connection.responseCode !in 200..299) return null

            // Date 是服务器生成响应的时刻，只有整秒精度；字段缺失时返回 0
            val headerDateMs = connection.date

            val body = runCatching { readBodyPrefix(connection) }.getOrNull()

            val t4 = System.currentTimeMillis()
            val rtt = (t4 - t1).coerceAtLeast(0L)

            val resolved = resolveServerTime(body, headerDateMs) ?: return null
            val serverTimeMs = resolved.first

            // 与 NTP 相同的对称假设：把请求区间的中点当作本机对应的时刻
            val offsetMs = serverTimeMs - (t1 + t4) / 2

            TimeSample(
                server = source.name,
                offsetMs = offsetMs,
                rttMs = rtt,
                sourceType = TimeSourceType.HTTP,
                precisionMs = resolved.second
            )
        } catch (e: Exception) {
            // 网络不可达、TLS 失败、超时、非法 URL 一律视为本次探测失败，绝不向上抛
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** 读取响应体前 [MAX_BODY_BYTES] 字节；无响应体时返回 null */
    private fun readBodyPrefix(connection: HttpURLConnection): String? =
        connection.inputStream?.use { stream ->
            val buffer = ByteArray(MAX_BODY_BYTES)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read <= 0) break
                total += read
            }
            if (total == 0) null else String(buffer, 0, total, Charsets.UTF_8)
        }

    /** @return (服务器时间戳, 该值的分辨率毫秒)；两者都取不到时返回 null */
    private fun resolveServerTime(body: String?, headerDateMs: Long): Pair<Long, Long>? {
        val fromBody = body?.let { EPOCH_MILLIS_REGEX.find(it)?.value?.toLongOrNull() }
        if (fromBody != null) return fromBody to TimeSample.PRECISION_MILLIS
        if (headerDateMs > 0L) return headerDateMs to TimeSample.PRECISION_SECONDS
        return null
    }
}
