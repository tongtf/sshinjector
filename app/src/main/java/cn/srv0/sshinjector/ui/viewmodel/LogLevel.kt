package cn.srv0.sshinjector.ui.viewmodel

import androidx.annotation.StringRes
import cn.srv0.sshinjector.R

/**
 * 应用内日志级别 (排障主要靠应用内日志 — 机器无 adb, logcat 看不到)。
 * 详见 AGENTS.md「应用内日志级别规范」。
 *
 * [labelRes] 只服务 UI chip 的展示; **`.name` 仍是机器口径** (日志行前缀与导出文件里的
 * `[WARNING]` 等) —— 两者不能互相替代, 否则导出证据的级别标识会随语言漂移。
 */
enum class LogLevel(
    @StringRes val labelRes: Int,
) {
    INFO(R.string.log_level_info),
    DEBUG(R.string.log_level_debug),
    SUCCESS(R.string.log_level_success),
    ERROR(R.string.log_level_error),
    WARNING(R.string.log_level_warning),
}
