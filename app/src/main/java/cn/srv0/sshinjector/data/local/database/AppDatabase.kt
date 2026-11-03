package cn.srv0.sshinjector.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.srv0.sshinjector.data.local.converter.Converters
import cn.srv0.sshinjector.data.local.dao.ProxyExitDao
import cn.srv0.sshinjector.data.local.dao.ServerDao
import cn.srv0.sshinjector.data.local.dao.WhitelistDao
import cn.srv0.sshinjector.data.local.entity.ProxyExitEntity
import cn.srv0.sshinjector.data.local.entity.ServerEntity
import cn.srv0.sshinjector.data.local.entity.WhitelistAppEntity

@Database(
    entities = [
        ServerEntity::class,
        WhitelistAppEntity::class,
        ProxyExitEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(
    Converters::class,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    abstract fun whitelistDao(): WhitelistDao

    abstract fun proxyExitDao(): ProxyExitDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE servers ADD COLUMN tunnelType TEXT NOT NULL DEFAULT 'socks5'")
                    db.execSQL("ALTER TABLE servers ADD COLUMN tunnelConfigJson TEXT DEFAULT NULL")
                }
            }

        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE servers DROP COLUMN tunnelType")
                    db.execSQL("ALTER TABLE servers DROP COLUMN tunnelConfigJson")
                    db.execSQL("ALTER TABLE servers ADD COLUMN socksPort INTEGER NOT NULL DEFAULT 1080")
                }
            }

        private val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(ProxyExitSchema.CREATE_TABLE)
                    db.execSQL(ProxyExitSchema.INDEX_NAME)
                    db.execSQL(ProxyExitSchema.INDEX_SERVER_ID)
                }
            }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                val created =
                    Room
                        .databaseBuilder(
                            context.applicationContext,
                            AppDatabase::class.java,
                            "sshinjector.db",
                        ).addMigrations(MIGRATION_2_3)
                        .addMigrations(MIGRATION_3_4)
                        .addMigrations(MIGRATION_4_5)
                        .build()
                instance = created
                created
            }
    }
}
