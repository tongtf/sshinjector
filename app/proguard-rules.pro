# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp { *; }
-keep class * extends dagger.hilt.android.HiltViewModel { *; }

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.Entity { *; }
-keep class * implements androidx.room.Dao { *; }

# DataStore
-keep class androidx.datastore.** { *; }

# JSch
-keep class com.jcraft.jsch.** { *; }

# DNSJava
-keep class org.xbill.DNS.** { *; }

# VPN Service + domain models
-keep class cn.srv0.sshinjector.vpn.** { *; }
-keep class cn.srv0.sshinjector.domain.model.** { *; }
-keep class cn.srv0.sshinjector.data.local.entity.** { *; }

# 忽略桌面/Windows 平台引用（Android 不存在）
-dontwarn com.sun.jna.**
-dontwarn java.net.spi.InetAddressResolverProvider
-dontwarn sun.net.spi.nameservice.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn lombok.**
-dontwarn org.xbill.DNS.spi.**

# 保留注解
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Serializable/Enum 不混淆
-keepnames class * implements java.io.Serializable
-keepnames class * extends java.lang.Enum

# L1: release 剥离 debug 日志 (Log.d),保留必要诊断日志 w/e/i
# 用户要求 release 不写 debug 日志;但 "已连接但无网络" 这类复现环境问题需要 i/w/e 才能排查,
# 故仅对 Log.d 加 -assumenosideeffects,让 R8 删掉 Log.d,保留其余。
-assumenosideeffects class android.util.Log {
    public static int d(...);
}
