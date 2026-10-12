package com.example.data.downloader

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import com.example.data.local.YMusicDatabase
import com.example.data.local.entities.DownloadEntity
import com.example.ui.components.DownloadFormatOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat

class OfflineDownloadManager(private val context: Context) {

    private val db = YMusicDatabase.getInstance(context)
    private val downloadDao = db.downloadDao()

    private val downloadDir: File by lazy {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: File(context.filesDir, "ymusic_downloads")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir
    }

    suspend fun downloadTrack(
        title: String,
        artist: String,
        duration: String,
        sourceUrl: String,
        thumbnailUrl: String,
        directStreamUrl: String = "",
        formatOption: DownloadFormatOption = DownloadFormatOption.AUDIO_M4A,
        isVideo: Boolean = formatOption.isVideo,
        onProgress: (Int) -> Unit = {}
    ): Result<DownloadEntity> = withContext(Dispatchers.IO) {
        val tag = "YMusicDownloader"
        android.util.Log.i(tag, "========== DOWNLOAD INITIATED ==========")
        android.util.Log.i(tag, "Track Title: '$title'")
        android.util.Log.i(tag, "Track Artist: '$artist'")
        android.util.Log.i(tag, "Format Option: ${formatOption.name} (isVideo=$isVideo, format=${formatOption.format})")
        android.util.Log.i(tag, "Source URL: '$sourceUrl'")

        var initialDownload: DownloadEntity? = null
        var downloadId: Long = 0L

        try {
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_").take(35).ifEmpty { "Track" }
            val qualityClean = formatOption.qualityBadge.replace(Regex("[^a-zA-Z0-9]"), "")
            val resolvedFormat = formatOption.format.lowercase()
            val fileName = "${sanitizedTitle}_${qualityClean}_${System.currentTimeMillis()}.$resolvedFormat"
            val targetFile = File(downloadDir, fileName)

            val entityTitle = if (formatOption.isVideo) {
                if (title.contains(formatOption.qualityBadge, ignoreCase = true)) title
                else "$title [${formatOption.qualityBadge}]"
            } else if (formatOption == DownloadFormatOption.AUDIO_MP3) {
                if (title.contains("MP3", ignoreCase = true)) title
                else "$title [MP3]"
            } else {
                title.ifEmpty { "Offline Track" }
            }

            // Initial Pending download record
            val pendingRecord = DownloadEntity(
                title = entityTitle,
                artist = artist.ifEmpty { "MeTube Download" },
                duration = duration.ifEmpty { "3:30" },
                sourceUrl = sourceUrl,
                localFilePath = targetFile.absolutePath,
                thumbnailUrl = thumbnailUrl,
                fileSizeBytes = 0L,
                status = "DOWNLOADING",
                progressPercent = 10
            )
            initialDownload = pendingRecord
            downloadId = downloadDao.insertDownload(pendingRecord)
            onProgress(10)

            // 1. Resolve Stream
            val resolvedStream = StreamUrlResolver.resolveMediaStream(
                videoPageUrl = sourceUrl,
                formatOption = formatOption,
                directStreamUrl = directStreamUrl
            )

            if (resolvedStream == null || resolvedStream.url.isEmpty()) {
                throw IOException("Could not resolve stream URL for '$title'. Please play the track first so the stream can be captured.")
            }

            android.util.Log.i(tag, "Resolved stream: itag=${resolvedStream.itag}, isMuxed=${resolvedStream.isMuxed}, hasCompanionAudio=${resolvedStream.companionAudioUrl != null}")

            var downloadSuccess = false

            // 2. Execute Download based on format
            if (formatOption.isVideo && !resolvedStream.isMuxed && !resolvedStream.companionAudioUrl.isNullOrEmpty()) {
                // Dual-track download: Video + Companion Audio, then mux together
                val tempVideo = File(downloadDir, "temp_v_${System.currentTimeMillis()}.${resolvedFormat}")
                val tempAudio = File(downloadDir, "temp_a_${System.currentTimeMillis()}.m4a")

                try {
                    android.util.Log.i(tag, "Step 1/3: Downloading video track...")
                    val videoOk = downloadUrlToFile(resolvedStream.url, tempVideo, 10, 55, onProgress)
                    if (!videoOk) throw IOException("Failed to download video track")

                    android.util.Log.i(tag, "Step 2/3: Downloading synchronized audio track...")
                    val audioOk = downloadUrlToFile(resolvedStream.companionAudioUrl, tempAudio, 55, 85, onProgress)
                    if (!audioOk) throw IOException("Failed to download companion audio track for video")

                    onProgress(90)
                    android.util.Log.i(tag, "Step 3/3: Muxing video and audio tracks with AndroidMediaMuxer...")
                    val muxOk = AndroidMediaMuxer.mux(tempVideo, tempAudio, targetFile)

                    if (muxOk && targetFile.exists() && targetFile.length() > 200 * 1024L) {
                        android.util.Log.i(tag, "HD video with synchronized audio muxed successfully: ${targetFile.length()} bytes")
                        downloadSuccess = true
                    } else {
                        android.util.Log.w(tag, "Muxing failed - checking for muxed stream fallback")
                    }
                } finally {
                    if (tempVideo.exists()) tempVideo.delete()
                    if (tempAudio.exists()) tempAudio.delete()
                }

                if (!downloadSuccess) {
                    // Fallback to muxed stream (itag 18) to guarantee video WITH sound
                    val videoId = StreamUrlResolver.extractVideoId(sourceUrl)
                    val muxedFallback = StreamCaptureRegistry.getBestMuxedVideoStream(videoId)
                    if (!muxedFallback.isNullOrEmpty()) {
                        android.util.Log.i(tag, "Fallback: Downloading 360p muxed stream with synchronized sound...")
                        downloadSuccess = downloadUrlToFile(muxedFallback, targetFile, 50, 95, onProgress)
                    } else {
                        // Direct video stream download
                        downloadSuccess = downloadUrlToFile(resolvedStream.url, targetFile, 50, 95, onProgress)
                    }
                }
            } else {
                // Direct single-file download (M4A audio, MP3 audio, or muxed video itag 18/22)
                android.util.Log.i(tag, "Downloading stream directly to ${targetFile.name}...")
                downloadSuccess = downloadUrlToFile(resolvedStream.url, targetFile, 15, 95, onProgress)
            }

            if (!downloadSuccess || !targetFile.exists() || targetFile.length() < 100 * 1024L) {
                throw IOException("Download failed or produced invalid file (${targetFile.length()} bytes)")
            }

            // Export to public phone storage and MediaStore
            val publicSavedPath = exportToPhoneStorageAndGallery(
                sourceFile = targetFile,
                title = title,
                artist = artist,
                format = resolvedFormat,
                isVideo = isVideo
            )

            val internalPlayablePath = targetFile.absolutePath
            onProgress(100)

            val finalEntity = (initialDownload ?: pendingRecord).copy(
                id = downloadId,
                localFilePath = internalPlayablePath,
                fileSizeBytes = targetFile.length(),
                status = "COMPLETED",
                progressPercent = 100
            )
            downloadDao.updateDownload(finalEntity)
            android.util.Log.i(tag, "Download entity saved. Title: '${finalEntity.title}', Path: '$internalPlayablePath', Size: ${formatFileSize(finalEntity.fileSizeBytes)}")
            android.util.Log.i(tag, "========== DOWNLOAD FINISHED ==========")

            Result.success(finalEntity)
        } catch (e: Exception) {
            android.util.Log.e(tag, "Download failed with exception: ${e.message}", e)
            try {
                initialDownload?.let { record ->
                    val failedEntity = record.copy(
                        id = downloadId,
                        status = "FAILED",
                        progressPercent = 0
                    )
                    downloadDao.updateDownload(failedEntity)
                }
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    private fun downloadUrlToFile(
        streamUrl: String,
        destinationFile: File,
        startPercent: Int,
        endPercent: Int,
        onProgress: (Int) -> Unit
    ): Boolean {
        var currentUrl = streamUrl
        var connection: HttpURLConnection? = null
        var redirectCount = 0

        while (redirectCount < 5) {
            val urlObj = URL(currentUrl)
            val sessionCookie = try {
                android.webkit.CookieManager.getInstance().getCookie(currentUrl)
                    ?: android.webkit.CookieManager.getInstance().getCookie("https://www.youtube.com")
            } catch (_: Exception) { null }

            connection = (urlObj.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "keep-alive")
                if (!sessionCookie.isNullOrEmpty()) {
                    setRequestProperty("Cookie", sessionCookie)
                }
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                connect()
            }

            val code = connection.responseCode
            if (code in 300..399) {
                val loc = connection.getHeaderField("Location")
                if (!loc.isNullOrEmpty()) {
                    currentUrl = loc
                    redirectCount++
                    continue
                }
            }
            break
        }

        var finalConnection = connection ?: return false
        var responseCode = finalConnection.responseCode

        // Retry with Range: bytes=0- if server returns 403 or 416
        if (responseCode == 403 || responseCode == 416) {
            try {
                finalConnection.disconnect()
                val urlObj = URL(currentUrl)
                finalConnection = (urlObj.openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("Range", "bytes=0-")
                    connectTimeout = 20000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    connect()
                }
                responseCode = finalConnection.responseCode
            } catch (e: Exception) {
                android.util.Log.w("YMusicDownloader", "Range header retry exception: ${e.message}")
            }
        }

        if (responseCode !in 200..299) {
            android.util.Log.w("YMusicDownloader", "HTTP $responseCode from $currentUrl")
            return false
        }

        val contentType = finalConnection.contentType?.lowercase() ?: ""
        if (contentType.startsWith("text/") || contentType.contains("html") || contentType.contains("json")) {
            android.util.Log.w("YMusicDownloader", "Invalid content-type: '$contentType'")
            return false
        }

        val totalLength = finalConnection.contentLengthLong
        var downloadedBytes = 0L

        finalConnection.inputStream.use { input ->
            FileOutputStream(destinationFile).use { output ->
                val buffer = ByteArray(65536) // 64KB transfer buffer
                var read: Int
                var lastReported = startPercent

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloadedBytes += read

                    if (totalLength > 0) {
                        val pct = startPercent + ((downloadedBytes * (endPercent - startPercent)) / totalLength).toInt()
                        if (pct >= lastReported + 3) {
                            lastReported = pct
                            onProgress(pct.coerceIn(startPercent, endPercent))
                        }
                    }
                }
            }
        }

        val finalSize = destinationFile.length()
        android.util.Log.i("YMusicDownloader", "Finished download: ${destinationFile.name} ($finalSize bytes, expected: $totalLength)")
        return finalSize >= 100 * 1024L
    }

    private fun exportToPhoneStorageAndGallery(
        sourceFile: File,
        title: String,
        artist: String,
        format: String,
        isVideo: Boolean
    ): String {
        var finalExportedPath = sourceFile.absolutePath
        val tag = "YMusicDownloader"
        val pathsToScan = mutableListOf<String>()
        pathsToScan.add(sourceFile.absolutePath)

        try {
            val mimeType = if (isVideo) {
                if (format.equals("webm", ignoreCase = true)) "video/webm" else "video/mp4"
            } else {
                when (format.lowercase()) {
                    "m4a" -> "audio/mp4"
                    "ogg" -> "audio/ogg"
                    "wav" -> "audio/wav"
                    "webm" -> "audio/webm"
                    else -> "audio/mpeg"
                }
            }

            val displayName = sourceFile.name

            // 1. Direct Public Storage Saving (Movies/MeTube or Music/MeTube)
            try {
                val primaryDirType = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
                val primaryBase = Environment.getExternalStoragePublicDirectory(primaryDirType)
                val primaryAppDir = File(primaryBase, "MeTube")
                if (!primaryAppDir.exists()) primaryAppDir.mkdirs()
                val primaryFile = File(primaryAppDir, displayName)
                sourceFile.copyTo(primaryFile, overwrite = true)
                finalExportedPath = primaryFile.absolutePath
                pathsToScan.add(primaryFile.absolutePath)
                android.util.Log.i(tag, "Exported directly to public storage: ${primaryFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.w(tag, "Direct primary storage export note: ${e.message}")
            }

            // Also copy into public Downloads folder
            try {
                val downloadsBase = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val downloadsAppDir = File(downloadsBase, "MeTube")
                if (!downloadsAppDir.exists()) downloadsAppDir.mkdirs()
                val downloadsFile = File(downloadsAppDir, displayName)
                sourceFile.copyTo(downloadsFile, overwrite = true)
                pathsToScan.add(downloadsFile.absolutePath)
                android.util.Log.i(tag, "Exported to public Downloads folder: ${downloadsFile.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.w(tag, "Downloads folder direct export note: ${e.message}")
            }

            // 2. Insert into Android MediaStore
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val collection = if (isVideo) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    }

                    val relativePath = if (isVideo) {
                        "${Environment.DIRECTORY_MOVIES}/MeTube"
                    } else {
                        "${Environment.DIRECTORY_MUSIC}/MeTube"
                    }

                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                        put(MediaStore.MediaColumns.TITLE, title)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                        if (!isVideo) {
                            put(MediaStore.Audio.Media.ARTIST, artist)
                        }
                    }

                    val uri = context.contentResolver.insert(collection, values)
                    if (uri != null) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            sourceFile.inputStream().use { inp ->
                                inp.copyTo(out)
                            }
                        }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        context.contentResolver.update(uri, values, null, null)
                        android.util.Log.i(tag, "Exported to system MediaStore successfully: $uri ($displayName)")

                        try {
                            val proj = arrayOf(MediaStore.MediaColumns.DATA)
                            context.contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                                    if (idx != -1) {
                                        val dataPath = cursor.getString(idx)
                                        if (!dataPath.isNullOrEmpty()) {
                                            finalExportedPath = dataPath
                                            pathsToScan.add(dataPath)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e(tag, "MediaStore primary insert error: ${e.message}")
                }
            }

            // 3. Trigger Android MediaScannerConnection
            MediaScannerConnection.scanFile(
                context,
                pathsToScan.distinct().toTypedArray(),
                Array(pathsToScan.distinct().size) { mimeType }
            ) { path, scannedUri ->
                android.util.Log.i(tag, "MediaScanner registered file: $path -> $scannedUri")
            }

        } catch (e: Exception) {
            android.util.Log.e(tag, "Failed exportToPhoneStorageAndGallery: ${e.message}", e)
        }
        return finalExportedPath
    }

    suspend fun deleteDownload(download: DownloadEntity) = withContext(Dispatchers.IO) {
        try {
            val file = File(download.localFilePath)
            if (file.exists()) {
                file.delete()
            }
            val baseName = file.name
            val sandboxFile = File(downloadDir, baseName)
            if (sandboxFile.exists()) {
                sandboxFile.delete()
            }
            downloadDao.deleteDownload(download.id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun clearAllDownloads() = withContext(Dispatchers.IO) {
        try {
            downloadDir.listFiles()?.forEach { f ->
                if (f.exists()) f.delete()
            }
            downloadDao.deleteAllDownloads()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getFreeStorageSpaceBytes(): Long {
        return try {
            val stat = StatFs(downloadDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val formattedNumber = DecimalFormat("#,##0.#").format(bytes / Math.pow(1024.0, digitGroups.toDouble()))
            return "$formattedNumber ${units[digitGroups]}"
        }
    }
}
