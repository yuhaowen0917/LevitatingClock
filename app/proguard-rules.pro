# TickSync 混淆规则

# 保留 DataStore 相关（反射读取 Preferences 键名）
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite {
    <fields>;
}

# Kotlin 协程
-keepclassmembernames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-dontwarn kotlinx.coroutines.**

# 本项目为纯 Kotlin + Compose，无反射注入场景，其余保持默认规则

# 发布时保留行号，便于崩溃定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 显式声明不使用自动化点击等能力（合规要求），保留相关说明占位
-dontnote com.ticksync.clock.**
