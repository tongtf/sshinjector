package cn.srv0.sshinjector.domain.vpn

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 域名访问统计的 CSV 渲染器 (导出用)。
 *
 * 为什么单独抽成纯 JVM 对象 (而不是写在 ViewModel 里):
 * - 导出的**列顺序 / 表头 / 转义规则是对外契约** (Excel、脚本、用户都按它解析), 必须能逐字锁进单测;
 *   抽出来后不碰 Android, 单测直接比对字符串。
 * - 域名里出现逗号 / 引号虽然在 DNS 里罕见, 但一旦出现, 不按 RFC4180 转义就会让整行错列 ——
 *   静默产出坏 CSV (用户拿到的是"数据错位"而不是报错), 所以转义是硬要求而非锦上添花。
 *
 * 纯字符串拼装, 不读任何全局状态、不写日志、不起线程。
 */
internal object DomainStatsCsv {
    /** 列顺序是外部契约 (单测锁死); 改动等于改导出格式, 需同步文档。 */
    private const val HEADER =
        "domain,real_ip,real_ip2,is_ip_literal,conn_count,failed_conn_count," +
            "up_bytes,down_bytes,first_seen_ms,last_seen_ms,last_port,app_uids,app"

    /**
     * 应用列的分隔符 (uids 与 labels 各自内部): 纯 ASCII, 与 UI 的「应用」文本同形。
     * 空列表渲染成空字段; 多值会被 [escape] 按 RFC4180 加引号 (`"10500,10123"`)。
     */
    private const val APP_SEPARATOR = ","

    /** 文件名模板: 只含 ASCII 数字, 与地区/历法无关。 */
    private const val FILE_NAME_PATTERN = "'sshinjector-domains-'yyyyMMdd-HHmmss'.csv'"

    private const val NEWLINE = "\n"

    /** 单行预估字符数, 仅用于预分配 StringBuilder, 避免长列表反复扩容。 */
    private const val ESTIMATED_ROW_CHARS = 96

    /** 命中任一字符就必须按 RFC4180 加引号 (逗号分隔符 / 引号 / 两种换行)。 */
    private const val MUST_QUOTE_CHARS = ",\"\n\r"

    /**
     * 表头 + 每行一条 (RFC4180 转义), 行尾 `\n`。返回全文, 编码由调用方按 UTF-8 落盘。
     *
     * @param appLabels uid → 应用名 (④ 按应用归属); 由调用方 (带 Context 的展示层) 预先解析好 ——
     *   本对象不碰 Android, 缺失的 uid 按 `uid N` 渲染 (与页面「应用」列同一口径, 机器事实不隐藏)。
     */
    fun render(
        rows: List<DomainStatSnapshot>,
        appLabels: Map<Int, String> = emptyMap(),
    ): String {
        val sb = StringBuilder(HEADER.length + NEWLINE.length + rows.size * ESTIMATED_ROW_CHARS)
        sb.append(HEADER).append(NEWLINE)
        for (row in rows) {
            appendRow(sb, row, appLabels)
        }
        return sb.toString()
    }

    /** `sshinjector-domains-yyyyMMdd-HHmmss.csv`。用 Locale.US 固定数字字形, 不随设备语言变化。 */
    fun fileName(nowMs: Long): String = SimpleDateFormat(FILE_NAME_PATTERN, Locale.US).format(Date(nowMs))
    // 注意: 字面量必须用单引号包起来 —— 否则 `sshinjector` 里的 `i` 会被 SimpleDateFormat 当成模式字母
    // (抛 `IllegalArgumentException: Illegal pattern character 'i'`)。

    private fun appendRow(
        sb: StringBuilder,
        row: DomainStatSnapshot,
        appLabels: Map<Int, String>,
    ) {
        sb.append(escape(row.domain)).append(',')
        sb.append(escape(row.realIp ?: "")).append(',')
        sb.append(escape(row.realIp2 ?: "")).append(',')
        sb.append(row.isIpLiteral).append(',')
        sb.append(row.connCount).append(',')
        sb.append(row.failedConnCount).append(',')
        sb.append(row.upBytes).append(',')
        sb.append(row.downBytes).append(',')
        sb.append(row.firstSeenAt).append(',')
        sb.append(row.lastSeenAt).append(',')
        sb.append(row.lastPort).append(',')
        // 应用两列放在**末尾**: 老消费者按列号解析 0..10 不受影响 (契约只增不改)。
        // 空槽 (未反查到 uid) 从列表里滤掉, 因此"没反查到"渲染成空字段, 与页面一致。
        val uids = appUids(row)
        sb
            .append(escape(uids.joinToString(APP_SEPARATOR)))
            .append(',')
            .append(escape(uids.joinToString("$APP_SEPARATOR ") { appLabels[it] ?: "uid $it" }))
            .append(NEWLINE)
    }

    /** 快照里的 uid 槽 (最多 3), 无序语义 (一域名被多个应用用过)。 */
    private fun appUids(row: DomainStatSnapshot): List<Int> =
        listOf(row.appUid1, row.appUid2, row.appUid3)
            .filter { it >= 0 }

    /**
     * RFC4180: 只有含逗号 / 引号 / 换行的字段才用双引号包裹, 内部引号翻倍;
     * 其余字段原样输出 (不无脑加引号 —— 否则所有行都带引号, 与"标准 CSV"的预期不符)。
     */
    private fun escape(field: String): String {
        if (field.none { it in MUST_QUOTE_CHARS }) return field

        val sb = StringBuilder(field.length + 2)
        sb.append('"')
        for (c in field) {
            if (c == '"') sb.append("\"\"") else sb.append(c)
        }
        sb.append('"')
        return sb.toString()
    }
}
