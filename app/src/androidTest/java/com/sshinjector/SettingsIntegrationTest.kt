package com.sshinjector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sshinjector.data.local.preferences.SettingsDataStore
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
    fun `test auto connect setting`() = runBlocking {
        // Test reading default value
        val defaultValue = settingsDataStore.autoConnect.first()
        assertFalse(defaultValue)

        // Test setting value
        settingsDataStore.setAutoConnect(true)
        val updatedValue = settingsDataStore.autoConnect.first()
        assertTrue(updatedValue)

        // Reset
        settingsDataStore.setAutoConnect(false)
    }

    @Test
    fun `test notification setting`() = runBlocking {
        // Test reading default value
        val defaultValue = settingsDataStore.notificationEnabled.first()
        assertTrue(defaultValue) // Default is true

        // Test setting value
        settingsDataStore.setNotificationEnabled(false)
        val updatedValue = settingsDataStore.notificationEnabled.first()
        assertFalse(updatedValue)

        // Reset
        settingsDataStore.setNotificationEnabled(true)
    }

    @Test
    fun `test DNS mode setting`() = runBlocking {
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
