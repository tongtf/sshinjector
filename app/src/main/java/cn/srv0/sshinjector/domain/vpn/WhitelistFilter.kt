package cn.srv0.sshinjector.domain.vpn

/**
 * 白名单应用的**实际生效列表** (F2)。
 *
 * `VpnService.Builder.addAllowedApplication` 对两类包会抛 `NameNotFoundException`:
 * ① **本应用自己** (加进去会让 SSH/本地代理的流量再次进 TUN 形成回路);
 * ② **已卸载 / 对 VPN 进程不可见**的包 (应用被删、或包可见性规则不允许查询)。
 *
 * 旧实现只在 `buildVpnBuilder` 里逐条 catch，于是 `allowedPackages.size` 与真正放行的集合不一致:
 * `isWhitelistMode` 判"非空" → 加了 `0.0.0.0/0` 全量路由, 却一个应用都没被允许 →
 * **全部应用流量进 TUN 却没有出口** = 整机断网。过滤必须发生在 `whitelistSelection()`,
 * 让 TUN 路由判据与 DNS 传输策略 (`DnsTransportPolicy.dnsTransportFor`) 读**同一份**列表;
 * 过滤后为空则两边一起退化成"全部直连"。
 *
 * 纯函数: 不做 PackageManager 查询, 由调用方注入 [isInstalled] (便于单测)。
 *
 * @param packages 配置里启用的白名单包名 (可能含重复、自身、已卸载项)
 * @param ownPackage 本应用包名
 * @param isInstalled 查询包是否可见/已安装
 * @return 保序、去重、且真正可以 `addAllowedApplication` 的包名列表
 */
internal fun effectiveWhitelist(
    packages: List<String>,
    ownPackage: String,
    isInstalled: (String) -> Boolean,
): List<String> =
    packages
        .asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .filter { it != ownPackage && isInstalled(it) }
        .toList()
