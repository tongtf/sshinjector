package cn.srv0.sshinjector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.srv0.sshinjector.data.local.preferences.SettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsIntegrationTest {
    private lateinit var context: Context
    private lateinit var settingsDataStore: SettingsDataStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settingsDataStore = SettingsDataStore(context)
    }

    @Test
    fun `test auto connect setting`() =
        runBlocking {
            // 不断言"默认值": DataStore 持久化, 上一次运行的 Reset 会留下显式存储值,
            // "读默认"在第二次运行必然不等于默认 (生产默认为 true)。只验证读写往返。
            settingsDataStore.setAutoConnect(false)
            assertFalse(settingsDataStore.autoConnect.first())

            settingsDataStore.setAutoConnect(true)
            assertTrue(settingsDataStore.autoConnect.first())
        }

    @Test
    fun `test DNS mode setting`() =
        runBlocking {
            // Test reading default value
            val defaultValue = settingsDataStore.dnsMode.first()
            assertEquals(0, defaultValue)

            // Test setting value
            settingsDataStore.setDnsMode(2)
            val updatedValue = settingsDataStore.dnsMode.first()
            assertEquals(2, updatedValue)

            // Reset
            settingsDataStore.setDnsMode(0)
        }
}
