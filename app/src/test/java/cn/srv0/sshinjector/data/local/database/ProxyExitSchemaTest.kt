package cn.srv0.sshinjector.data.local.database

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 迁移 DDL 与导出 schema 的一致性 (设计 §7.1 / §7.4)。
 *
 * 这是**唯一**能在无真机的 CI 里发现"手写迁移与实体定义漂移"的地方: Room 打开升级后的 DB 时
 * 用 `TableInfo` 对比, 列多一个 DEFAULT / 索引名写错都会抛
 * `IllegalStateException: Migration didn't properly handle`, 而且发生在用户升级那一刻 ——
 * 崩了就无法回滚。`androidTest` 的 `MigrationTestHelper` 不跑 CI, 所以用这个逐字比对兜底。
 *
 * 比对方式: 导出 json 里的 `createSql` 用 `${TABLE_NAME}` 占位, 且 SQL 里没有双引号 (只有反引号),
 * 因此在原始文件文本里做子串匹配即可, 不需要 JSON 解析 (单元测试里的 `org.json` 是抛异常的桩)。
 */
class ProxyExitSchemaTest {
    private fun schemaText(): String {
        val file = File("schemas/cn.srv0.sshinjector.data.local.database.AppDatabase/5.json")
        assertTrue("缺少导出 schema: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    @Test
    fun `migration creates exactly the exported table`() {
        val expected = ProxyExitSchema.CREATE_TABLE.replace("`proxy_exits`", "`\${TABLE_NAME}`")
        assertTrue(
            "迁移 DDL 与 5.json 的 createSql 不一致 (Room 会在升级时抛 Migration didn't properly handle)",
            schemaText().contains(expected),
        )
    }

    @Test
    fun `migration creates exactly the exported indices`() {
        val text = schemaText()
        listOf(
            ProxyExitSchema.INDEX_NAME,
            ProxyExitSchema.INDEX_SERVER_ID,
        ).forEach { sql ->
            assertTrue("迁移缺少导出 schema 里的索引: $sql", text.contains(sql.replace("`proxy_exits`", "`\${TABLE_NAME}`")))
        }
    }
}
