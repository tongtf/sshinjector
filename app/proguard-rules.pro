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
