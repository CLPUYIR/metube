package com.example.data.downloader

import android.net.Uri
import android.util.Log

data class CapturedStream(
    val url: String,
    val itag: Int,
    val mimeType: String,
    val isAudio: Boolean,
    val isVideo: Boolean,
    val isMuxed: Boolean,
    val isAac: Boolean,
    val isAvc: Boolean,
    val isOpus: Boolean,
    val isVp9: Boolean,
    val videoId: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Global registry that intercepts and stores live, authenticated media stream URLs
 * flowing directly through the Android WebView from *.googlevideo.com/videoplayback.
 * Correctly correlates streams with the active YouTube video ID and sanitizes range/sq chunking.
 */
object StreamCaptureRegistry {

    private const val TAG = "StreamCaptureRegistry"
    private val lock = Any()
    private val streamsByVideoId = mutableMapOf<String, MutableList<CapturedStream>>()
    private val recentStreams = mutableListOf<CapturedStream>()

    @Volatile
    var activeVideoId: String = ""
        private set

    @Volatile
    var activeTitle: String = ""
        private set

    fun setActiveVideo(videoId: String, title: String = "") {
        if (videoId.isNotBlank()) {
            synchronized(lock) {
                activeVideoId = videoId
                if (title.isNotBlank()) activeTitle = title
            }
            Log.d(TAG, "Active video set: '$videoId' (title='$title')")
        }
    }

    /**
     * Sanitizes a googlevideo stream URL:
     * - Strips chunk range (`range=...`), chunk sequence index (`sq=...`), range number (`rn=...`, `rbuf=...`)
     * - Ensures `ratebypass=yes` so YouTube doesn't throttle download bandwidth
     * Returns a URL that streams the entire audio or video file from byte 0 to completion.
     */
    fun sanitizeStreamUrl(rawUrl: String): String {
        try {
            val uri = Uri.parse(rawUrl)
            val builder = uri.buildUpon().clearQuery()
            val names = uri.queryParameterNames
            for (name in names) {
                if (name.equals("range", ignoreCase = true) ||
                    name.equals("sq", ignoreCase = true) ||
                    name.equals("rn", ignoreCase = true) ||
                    name.equals("rbuf", ignoreCase = true) ||
                    name.equals("fallback_count", ignoreCase = true) ||
                    name.equals("live", ignoreCase = true)) {
                    continue
                }
                for (v in uri.getQueryParameters(name)) {
                    builder.appendQueryParameter(name, v)
                }
            }
            if (uri.getQueryParameter("ratebypass") == null) {
                builder.appendQueryParameter("ratebypass", "yes")
            }
            return builder.build().toString()
        } catch (_: Exception) {
            return rawUrl.replace(Regex("&range=[0-9]+-[0-9]+"), "")
        }
    }

    fun registerStream(rawUrl: String, videoIdOverride: String? = null) {
        try {
            if (!rawUrl.contains("googlevideo.com/videoplayback")) return
            val uri = Uri.parse(rawUrl)
            val itag = uri.getQueryParameter("itag")?.toIntOrNull() ?: 0
            val mime = uri.getQueryParameter("mime")?.lowercase() ?: ""

            // Determine actual YouTube video ID
            val videoId = if (!videoIdOverride.isNullOrBlank()) {
                videoIdOverride
            } else if (activeVideoId.isNotBlank()) {
                activeVideoId
            } else {
                ""
            }

            val cleanUrl = sanitizeStreamUrl(rawUrl)

            val isMuxed = itag == 18 || itag == 22
            val isAac = itag in listOf(140, 139) || mime.contains("mp4a")
            val isOpus = itag in listOf(249, 250, 251) || mime.contains("opus")
            val isAvc = itag in listOf(18, 22, 133, 134, 135, 136, 137) || mime.contains("avc")
            val isVp9 = itag in listOf(242, 243, 244, 247, 248, 271, 313) || mime.contains("vp9")

            val isAudio = isAac || isOpus || mime.startsWith("audio/") || isMuxed
            val isVideo = isAvc || isVp9 || mime.startsWith("video/") || isMuxed

            val entry = CapturedStream(
                url = cleanUrl,
                itag = itag,
                mimeType = mime,
                isAudio = isAudio,
                isVideo = isVideo,
                isMuxed = isMuxed,
                isAac = isAac,
                isAvc = isAvc,
                isOpus = isOpus,
                isVp9 = isVp9,
                videoId = videoId
            )

            synchronized(lock) {
                recentStreams.add(0, entry)
                if (recentStreams.size > 30) {
                    recentStreams.removeAt(recentStreams.size - 1)
                }

                if (videoId.isNotEmpty()) {
                    val list = streamsByVideoId.getOrPut(videoId) { mutableListOf() }
                    list.removeAll { it.itag == itag }
                    list.add(0, entry)
                    if (list.size > 20) {
                        list.removeAt(list.size - 1)
                    }
                }
            }

            Log.i(TAG, "Captured stream: itag=$itag, audio=$isAudio (aac=$isAac, opus=$isOpus), video=$isVideo (avc=$isAvc, vp9=$isVp9), muxed=$isMuxed, videoId='$videoId'")
        } catch (e: Exception) {
            Log.w(TAG, "Error registering stream: ${e.message}")
        }
    }

    fun getBestAudioStream(videoId: String? = null): String? {
        synchronized(lock) {
            if (!videoId.isNullOrEmpty()) {
                val list = streamsByVideoId[videoId]
                val aac = list?.firstOrNull { it.isAac }?.url
                if (aac != null) return aac

                val opus = list?.firstOrNull { it.isOpus }?.url
                if (opus != null) return opus

                val anyAudio = list?.firstOrNull { it.isAudio && !it.isVideo }?.url
                if (anyAudio != null) return anyAudio
            }

            val recentAac = recentStreams.firstOrNull { it.isAac }?.url
            if (recentAac != null) return recentAac

            val recentOpus = recentStreams.firstOrNull { it.isOpus }?.url
            if (recentOpus != null) return recentOpus

            return recentStreams.firstOrNull { it.isAudio && !it.isVideo }?.url
        }
    }

    fun getAacAudioStream(videoId: String? = null): String? {
        synchronized(lock) {
            if (!videoId.isNullOrEmpty()) {
                val list = streamsByVideoId[videoId]
                val aac = list?.firstOrNull { it.isAac }?.url
                if (aac != null) return aac
            }
            return recentStreams.firstOrNull { it.isAac }?.url
        }
    }

    fun getBest360pVideoStream(videoId: String? = null): CapturedStream? {
        synchronized(lock) {
            if (!videoId.isNullOrEmpty()) {
                val list = streamsByVideoId[videoId]
                val muxed18 = list?.firstOrNull { it.itag == 18 }
                if (muxed18 != null) return muxed18
                val avc360 = list?.firstOrNull { it.itag == 134 }
                if (avc360 != null) return avc360
            }
            val recentMuxed = recentStreams.firstOrNull { it.itag == 18 }
            if (recentMuxed != null) return recentMuxed
            return recentStreams.firstOrNull { it.itag == 134 }
        }
    }

    fun getBestHdVideoStream(videoId: String? = null): CapturedStream? {
        synchronized(lock) {
            if (!videoId.isNullOrEmpty()) {
                val list = streamsByVideoId[videoId]
                val muxed22 = list?.firstOrNull { it.itag == 22 }
                if (muxed22 != null) return muxed22
                val avc720 = list?.firstOrNull { it.itag == 136 || it.itag == 137 }
                if (avc720 != null) return avc720
                val vp9720 = list?.firstOrNull { it.itag == 247 || it.itag == 248 }
                if (vp9720 != null) return vp9720
            }
            val recentMuxed22 = recentStreams.firstOrNull { it.itag == 22 }
            if (recentMuxed22 != null) return recentMuxed22
            return recentStreams.firstOrNull { it.itag == 136 || it.itag == 137 || it.itag == 247 }
        }
    }

    fun getBest720pHdVideoStream(videoId: String? = null): CapturedStream? = getBestHdVideoStream(videoId)

    fun getAnyVideoStream(videoId: String? = null): CapturedStream? {
        synchronized(lock) {
            // 1. Check for HD (1080p, 720p)
            val hd = getBestHdVideoStream(videoId)
            if (hd != null) return hd

            // 2. Check for 360p (itag 18, 134)
            val sd = getBest360pVideoStream(videoId)
            if (sd != null) return sd

            // 3. Check for 480p or any video stream in matching videoId
            if (!videoId.isNullOrEmpty()) {
                val list = streamsByVideoId[videoId]
                val anyVid = list?.firstOrNull { it.isVideo }
                if (anyVid != null) return anyVid
            }

            // 4. Check for any video stream in recentStreams
            return recentStreams.firstOrNull { it.isVideo }
        }
    }

    fun getBestMuxedVideoStream(videoId: String? = null): String? {
        return getBest360pVideoStream(videoId)?.let { if (it.isMuxed) it.url else null }
            ?: getBestHdVideoStream(videoId)?.let { if (it.isMuxed) it.url else null }
            ?: getAnyVideoStream(videoId)?.let { if (it.isMuxed) it.url else null }
    }
}
