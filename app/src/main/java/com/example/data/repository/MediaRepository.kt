package com.example.data.repository

import android.content.Context
import com.example.data.downloader.OfflineDownloadManager
import com.example.data.local.YMusicDatabase
import com.example.data.local.entities.DownloadEntity
import com.example.data.local.entities.PlaylistEntity
import com.example.data.local.entities.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow

class MediaRepository(context: Context) {

    private val db = YMusicDatabase.getInstance(context)
    private val playlistDao = db.playlistDao()
    private val downloadDao = db.downloadDao()
    val downloadManager = OfflineDownloadManager(context)

    // Playlists
    val allPlaylists: Flow<List<PlaylistEntity>> = playlistDao.getAllPlaylists()

    suspend fun createPlaylist(name: String, description: String = ""): Long {
        return playlistDao.insertPlaylist(
            PlaylistEntity(name = name, description = description)
        )
    }

    suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deleteItemsForPlaylist(playlistId)
        playlistDao.deletePlaylist(playlistId)
    }

    fun getPlaylistItems(playlistId: Long): Flow<List<PlaylistItemEntity>> {
        return playlistDao.getItemsForPlaylist(playlistId)
    }

    suspend fun addTrackToPlaylist(
        playlistId: Long,
        title: String,
        artist: String,
        duration: String,
        sourceUrl: String,
        thumbnailUrl: String,
        localFilePath: String? = null
    ): Long {
        return playlistDao.insertPlaylistItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                title = title,
                artist = artist,
                duration = duration,
                sourceUrl = sourceUrl,
                thumbnailUrl = thumbnailUrl,
                localFilePath = localFilePath
            )
        )
    }

    suspend fun removePlaylistItem(itemId: Long) {
        playlistDao.deletePlaylistItem(itemId)
    }

    suspend fun reorderPlaylistItem(itemId: Long, newOrder: Int) {
        playlistDao.updateItemOrder(itemId, newOrder)
    }

    // Downloads
    val allDownloads: Flow<List<DownloadEntity>> = downloadDao.getAllDownloads()
    val totalStorageUsed: Flow<Long?> = downloadDao.getTotalStorageUsed()

    suspend fun deleteDownload(download: DownloadEntity) {
        downloadManager.deleteDownload(download)
    }

    suspend fun clearAllDownloads() {
        downloadManager.clearAllDownloads()
    }
}
