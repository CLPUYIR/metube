package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.local.dao.DownloadDao
import com.example.data.local.dao.PlaylistDao
import com.example.data.local.entities.DownloadEntity
import com.example.data.local.entities.PlaylistEntity
import com.example.data.local.entities.PlaylistItemEntity

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        DownloadEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class YMusicDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun downloadDao(): DownloadDao

    companion object {
        @Volatile
        private var INSTANCE: YMusicDatabase? = null

        fun getInstance(context: Context): YMusicDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    YMusicDatabase::class.java,
                    "ymusic_offline.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
