package com.ticksync.clock.time

/**
 * 时间源类型。
 *
 * 三类的精度与适用场景差异很大：
 * - [NTP]：UDP 123 的授时协议，四时间戳法可把网络不对称带来的误差压到毫秒级，最可靠；
 * - [HTTP]：直接问各平台自己的接口。返回毫秒时间戳的源精度与 NTP 相当；
 *   只拿到响应头 `Date` 的源精度是整秒，量化误差最大 1 秒；
 * - [DEVICE]：直接采信本机系统时间（offset 恒为 0）。它只作兜底——
 *   在抢票这类场景里毫无价值，因为本机时钟正是需要被纠正的那一个。
 */
enum class TimeSourceType {
    NTP,
    HTTP,
    DEVICE
}

/**
 * 一个时间源。
 *
 * @param type     源类型，决定用哪个客户端去探测
 * @param name     展示名称，用户可改（如「大麦」「我的公司 NTP」）
 * @param endpoint NTP 为域名，HTTP 为完整 URL，DEVICE 为空串
 * @param enabled  是否参与本次校准
 * @param builtin  是否为预置源：预置源可停用、可改名，但不可删除，避免误删后无从恢复
 */
data class TimeSource(
    val type: TimeSourceType,
    val name: String,
    val endpoint: String,
    val enabled: Boolean = true,
    val builtin: Boolean = false
) {
    /**
     * 稳定标识。
     *
     * 用「类型 + 地址」拼出而非随机 UUID：改名不该改变身份，而地址变了本就是另一个源。
     * 这样也省掉了把 id 单独持久化的一致性负担。
     */
    val id: String
        get() = "$type:$endpoint"

    /** 是否为设备时间源 */
    val isDevice: Boolean
        get() = type == TimeSourceType.DEVICE
}

/**
 * 预置时间源目录。
 *
 * 预置 ≠ 默认开启：平台接口默认全部停用，理由见 [PLATFORM_PRESETS]。
 */
object TimeSourceCatalog {

    /** 设备时间源的地址占位（它没有网络地址） */
    const val DEVICE_ENDPOINT = ""

    /** 设备时间源。固定存在于列表首位，可停用但不可删除。 */
    val DEVICE = TimeSource(
        type = TimeSourceType.DEVICE,
        name = "设备时间",
        endpoint = DEVICE_ENDPOINT,
        enabled = true,
        builtin = true
    )

    /**
     * 预置 NTP 源。
     *
     * 默认只开启国家级授时中心与两家国内云厂商：源越多，一次校准的并发请求越多，
     * 而两个低延迟的独立源已足够让中位数法剔除离群值。
     * 境外源需要用户明确开启，它们在部分网络下不可达，只会拖长同步耗时。
     */
    val NTP_PRESETS = listOf(
        TimeSource(TimeSourceType.NTP, "国家授时中心", "ntp.ntsc.ac.cn"),
        TimeSource(TimeSourceType.NTP, "阿里云", "ntp.aliyun.com"),
        TimeSource(TimeSourceType.NTP, "腾讯云", "time1.cloud.tencent.com"),
        TimeSource(TimeSourceType.NTP, "公共池（境外）", "cn.pool.ntp.org", enabled = false)
    )

    /**
     * 预置平台源。
     *
     * 默认全部**停用**：这些是各平台自己的业务接口，只在用户明确开启后才应被访问。
     * 抢票平台普遍有风控，无谓的后台轮询可能招致限流，所以不做默认开启。
     *
     * 「毫秒」与「秒级」的区别取决于该接口是否回吐毫秒时间戳：
     * - 前三个接口会返回毫秒时间戳，精度与 NTP 同级；
     * - 大麦、猫眼只取响应头 `Date`，精度是整秒——抢票场景下请优先用毫秒级源。
     */
    val PLATFORM_PRESETS = listOf(
        TimeSource(
            type = TimeSourceType.HTTP,
            name = "淘宝 / 天猫（毫秒）",
            endpoint = "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp",
            enabled = false,
            builtin = true
        ),
        TimeSource(
            type = TimeSourceType.HTTP,
            name = "京东（毫秒）",
            endpoint = "https://a.jd.com/ajax/queryServerData.html",
            enabled = false,
            builtin = true
        ),
        TimeSource(
            type = TimeSourceType.HTTP,
            name = "苏宁易购（毫秒）",
            endpoint = "https://f.m.suning.com/api/ct.do",
            enabled = false,
            builtin = true
        ),
        TimeSource(
            type = TimeSourceType.HTTP,
            name = "大麦（秒级）",
            endpoint = "https://www.damai.cn/",
            enabled = false,
            builtin = true
        ),
        TimeSource(
            type = TimeSourceType.HTTP,
            name = "猫眼（秒级）",
            endpoint = "https://www.maoyan.com/",
            enabled = false,
            builtin = true
        )
    )

    /** 出厂默认源列表 */
    val DEFAULT: List<TimeSource> = listOf(DEVICE) + NTP_PRESETS + PLATFORM_PRESETS
}
