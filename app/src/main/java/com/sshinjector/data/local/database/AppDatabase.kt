package com.sshinjector.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sshinjector.data.local.converter.Converters
import com.sshinjector.data.local.dao.ServerDao
import com.sshinjector.data.local.dao.WhitelistDao
import com.sshinjector.data.local.entity.ServerEntity
import com.sshinjector.data.local.entity.WhitelistAppEntity

@Database(
    entities = [
        ServerEntity::class,
        WhitelistAppEntity::class,
    ],
version = 4,
        exportSchema = true
)
@TypeConverters(
    Converters::class
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun whitelistDao(): WhitelistDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN tunnelType TEXT NOT NULL DEFAULT 'socks5'")
                db.execSQL("ALTER TABLE servers ADD COLUMN tunnelConfigJson TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers DROP COLUMN tunnelType")
                db.execSQL("ALTER TABLE servers DROP COLUMN tunnelConfigJson")
                db.execSQL("ALTER TABLE servers ADD COLUMN socksPort INTEGER NOT NULL DEFAULT 1080")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sshinjector.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}