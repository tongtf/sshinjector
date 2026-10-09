package cn.srv0.sshinjector.ui.viewmodel

/**
 * 应用内日志级别 (排障主要靠应用内日志 — 机器无 adb, logcat 看不到)。
 * 详见 AGENTS.md「应用内日志级别规范」。
 */
enum class LogLevel {
    INFO,
    DEBUG,
    SUCCESS,
    ERROR,
    WARNING,
}
