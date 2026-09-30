# Env Check — 状态检测功能 (2026-09-30)

| 类别 | 结论 | 备注 |
|------|------|------|
| 工具链 | JDK 17 必须 (`/usr/lib/jvm/java-17-openjdk-amd64`)，Gradle 9.5 / AGP 9.3.1 / Kotlin 2.3 | AGENTS.md 既有约定，无需再确认 |
| 构建/验证命令 | `./gradlew testDebugUnitTest ktlintCheck detekt lint assembleDebug assembleRelease` | 上批次刚全量跑绿 |
| 依赖源 | settings.gradle.kts 腾讯/清华镜像优先，外网依赖（jitpack JSch）可达 | 无需变更 |
| 目录权限 | 仓库可写；`/tmp/opencode/` 可写（日志存档） | 已验证 |
| 设备 | Redmi K50 Pro `TCEIPN4DVWRSNNRK` 在线；release 包 `cn.srv0.sshinjector` 签名匹配可 `install -r` 保数据 | udev 已修 |
| 网络/远端 | forgejo `code.srv0.cn` **不可用**（用户确认：先不管 push/issue-ref）；origin=GitHub 可达 | push 阻塞搁置 |
| diagram-design skill | **未安装**（available_skills 仅 dev-workflow / customize-opencode） | 规划图改为轻量手写/表格，R/V 检测按内容自检 |
| 排期基准 | 起始日 D1 = 2026-09-30 | 甘特按日计 |
