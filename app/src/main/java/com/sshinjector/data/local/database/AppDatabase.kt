package com.sshinjector.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
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
    version = 2,
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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sshinjector.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}