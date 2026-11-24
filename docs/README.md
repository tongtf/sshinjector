# 文档索引

本页说明 `docs/` 下各文档的定位。**当前架构与行为的唯一权威来源是仓库根目录的 [AGENTS.md](../AGENTS.md)**；本文只负责指路。

## 活文档（按需更新，可作为当前用法依据）

| 文档 | 用途 |
|------|------|
| [../README.md](../README.md) / [README.en.md](../README.en.md) / [README.ru.md](../README.ru.md) | 项目概览、功能、快速开始、**一键服务端配置入口**、构建与测试 |
| [USER_GUIDE.md](USER_GUIDE.md) | 安装、服务器管理（含一键配置向导 4 步）、白名单、连接状态、设置、域名统计、日志、排障、FAQ |
| [server-setup.md](server-setup.md) | 服务端配置：一键配置内部流程、手动命令、OpenSSH/dropbear 差异、验证与回滚 |
| [specs/2026-10-18-device-verification-checklist.md](specs/2026-10-18-device-verification-checklist.md) | **数据面改动的唯一回归矩阵**（场景 S0–S17 + 阈值速查）。新增计数器/告警行必须同步更新它 |

## 辅助理解（时点快照，不是事实来源）

| 文档 | 状态 |
|------|------|
| [diagrams/index.html](diagrams/index.html) | 架构/流程/状态机/时序图集。**最后同步 2026-10-08**，此后 10-18/10-19 数据面与统计改造未全部回灌；部分图有事实错误（以 AGENTS.md 为准）。入口页与 [review-checklist.md](diagrams/review-checklist.md) 已标注当前漂移 |
| [diagrams/*.html](diagrams/) | 14 张图，覆盖系统架构、包流、TCP、DNS、VPN 建连/状态、SOCKS5 状态、SSH 重连、密钥、一键配置、甘特等；同上，仅作辅助 |
| [evidence/2026-09-30-status-chain.md](evidence/2026-09-30-status-chain.md) | 一次排障取证记录（时点材料） |

## 历史记录（设计时点快照，勿当现状）

`specs/` 下除回归清单外的文档，是某次审计/设计/施工的**时点记录**：其中的 file:line、勾选状态、数量统计、方案选型都反映当时的代码，很多条目已经实施、被替代或废弃。每篇开头已加统一状态横幅，**不要按它们判断当前是否仍存在问题**。

| 文档 | 当时主题 | 现状提示 |
|------|----------|----------|
| `specs/2026-09-29-review-fixes-spec.md` | review-fixes 方案 | 已实施 |
| `specs/2026-09-30-connectivity-health-spec.md` | 连通性健康首版设计 | 已扩展 `FORWARD`、分阶段健康、出口 IP 回显；首字节 EOF 现归 TUNNEL |
| `specs/2026-10-18-defect-audit.md` | 缺陷审计 | 已修 24/24 + 故意遗留若干 |
| `specs/2026-10-18-fix-design.md` / `fix-todo.md` | 缺陷修复设计/施工图 | 已实施 |
| `specs/2026-10-18-architecture-review.md` / `arch-optimization-design.md` / `arch-optimization-todo.md` | 架构评审与 F-01…F-05 优化 | F-01/F-02 全量、F-03 C-2、F-04 第一刀已落地 |
| `specs/2026-10-19-domain-access-stats-design.md` | 域名统计设计 | P0/P1、远端解析、应用归属均已落地；设计中 exec 方案已被 direct-tcpip 53/TCP 取代 |
| `specs/2026-10-19-domain-stats*.md` | 域名统计各轮 TODO/审计 | 多轮均已完成 |

## 维护约定

- 改了**用户可见行为或 UI 路径** → 更新三语 README + USER_GUIDE（必要时 server-setup）
- 改了**数据面/状态面** → 更新 AGENTS.md 架构说明 + 回归清单；图集能补则补，补不了就在 index 标注漂移
- 新增文档先决定它属于「活文档」还是「历史记录」，后者必须在开头加状态横幅
- 三语 README 的构建产物路径一律用通配符/占位符，不写死版本号与 ABI 名
