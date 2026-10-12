package com.example.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "playlist_items")
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val playlistId: Long,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val duration: String = "0:00",
    val sourceUrl: String = "",
    val localFilePath: String? = null,
    val thumbnailUrl: String = "",
    val orderIndex: Int = 0
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String = "",
    val duration: String = "0:00",
    val sourceUrl: String = "",
    val localFilePath: String,
    val thumbnailUrl: String = "",
    val fileSizeBytes: Long = 0L,
    val status: String = "COMPLETED", // PENDING, DOWNLOADING, COMPLETED, FAILED
    val progressPercent: Int = 100,
    val downloadedAt: Long = System.currentTimeMillis()
)
