# =============================================================
# MePass 安全加固 ProGuard / R8 规则
# 目标：最大化代码混淆 / 优化强度，保留必要的 Android / Compose / 加密入口
# =============================================================

# ---- 1. 基础优化策略（更激进，让反编译者阅读成本更高） ----
# 注意：实际混淆强度由 R8 主导（proguard-android-optimize.txt），这里仅补充定制化规则
-optimizationpasses 6
-allowaccessmodification
-repackageclasses ''
-overloadaggressively
-mergeinterfacesaggressively
-keepattributes Signature,InnerClasses,*Annotation*,EnclosingMethod
# 【不要】加 -dontobfuscate；isMinifyEnabled=true 会自动开启混淆

# ---- 2. Jetpack Compose 不能混淆的最小集合（否则 release 必崩） ----
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.navigation.compose.** { *; }
-keep class androidx.activity.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep @androidx.compose.runtime.Composable class * { *; }

# ---- 3. Kotlinx 序列化（如果使用 @Serializable） ----
-keepattributes *Annotation*, kotlin.Metadata
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class @kotlinx.serialization.Serializable ** {
    <init>(...);
    *** component1();
    *** component2();
}

# ---- 4. Argon2 加密库（JNI 和反射入口） ----
-keep class de.mkammerer.argon2.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn de.mkammerer.argon2.**

# ---- 5. 数据模型：需要跨 Gson/JSON 序列化的字段不能改名字 ----
-keep class com.mepass.app.model.** {
    <fields>;
    <init>(...);
}
-keep class com.mepass.app.crypto.ShamirSecretSharing$* { <fields>; <init>(...); }

# ---- 6. 移除所有 Log 调用（Release 中不能输出敏感日志） ----
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
}

# ---- 7. 防止反编译工具直接识别关键类名（加密/安全/密码模块的类名保留但成员混淆） ----
-keepnames class com.mepass.app.crypto.**
-keepnames class com.mepass.app.security.**
-keepnames class com.mepass.app.template.**

# ---- 8. AndroidX / Material 兼容性兜底 ----
-keep class androidx.core.** { *; }
-keep class androidx.appcompat.** { *; }
-keep class com.google.android.material.** { *; }
-dontwarn androidx.**
-dontwarn com.google.android.material.**
