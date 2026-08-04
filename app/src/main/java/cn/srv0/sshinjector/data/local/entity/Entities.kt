package cn.srv0.sshinjector.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import java.util.Date

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "port") val port: Int = 22,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "keyAlias") val keyAlias: String, // Android Keystore 别名
    @ColumnInfo(name = "keyAlgorithm") val keyAlgorithm: String = "Ed25519",
    @ColumnInfo(name = "keyPassphrase") val keyPassphrase: String? = null, // 加密存储在 DataStore
    @ColumnInfo(name = "password") val password: String? = null,
    @ColumnInfo(name = "isActive") val isActive: Boolean = false,
    @ColumnInfo(name = "mtu") val mtu: Int = 1500,
    @ColumnInfo(name = "keepAliveInterval") val keepAliveInterval: Int = 30, // 秒
    @ColumnInfo(name = "enableIPv6") val enableIPv6: Boolean = true,
    @ColumnInfo(name = "dnsMode") val dnsMode: DnsMode = DnsMode.REMOTE,
    @ColumnInfo(name = "remoteDnsServer") val remoteDnsServer: String = "8.8.8.8",
    @ColumnInfo(name = "hostKeyFingerprint") val hostKeyFingerprint: String? = null, // SSH Host Key 指纹
    @ColumnInfo(name = "allowedPackages") val allowedPackages: String? = null, // JSON array
    @ColumnInfo(name = "excludedRoutes") val excludedRoutes: String? = null, // JSON array
    @ColumnInfo(name = "socksPort") val socksPort: Int = 1080, // 本地 SOCKS5 监听端口
    @ColumnInfo(name = "createdAt") val createdAt: Date = Date(),
    @ColumnInfo(name = "updatedAt") val updatedAt: Date = Date()
) {
    companion object {
        fun create(
            name: String,
            host: String,
            port: Int,
            username: String,
            keyAlias: String,
            keyPassphrase: String? = null
        ) = ServerEntity(
            name = name,
            host = host,
            port = port,
            username = username,
            keyAlias = keyAlias,
            keyPassphrase = keyPassphrase
        )
    }
}

enum class DnsMode {
    REMOTE,  // 远端解析 (默认)
    LOCAL,   // 本地解析
    SYSTEM,  // 系统 DNS
    SPLIT    // 白名单分流 (白名单远端, 非白名单本地)
}

@Entity(tableName = "whitelist_apps", primaryKeys = ["packageName"])
data class WhitelistAppEntity(
    @ColumnInfo(name = "packageName") val packageName: String,
    @ColumnInfo(name = "appName") val appName: String,
    @ColumnInfo(name = "iconPackage") val iconPackage: String? = null,
    @ColumnInfo(name = "isEnabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "addedAt") val addedAt: Date = Date()
)