# MePass ProGuard/R8 规则（极简版）

# BouncyCastle：Argon2id 纯 Java 实现，保留必要类
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jce.provider.X509LDAPCertStoreSpi
-dontwarn org.bouncycastle.jce.provider.CrlCache

# kotlinx.serialization
-keepattributes *Annotation*, kotlin.Metadata
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
-keepclassmembers @kotlinx.serialization.Serializable class com.mepass.app.** {
    <init>(...);
    *** component*();
    *** serializer();
}

# 保留数据模型字段名（JSON 序列化需要）
-keep class com.mepass.app.model.** { <fields>; <init>(...); }
