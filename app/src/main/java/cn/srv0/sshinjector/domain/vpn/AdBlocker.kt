package cn.srv0.sshinjector.domain.vpn

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 广告 / 追踪域名过滤
 *
 * 持有已编译的 [GfwListMatcher] (内置清单 assets/adblock.txt + 用户自定义域名,
 * AdGuard/gfwlist 风格) 与顶层总开关。[isBlocked] 在 DNS 查询判定走隧道 / 系统 DNS
 * **之前**被调用, 命中即返回 true → [DnsInterceptor] 回 0.0.0.0, 不解析、不进缓存、
 * 不消耗任何通道资源。该检查对全部 DNS 传输模式 (REMOTE/SYSTEM/WHITELIST/DOMAIN_SPLIT)
 * 统一生效。
 *
 * 无 Android 依赖: matcher 由 [setMatcher] 注入 (连接时由 VpnController 加载后设置),
 * 便于 JVM 单测直接构造验证匹配逻辑。
 */
@Singleton
class AdBlocker
    @Inject
    constructor() {
        @Volatile private var matcher: GfwListMatcher = GfwListMatcher.parse("")

        // 顶层总开关, 由 DnsInterceptor.setEnabledAdBlock 在连接时注入; 关闭时直接放行。
        @Volatile private var enabled = true

        fun setEnabled(enabled: Boolean) {
            this.enabled = enabled
            Log.d(TAG, "AdBlocker enabled=$enabled")
        }

        /**
         * 注入已编译的规则匹配器。连接时由 VpnController 读取内置清单 + 用户自定义域名后调用。
         */
        internal fun setMatcher(matcher: GfwListMatcher) {
            this.matcher = matcher
            Log.d(TAG, "AdBlocker matcher loaded, ruleCount=${matcher.ruleCount}")
        }

        /**
         * 命中广告域名返回 true。仅接受主机名; IP 字面量 / .arpa 反向解析视为未命中 (避免误判)。
         */
        fun isBlocked(host: String): Boolean {
            if (!enabled) return false
            val h = host.trim().lowercase().trimEnd('.')
            if (h.isEmpty()) return false
            // 跳过 IP 字面量与反向 DNS, 避免无意义匹配 / 误杀
            if (IPV4_REGEX.matches(h) || h.endsWith(".arpa")) return false
            return matcher.matches(h)
        }

        private companion object {
            const val TAG = "AdBlocker"
            private val IPV4_REGEX = Regex("^\\d{1,3}(\\.\\d{1,3}){3}")
        }
    }
