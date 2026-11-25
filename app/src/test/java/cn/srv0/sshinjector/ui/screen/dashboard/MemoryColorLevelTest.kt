package cn.srv0.sshinjector.ui.screen.dashboard

import cn.srv0.sshinjector.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 状态卡内存档位: 曾经外层判 `" MB"`、内层判 `" GB"`, 内层永远为假 → 任何 ≥1GB 的堆都算 0MB
 * (恒绿)。这里锁三件事: MB 换算、**GB 必须先换算成 MB 再比较**、不可解析不误报。
 *
 * 阈值: 比值 = MB / 50; < 0.5 绿 / < 1.0 黄 / 其余红 (见 [MainViewModel.ratioLevel])。
 */
class MemoryColorLevelTest {
    @Test
    fun `mb text below the warning threshold stays ok`() {
        assertEquals(MainViewModel.RatioLevel.OK, memoryColorLevel("10.0 MB"))
        assertEquals(MainViewModel.RatioLevel.OK, memoryColorLevel("24.9 MB"))
        assertEquals(MainViewModel.RatioLevel.WARNING, memoryColorLevel("30.0 MB"))
    }

    @Test
    fun `gb text is converted to mb before comparing`() {
        // 老实现里内层 `contains(" GB")` 在 `contains(" MB")` 为真的分支内永假 → 这里恒 OK
        assertEquals(MainViewModel.RatioLevel.BAD, memoryColorLevel("1.5 GB"))
        assertEquals(MainViewModel.RatioLevel.BAD, memoryColorLevel("1.0 GB"))
        // 小 GB 也要真的换算: 0.02 GB = 20.5 MB → 绿
        assertEquals(MainViewModel.RatioLevel.OK, memoryColorLevel("0.02 GB"))
    }

    @Test
    fun `unparsable text never reports a bad level`() {
        assertEquals(MainViewModel.RatioLevel.OK, memoryColorLevel("-"))
        assertEquals(MainViewModel.RatioLevel.OK, memoryColorLevel(""))
        assertEquals(MainViewModel.RatioLevel.OK, memoryColorLevel("NaN MB"))
        assertEquals(MainViewModel.RatioLevel.OK, memoryColorLevel("1024"))
    }
}
