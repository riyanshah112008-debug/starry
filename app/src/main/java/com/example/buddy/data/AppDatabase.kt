package com.example.buddy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.buddy.data.dao.ActionLogDao
import com.example.buddy.data.dao.ChatDao
import com.example.buddy.data.dao.MemoryDao
import com.example.buddy.data.dao.PreferenceDao
import com.example.buddy.data.entity.ActionLog
import com.example.buddy.data.entity.ChatMessage
import com.example.buddy.data.entity.Memory
import com.example.buddy.data.entity.UserPreference

@Database(
    entities = [
        ChatMessage::class,
        ActionLog::class,
        Memory::class,
        UserPreference::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun actionLogDao(): ActionLogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun preferenceDao(): PreferenceDao

    companion object {
        private const val DB_NAME = "buddy_agent.db"

        @Volatile
        private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
