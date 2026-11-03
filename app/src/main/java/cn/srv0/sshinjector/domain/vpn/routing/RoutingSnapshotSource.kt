package cn.srv0.sshinjector.domain.vpn.routing

/**
 * 数据面读取路由快照的**唯一接口** (设计 §8.10)。
 *
 * 为什么是接口而不是直接依赖 `RoutingTableStore`:
 * - 数据面只需要"当前快照"这一个能力, 而 `RoutingTableStore` 还带 DataStore / Room 依赖 ——
 *   直接用具体类会让 `TcpStateMachine` 的单测无法注入快照, 于是"命中规则后到底走了哪个出口"
 *   这条**最核心的新行为**没有确定性用例 (只能靠真机);
 * - 实现方可以换 (会话级 store / 测试替身), 数据面不用改。
 */
fun interface RoutingSnapshotSource {
    /** 当前快照; 一定是不可变对象, 调用方读一次即可 (不要跨调用缓存)。 */
    fun current(): RoutingSnapshot
}
