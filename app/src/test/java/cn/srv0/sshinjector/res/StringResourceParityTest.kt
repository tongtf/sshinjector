package cn.srv0.sshinjector.res

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * L2-4 的机器化: 四份 `strings.xml` 的 key 必须一致。
 *
 * 规则:
 * - 默认 `values/` 里 `translatable="false"` 的条目**允许**只存在于默认文件
 *   (`settings_license_content` 就是这种情况 —— 人工核对容易误判成"漏翻译");
 * - 其余 key: 三个语言文件必须全部覆盖;
 * - 语言文件里不允许出现默认文件没有的孤儿 key (拼错 = 永远不生效)。
 */
class StringResourceParityTest {
    private val resDir = File("src/main/res")
    private val locales = listOf("values-en", "values-ru", "values-zh-rCN")

    private fun readStrings(file: File): Map<String, Boolean> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = LinkedHashMap<String, Boolean>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttribute("name")
            if (name.isEmpty()) continue
            out[name] = el.getAttribute("translatable").lowercase() != "false"
        }
        return out
    }

    @Test
    fun `localized files cover every translatable default key`() {
        val base = readStrings(File(resDir, "values/strings.xml"))
        assertTrue("default strings.xml 为空或路径不对", base.isNotEmpty())
        for (locale in locales) {
            val file = File(resDir, "$locale/strings.xml")
            assertTrue("$locale/strings.xml 不存在", file.isFile)
            val localized = readStrings(file)
            val missing = base.filterValues { it }.keys - localized.keys
            assertTrue("$locale 缺少默认文案: $missing", missing.isEmpty())
        }
    }

    @Test
    fun `localized files have no orphan keys`() {
        val base = readStrings(File(resDir, "values/strings.xml"))
        for (locale in locales) {
            val localized = readStrings(File(resDir, "$locale/strings.xml"))
            val orphan = localized.keys - base.keys
            assertTrue("$locale 有默认文件不存在的 key (拼错?): $orphan", orphan.isEmpty())
        }
    }
}
