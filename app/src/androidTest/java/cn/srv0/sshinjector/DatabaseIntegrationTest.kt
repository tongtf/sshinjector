package cn.srv0.sshinjector

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.database.AppDatabase
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var serverDao: ServerDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        serverDao = db.serverDao()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `test server CRUD`() = runBlocking {
        // Create
        val server = ServerEntity(
            name = "Test Server",
            host = "example.com",
            port = 22,
            username = "user",
            keyAlias = "test_key",
            isActive = false
        )
        val id = serverDao.insert(server)
        assertTrue(id > 0)

        // Read
        val loaded = serverDao.getServerById(id)
        assertNotNull(loaded)
        assertEquals("Test Server", loaded?.name)
        assertEquals("example.com", loaded?.host)

        // Update
        val updated = loaded!!.copy(name = "Updated Server")
        serverDao.update(updated)
        val loadedAfterUpdate = serverDao.getServerById(id)
        assertEquals("Updated Server", loadedAfterUpdate?.name)

        // Delete
        serverDao.delete(updated)
        val loadedAfterDelete = serverDao.getServerById(id)
        assertNull(loadedAfterDelete)
    }

    @Test
    fun `test server list`() = runBlocking {
        // Insert multiple servers
        val server1 = ServerEntity(name = "Server 1", host = "host1.com", port = 22, username = "user1", keyAlias = "key1")
        val server2 = ServerEntity(name = "Server 2", host = "host2.com", port = 22, username = "user2", keyAlias = "key2")
        serverDao.insert(server1)
        serverDao.insert(server2)

        // Get all
        val servers = serverDao.getAllServers()
        assertEquals(2, servers.size)

        // Get active
        val activeServers = serverDao.getActiveServers()
        assertEquals(0, activeServers.size)

        // Cleanup
        serverDao.delete(server1)
        serverDao.delete(server2)
    }
}
