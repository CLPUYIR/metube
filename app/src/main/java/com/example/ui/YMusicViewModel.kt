package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.JsMediaBridge
import com.example.MediaPlaybackService
import com.example.TrackMetadata
import com.example.data.local.entities.DownloadEntity
import com.example.data.local.entities.PlaylistEntity
import com.example.data.local.entities.PlaylistItemEntity
import com.example.data.downloader.OfflineDownloadService
import com.example.data.repository.MediaRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class MainNavTab {
    STREAM,
    PLAYLISTS,
    DOWNLOADS
}

class YMusicViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)

    val playlists: StateFlow<List<PlaylistEntity>> = repository.allPlaylists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloads: StateFlow<List<DownloadEntity>> = repository.allDownloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalStorageUsed: StateFlow<Long> = repository.totalStorageUsed
        .map { it ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val currentDownloadProgress: StateFlow<Int?> = OfflineDownloadService.currentDownload
        .map { status -> status?.takeIf { !it.isCompleted && !it.isFailed }?.progress }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selectedPlaylist = MutableStateFlow<PlaylistEntity?>(null)
    val selectedPlaylist = _selectedPlaylist.asStateFlow()

    private val _playlistItems = MutableStateFlow<List<PlaylistItemEntity>>(emptyList())
    val playlistItems = _playlistItems.asStateFlow()

    // Offline queue management
    private var offlineQueue = listOf<PlaylistItemEntity>()
    private var currentQueueIndex = -1

    init {
        // Pre-seed a welcome playlist if empty on first launch
        viewModelScope.launch {
            playlists.first() // wait for initial emission
            if (playlists.value.isEmpty()) {
                val pId = repository.createPlaylist("Favorites", "My saved songs")
                repository.createPlaylist("Chill & Lo-Fi", "Relaxing vibes")
            }
        }
    }

    fun selectPlaylist(playlist: PlaylistEntity?) {
        _selectedPlaylist.value = playlist
        if (playlist != null) {
            viewModelScope.launch {
                repository.getPlaylistItems(playlist.id).collect { items ->
                    _playlistItems.value = items
                }
            }
        } else {
            _playlistItems.value = emptyList()
        }
    }

    fun createPlaylist(name: String, description: String = "", onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            if (name.isNotBlank()) {
                val newId = repository.createPlaylist(name.trim(), description.trim())
                onCreated?.invoke(newId)
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            repository.deletePlaylist(playlistId)
            if (_selectedPlaylist.value?.id == playlistId) {
                _selectedPlaylist.value = null
                _playlistItems.value = emptyList()
            }
        }
    }

    fun addTrackToPlaylist(
        playlistId: Long,
        track: TrackMetadata,
        localFilePath: String? = null
    ) {
        viewModelScope.launch {
            val resolvedLocalPath = localFilePath ?: downloads.value.firstOrNull {
                it.title.equals(track.title, ignoreCase = true) || (track.url.isNotEmpty() && it.sourceUrl == track.url)
            }?.localFilePath

            repository.addTrackToPlaylist(
                playlistId = playlistId,
                title = track.title.ifEmpty { "Track" },
                artist = track.artist.ifEmpty { "MeTube" },
                duration = track.duration,
                sourceUrl = track.url,
                thumbnailUrl = track.thumbnailUrl,
                localFilePath = resolvedLocalPath
            )
        }
    }

    fun addDownloadToPlaylist(
        playlistId: Long,
        download: DownloadEntity
    ) {
        viewModelScope.launch {
            repository.addTrackToPlaylist(
                playlistId = playlistId,
                title = download.title,
                artist = download.artist,
                duration = download.duration,
                sourceUrl = download.sourceUrl,
                thumbnailUrl = download.thumbnailUrl,
                localFilePath = download.localFilePath
            )
        }
    }

    fun removePlaylistItem(itemId: Long) {
        viewModelScope.launch {
            repository.removePlaylistItem(itemId)
        }
    }

    fun movePlaylistItem(itemId: Long, currentOrder: Int, direction: Int) {
        viewModelScope.launch {
            val items = _playlistItems.value
            val currentIndex = items.indexOfFirst { it.id == itemId }
            val targetIndex = currentIndex + direction
            if (currentIndex in items.indices && targetIndex in items.indices) {
                val currentItem = items[currentIndex]
                val targetItem = items[targetIndex]
                repository.reorderPlaylistItem(currentItem.id, targetItem.orderIndex)
                repository.reorderPlaylistItem(targetItem.id, currentItem.orderIndex)
            }
        }
    }

    fun downloadTrack(
        title: String,
        artist: String,
        duration: String,
        sourceUrl: String,
        thumbnailUrl: String,
        directStreamUrl: String = "",
        formatOption: com.example.ui.components.DownloadFormatOption = com.example.ui.components.DownloadFormatOption.AUDIO_M4A,
        isVideo: Boolean = formatOption.isVideo,
        onComplete: (Boolean) -> Unit = {}
    ) {
        val resolvedOption = if (isVideo && !formatOption.isVideo) {
            com.example.ui.components.DownloadFormatOption.VIDEO_720P
        } else {
            formatOption
        }

        try {
            OfflineDownloadService.startDownload(
                context = getApplication(),
                title = title,
                artist = artist,
                duration = duration,
                sourceUrl = sourceUrl,
                thumbnailUrl = thumbnailUrl,
                directStreamUrl = directStreamUrl,
                formatOption = resolvedOption,
                isVideo = resolvedOption.isVideo
            )
            onComplete(true)
        } catch (e: Exception) {
            android.util.Log.e("YMusicViewModel", "Failed starting download service: ${e.message}", e)
            viewModelScope.launch {
                val result = repository.downloadManager.downloadTrack(
                    title = title,
                    artist = artist,
                    duration = duration,
                    sourceUrl = sourceUrl,
                    thumbnailUrl = thumbnailUrl,
                    directStreamUrl = directStreamUrl,
                    formatOption = resolvedOption,
                    isVideo = resolvedOption.isVideo
                )
                onComplete(result.isSuccess)
            }
        }
    }

    fun deleteDownload(download: DownloadEntity) {
        viewModelScope.launch {
            repository.deleteDownload(download)
        }
    }

    fun clearAllDownloads() {
        viewModelScope.launch {
            repository.clearAllDownloads()
        }
    }

    fun getFreeStorage(): Long {
        return repository.downloadManager.getFreeStorageSpaceBytes()
    }

    fun playOfflineDownload(download: DownloadEntity, service: MediaPlaybackService?) {
        service?.onOfflineTrackEnded = {
            service.stopOfflinePlayback()
        }
        service?.playOfflineMedia(
            filePath = download.localFilePath,
            title = download.title,
            artist = download.artist,
            thumbnailUrl = download.thumbnailUrl
        )
    }

    fun playPlaylistItem(
        item: PlaylistItemEntity,
        items: List<PlaylistItemEntity>,
        service: MediaPlaybackService?,
        onPlayOnlineTrack: ((url: String, title: String) -> Unit)? = null
    ) {
        offlineQueue = items
        currentQueueIndex = items.indexOf(item)

        val resolvedPath = if (!item.localFilePath.isNullOrEmpty() && java.io.File(item.localFilePath).exists()) {
            item.localFilePath
        } else {
            val matching = downloads.value.firstOrNull {
                it.title.equals(item.title, ignoreCase = true) || (item.sourceUrl.isNotEmpty() && it.sourceUrl == item.sourceUrl)
            }
            if (matching != null && java.io.File(matching.localFilePath).exists()) {
                matching.localFilePath
            } else null
        }

        if (resolvedPath != null) {
            service?.playOfflineMedia(
                filePath = resolvedPath,
                title = item.title,
                artist = item.artist,
                thumbnailUrl = item.thumbnailUrl
            )
        } else if (item.sourceUrl.isNotEmpty() && onPlayOnlineTrack != null) {
            service?.stopOfflinePlayback()
            onPlayOnlineTrack(item.sourceUrl, item.title)
        } else {
            // Fallback: update metadata
            service?.stopOfflinePlayback()
            service?.syncMetadata(
                TrackMetadata(
                    title = item.title,
                    artist = item.artist,
                    duration = item.duration,
                    thumbnailUrl = item.thumbnailUrl,
                    url = item.sourceUrl,
                    isPlaying = true
                ),
                force = true
            )
        }

        // Set next track callback on service
        service?.onOfflineTrackEnded = {
            playNextInQueue(service, onPlayOnlineTrack)
        }
    }

    fun playNextInQueue(
        service: MediaPlaybackService?,
        onPlayOnlineTrack: ((url: String, title: String) -> Unit)? = null
    ) {
        if (offlineQueue.isNotEmpty() && currentQueueIndex < offlineQueue.size - 1) {
            currentQueueIndex++
            val nextItem = offlineQueue[currentQueueIndex]
            playPlaylistItem(nextItem, offlineQueue, service, onPlayOnlineTrack)
        } else {
            service?.stopOfflinePlayback()
        }
    }
}
