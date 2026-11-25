package cn.srv0.sshinjector.domain.vpn.routing

/**
 * 运行中、但已不在"启用出口"集合里的出口 id。
 *
 * 服务器删除会由外键级联删掉 `proxy_exits` 行, 但运行中的隧道对象不会自己消失:
 * 不回收就是"端口 + 3 条 SSH 会话"跨会话泄漏, 且新连接仍可能被派发到它。
 * 纯函数, 单测覆盖三种边界 (部分过期 / 全部命中 / 全部过期)。
 */
internal fun staleExitIds(
    running: Collection<String>,
    desired: Set<String>,
): List<String> = running.filter { it !in desired }
