package cn.srv0.sshinjector.domain.obs

import cn.srv0.sshinjector.ui.viewmodel.LogLevel
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级**失败归因**计数 (无 adb 现场的关键补强)。
 *
 * 问题: 应用内日志是唯一观测面, 但一次会话下来可能有几百行, 其中失败行散落各处
 * (背压丢段、回程窗口耗尽、0x03、池失败、TUN 写入失败…) —— 靠肉眼在导出的 txt 里数
 * "哪一类占主导"非常不可靠, 而"哪类占主导"恰恰决定排障方向。
 *
 * 做法: 在所有日志的唯一出口 ([cn.srv0.sshinjector.domain.usecase.VpnController.appLog] /
 * `appLogThrottled`) 上, 对 WARNING / ERROR 按**静态前缀**计数, 周期性输出一行汇总:
 *
 * ```
 * 故障归因 · 本会话 3 类 17 次 — 上行背压丢段 ×12 · 回程窗口耗尽 ×4 · TUN 写入失败 ×1
 * ```
 *
 * 三条纪律:
 * 1. **计数点在 [cn.srv0.sshinjector.domain.usecase.VpnController.addLog] —— 全仓唯一的落盘出口**
 *    (companion 的 `appLog` 也是经 `appLogSink` 转调它), 所以不会重复计数也不会漏。
 *    **口径是"打出来的失败行数"**: `appLogThrottled` 已把高频故障压到 10s 一条, 本项因此反映
 *    **相对占比** (哪一类占主导) 而非绝对次数 —— 绝对量看快照里的专用计数
 *    (背压丢段 B / 闸门阻塞 s / 零回程次数 / 孤儿包 / TUN 写丢弃)。
 * 2. **键必须静态** (`normalize` 把 ` — <细节>` 与 `·<域名>` 之后的部分切掉), 否则每个域名/异常
 *    各成一键, 汇总退化成"每类各 1 次"。同对象下的细分变体会合并成一类
 *    (`组包失败·SYN-ACK` 与 `组包失败·ACK` 同键) —— 归因只回答"从哪查起", 精确定位仍看正文。
 * 3. **不清表**: 满 [MAX_KEYS] 后**忽略新键**而不是清空 —— 清表等于把已归因的故障抹掉,
 *    而故障归因必须单调 (会话内只由 [resetSession] 清零)。
 *
 * 纯计数 + 纯字符串, 无 Android 依赖, 可单测。
 */
object AppMetrics {
    /** 汇总行展示的类别上限 (超出只显示"另 N 类")。 */
    private const val SUMMARY_TOP_N = 8

    /** 键上限: 键都是静态前缀, 64 个足够覆盖全部失败分支。 */
    private const val MAX_KEYS = 64

    /** 日志规范的分隔符: `<对象> · <事实> — <细节>` (U+00B7 中点 + 破折号)。 */
    private const val DOT = '\u00B7'
    private const val DASH = " — "

    private val counts = ConcurrentHashMap<String, Long>()

    /**
     * 记一次失败 (仅 WARNING / ERROR 计; INFO / SUCCESS / DEBUG 是生命周期与过程, 不是故障)。
     */
    fun record(
        level: LogLevel,
        key: String,
    ) {
        if (level != LogLevel.WARNING && level != LogLevel.ERROR) return
        val k = normalize(key)
        if (k.isEmpty()) return
        if (!counts.containsKey(k) && counts.size >= MAX_KEYS) return
        counts.merge(k, 1L) { a, b -> a + b }
    }

    /**
     * 汇总行 (按次数降序); 无故障时返回空串 —— 调用方据此跳过输出, 健康会话不产生噪声。
     */
    fun summary(topN: Int = SUMMARY_TOP_N): String {
        if (counts.isEmpty()) return ""
        val sorted =
            counts.entries.sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key },
            )
        val total = sorted.sumOf { it.value }
        val head = sorted.take(topN).joinToString(" · ") { "${it.key} ×${it.value}" }
        val more = if (sorted.size > topN) " · …另 ${sorted.size - topN} 类" else ""
        return "故障归因 · 本会话 ${sorted.size} 类 $total 次 — $head$more"
    }

    /** 会话开始 (VpnController.connect) 清零: "本会话"必须名副其实。 */
    fun resetSession() {
        counts.clear()
    }

    /**
     * 把任意日志文案归一到**静态前缀**:
     * `"上行背压丢段 ·a.example.com — 1448B"` → `"上行背压丢段"`;
     * `"TUN 写入失败 · java.io.IOException: …"` → `"TUN 写入失败"`。
     * 已归一化的键 (如 `throttleKey = "回程窗口耗尽"`) 保持不变 (幂等)。
     */
    internal fun normalize(key: String): String =
        key
            .substringBefore(DASH)
            .substringBefore(DOT)
            .trim()

    /** 单测用: 当前计数副本。 */
    internal fun countsForTest(): Map<String, Long> = counts.toMap()
}
