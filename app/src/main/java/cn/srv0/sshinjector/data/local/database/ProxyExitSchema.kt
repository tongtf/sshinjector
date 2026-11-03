package cn.srv0.sshinjector.data.local.database

/**
 * 4 → 5 迁移的建表 DDL (多出口路由, 设计 §7.1)。
 *
 * 抽成常量是为了让 `ProxyExitSchemaTest` 直接与导出的 `5.json` 逐字比对 —— 手写迁移与
 * 实体定义一旦漂移, 真机升级时 Room 会抛
 * `IllegalStateException: Migration didn't properly handle: proxy_exits` (崩溃且无补救)。
 *
 * **不含 DEFAULT 子句**: Room 打开 DB 时对比 `TableInfo`, 多一个 `DEFAULT 1` 就判不匹配。
 * 设计 §7.1 的示例带 DEFAULT, 那是文档笔误, 以导出 schema 为准。
 */
internal object ProxyExitSchema {
    const val CREATE_TABLE =
        "CREATE TABLE IF NOT EXISTS `proxy_exits` (" +
            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL, " +
            "`serverId` INTEGER NOT NULL, " +
            "`enabled` INTEGER NOT NULL, " +
            "`priority` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, " +
            "`updatedAt` INTEGER NOT NULL, " +
            "FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE CASCADE )"

    const val INDEX_NAME =
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_proxy_exits_name` ON `proxy_exits` (`name`)"

    const val INDEX_SERVER_ID =
        "CREATE INDEX IF NOT EXISTS `index_proxy_exits_serverId` ON `proxy_exits` (`serverId`)"
}
