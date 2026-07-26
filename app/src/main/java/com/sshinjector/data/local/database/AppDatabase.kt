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
    version = 3,
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sshinjector.db"
                )
                    .addMigrations(MIGRATION_2_3)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}