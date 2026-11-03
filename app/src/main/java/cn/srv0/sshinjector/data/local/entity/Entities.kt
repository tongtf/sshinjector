package cn.srv0.sshinjector.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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
    @ColumnInfo(name = "keyPassphrase") val keyPassphrase: String? = null, // 遗留字段, 私钥 passphrase 由 SshKeyManager 独立管理
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
    @ColumnInfo(name = "updatedAt") val updatedAt: Date = Date(),
)

enum class DnsMode {
    REMOTE, // 远端解析 (默认)
    LOCAL, // 本地解析
    SYSTEM, // 系统 DNS
    SPLIT, // 白名单分流 (白名单远端, 非白名单本地)
}

@Entity(tableName = "whitelist_apps", primaryKeys = ["packageName"])
data class WhitelistAppEntity(
    @ColumnInfo(name = "packageName") val packageName: String,
    @ColumnInfo(name = "appName") val appName: String,
    @ColumnInfo(name = "iconPackage") val iconPackage: String? = null,
    @ColumnInfo(name = "isEnabled") val isEnabled: Boolean = true,
    @ColumnInfo(name = "addedAt") val addedAt: Date = Date(),
)

/**
 * 附加出口 (多出口路由, 设计 §7.1)。主出口不在这张表里 —— 它恒等于"当前连接的服务器"。
 *
 * 只存**引用** ([serverId]), 绝不复制 host/port/username: 服务器详情是 `servers` 的真相源,
 * 复制会出现"改了服务器地址, 出口还用旧地址"的分叉。删除服务器由外键级联删掉出口。
 *
 * [name] 是规则里引用的名字 (`example.com -> hk` 的 `hk`), 小写唯一; 改名不影响规则文本
 * (改名前 UI 必须提示"有 N 条规则引用它")。
 */
@Entity(
    tableName = "proxy_exits",
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["serverId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["name"], unique = true), Index("serverId")],
)
data class ProxyExitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "serverId") val serverId: Long,
    @ColumnInfo(name = "enabled") val enabled: Boolean = true,
    @ColumnInfo(name = "priority") val priority: Int = 100,
    @ColumnInfo(name = "createdAt") val createdAt: Date = Date(),
    @ColumnInfo(name = "updatedAt") val updatedAt: Date = Date(),
)
