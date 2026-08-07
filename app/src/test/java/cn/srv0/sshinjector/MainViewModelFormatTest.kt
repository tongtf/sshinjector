package cn.srv0.sshinjector

import cn.srv0.sshinjector.ui.viewmodel.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelFormatTest {
    @Test
    fun `formatBytes handles bytes and negatives`() {
        assertEquals("0 B", MainViewModel.formatBytes(0))
        assertEquals("1023 B", MainViewModel.formatBytes(1023))
        assertEquals("-", MainViewModel.formatBytes(-1))
    }

    @Test
    fun `formatBytes handles kb mb gb`() {
        assertEquals("1.0 KB", MainViewModel.formatBytes(1024))
        assertEquals("1.5 KB", MainViewModel.formatBytes(1536))
        assertEquals("1.0 MB", MainViewModel.formatBytes(1024L * 1024))
        assertEquals("1.50 GB", MainViewModel.formatBytes(1024L * 1024 * 1024 + 1024L * 1024 * 1024 / 2))
    }

    @Test
    fun `formatDuration handles seconds and minutes`() {
        assertEquals("00:00:00", MainViewModel.formatDuration(0))
        assertEquals("00:00:59", MainViewModel.formatDuration(59_000))
        assertEquals("00:59:59", MainViewModel.formatDuration(3_599_000))
        assertEquals("01:00:00", MainViewModel.formatDuration(3_600_000))
    }

    @Test
    fun `formatDuration handles days and negatives`() {
        assertEquals("1d 01:01:00", MainViewModel.formatDuration(86_400_000L + 3_660_000L))
        assertEquals("-", MainViewModel.formatDuration(-1))
    }
}
