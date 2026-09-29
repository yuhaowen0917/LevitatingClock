package com.ticksync.clock.util

import android.os.Build
import java.util.Locale

/** 厂商 ROM 类型。国产 ROM 对悬浮窗与后台常驻有额外限制，需要差异化引导。 */
enum class RomType {
    /** 小米 / 红米 */
    MIUI,

    /** 华为 / 荣耀 */
    EMUI,

    /** OPPO / realme / 一加 */
    COLOR_OS,

    /** vivo / iQOO */
    ORIGIN_OS,

    /** 三星 */
    ONE_UI,

    /** 原生或未识别的 ROM */
    STOCK
}

/**
 * 厂商与 ROM 识别。
 *
 * 通过 [Build.MANUFACTURER] 结合厂商私有系统属性判断，
 * 用于在主界面展示针对性的权限引导（MIUI 等的「后台弹出界面」权限是踩坑重灾区）。
 */
object RomUtils {

    fun romType(): RomType {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase(Locale.US)
        val brand = Build.BRAND.orEmpty().lowercase(Locale.US)

        return when {
            isMiui(manufacturer, brand) -> RomType.MIUI
            isEmui(manufacturer, brand) -> RomType.EMUI
            isColorOs(manufacturer, brand) -> RomType.COLOR_OS
            isOriginOs(manufacturer, brand) -> RomType.ORIGIN_OS
            manufacturer.contains("samsung") -> RomType.ONE_UI
            else -> RomType.STOCK
        }
    }

    /** ROM 展示名称，用于引导文案 */
    fun displayName(type: RomType): String = when (type) {
        RomType.MIUI -> "小米 / 红米（MIUI）"
        RomType.EMUI -> "华为 / 荣耀（EMUI / HarmonyOS）"
        RomType.COLOR_OS -> "OPPO / realme（ColorOS）"
        RomType.ORIGIN_OS -> "vivo / iQOO（OriginOS）"
        RomType.ONE_UI -> "三星（One UI）"
        RomType.STOCK -> "原生 Android"
    }

    /** 是否需要展示额外的厂商权限引导卡片 */
    fun needsExtraGuide(type: RomType): Boolean = type != RomType.STOCK && type != RomType.ONE_UI

    private fun isMiui(manufacturer: String, brand: String): Boolean =
        manufacturer.contains("xiaomi") ||
            brand.contains("xiaomi") ||
            brand.contains("redmi") ||
            brand.contains("poco") ||
            systemProperty("ro.miui.ui.version.name").isNotEmpty()

    private fun isEmui(manufacturer: String, brand: String): Boolean =
        manufacturer.contains("huawei") ||
            brand.contains("huawei") ||
            brand.contains("honor") ||
            systemProperty("ro.build.version.emui").isNotEmpty() ||
            systemProperty("hw_sc.build.platform.version").isNotEmpty()

    private fun isColorOs(manufacturer: String, brand: String): Boolean =
        manufacturer.contains("oppo") ||
            manufacturer.contains("realme") ||
            brand.contains("oppo") ||
            brand.contains("realme") ||
            brand.contains("oneplus") ||
            systemProperty("ro.build.version.opporom").isNotEmpty()

    private fun isOriginOs(manufacturer: String, brand: String): Boolean =
        manufacturer.contains("vivo") ||
            brand.contains("vivo") ||
            brand.contains("iqoo") ||
            systemProperty("ro.vivo.os.version").isNotEmpty()

    /**
     * 读取厂商私有系统属性。
     *
     * 通过反射访问 android.os.SystemProperties（隐藏 API），
     * 失败时返回空串，不影响识别流程。
     */
    private fun systemProperty(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val getter = clazz.getMethod("get", String::class.java)
        (getter.invoke(null, key) as? String).orEmpty()
    }.getOrDefault("")
}
