package com.example.data.downloader

import android.webkit.CookieManager
import com.example.ui.components.DownloadFormatOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ResolvedMediaStream(
    val url: String,
    val format: String,
    val isVideo: Boolean = false,
    val isMuxed: Boolean = false,
    val itag: Int = 0,
    val bitrate: Int = 0,
    val contentLength: Long = 0L,
    val companionAudioUrl: String? = null // Separate audio URL for video muxing
)

typealias ResolvedAudioStream = ResolvedMediaStream

/**
 * High-performance stream resolver for MeTube / YMusic:
 * 1. Checks StreamCaptureRegistry for live, authenticated googlevideo streams from active WebView
 * 2. Directly queries YouTube's InnerTube API (client: ANDROID_VR) for direct, unthrottled streamingData
 * 3. Supports muxed 360p (itag 18) and dual-track 720p HD (itag 136 + itag 140) for complete video with sound
 * 4. Falls back to YouTube Watch Page session scraper using active WebView cookies
 */
object StreamUrlResolver {

    private const val TAG = "StreamUrlResolver"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun logDebug(msg: String) {
        try { android.util.Log.d(TAG, msg) } catch (_: Throwable) { println("[$TAG][D] $msg") }
    }

    private fun logInfo(msg: String) {
        try { android.util.Log.i(TAG, msg) } catch (_: Throwable) { println("[$TAG][I] $msg") }
    }

    private fun logWarn(msg: String) {
        try { android.util.Log.w(TAG, msg) } catch (_: Throwable) { println("[$TAG][W] $msg") }
    }

    private fun logError(msg: String, tr: Throwable? = null) {
        try { android.util.Log.e(TAG, msg, tr) } catch (_: Throwable) { println("[$TAG][E] $msg") }
    }

    /**
     * Extracts an 11-character YouTube video ID from various URL formats.
     */
    fun extractVideoId(rawUrl: String): String? {
        if (rawUrl.isEmpty()) return null

        val vParam = Regex("[?&]v=([a-zA-Z0-9_-]{11})").find(rawUrl)?.groupValues?.get(1)
        if (!vParam.isNullOrEmpty()) return vParam

        val shortParam = Regex("youtu\\.be/([a-zA-Z0-9_-]{11})").find(rawUrl)?.groupValues?.get(1)
        if (!shortParam.isNullOrEmpty()) return shortParam

        val pathParam = Regex("(?:embed|live|shorts|v)/([a-zA-Z0-9_-]{11})").find(rawUrl)?.groupValues?.get(1)
        if (!pathParam.isNullOrEmpty()) return pathParam

        if (rawUrl.length == 11 && rawUrl.matches(Regex("[a-zA-Z0-9_-]{11}"))) {
            return rawUrl
        }

        return null
    }

    /**
     * Resolves the desired media stream matching the requested format option.
     */
    suspend fun resolveMediaStream(
        videoPageUrl: String,
        formatOption: DownloadFormatOption,
        directStreamUrl: String = ""
    ): ResolvedMediaStream? = withContext(Dispatchers.IO) {
        logInfo("========== STREAM RESOLUTION: format=${formatOption.name}, url='$videoPageUrl' ==========")

        val videoId = extractVideoId(videoPageUrl)
            ?: extractVideoId(directStreamUrl)
            ?: StreamCaptureRegistry.activeVideoId.ifEmpty { null }

        // 1. Check direct media URL if already provided
        if (directStreamUrl.isNotBlank() && (directStreamUrl.startsWith("http://") || directStreamUrl.startsWith("https://")) && !directStreamUrl.startsWith("blob:")) {
            val cleanDirect = StreamCaptureRegistry.sanitizeStreamUrl(directStreamUrl)
            if (!formatOption.isVideo && (directStreamUrl.contains("mime=audio") || directStreamUrl.contains(".m4a") || directStreamUrl.contains(".mp3"))) {
                logInfo("Strategy 1: Using direct captured audio stream: ${cleanDirect.take(80)}")
                return@withContext ResolvedMediaStream(
                    url = cleanDirect,
                    format = formatOption.format,
                    isVideo = false,
                    isMuxed = false,
                    bitrate = 192000
                )
            }
        }

        // 2. Strategy 2: Check StreamCaptureRegistry for live streams captured from active WebView playback
        if (videoId != null) {
            if (!formatOption.isVideo) {
                // Audio download: prioritize AAC (itag 140) or Opus (itag 251)
                val capturedAudio = StreamCaptureRegistry.getBestAudioStream(videoId)
                if (!capturedAudio.isNullOrEmpty()) {
                    logInfo("Strategy 2: Resolved live captured audio stream for videoId=$videoId")
                    return@withContext ResolvedMediaStream(
                        url = capturedAudio,
                        format = formatOption.format,
                        isVideo = false,
                        isMuxed = false,
                        bitrate = 160000
                    )
                }
            } else if (formatOption == DownloadFormatOption.VIDEO_360P) {
                val captured360 = StreamCaptureRegistry.getBest360pVideoStream(videoId)
                if (captured360 != null) {
                    if (captured360.isMuxed) {
                        logInfo("Strategy 2: Resolved live captured 360p muxed video stream for $videoId")
                        return@withContext ResolvedMediaStream(
                            url = captured360.url,
                            format = "mp4",
                            isVideo = true,
                            isMuxed = true,
                            itag = captured360.itag,
                            bitrate = 800000
                        )
                    } else {
                        val companionAudio = StreamCaptureRegistry.getAacAudioStream(videoId)
                            ?: StreamCaptureRegistry.getBestAudioStream(videoId)
                        if (companionAudio != null) {
                            logInfo("Strategy 2: Resolved live captured 360p video + audio tracks for muxing ($videoId)")
                            return@withContext ResolvedMediaStream(
                                url = captured360.url,
                                format = "mp4",
                                isVideo = true,
                                isMuxed = false,
                                itag = captured360.itag,
                                bitrate = 800000,
                                companionAudioUrl = companionAudio
                            )
                        }
                    }
                } else {
                    val capturedAny = StreamCaptureRegistry.getAnyVideoStream(videoId)
                    if (capturedAny != null) {
                        if (capturedAny.isMuxed) {
                            logInfo("Strategy 2: Fallback to captured muxed video (${capturedAny.itag}) for 360p request ($videoId)")
                            return@withContext ResolvedMediaStream(
                                url = capturedAny.url,
                                format = "mp4",
                                isVideo = true,
                                isMuxed = true,
                                itag = capturedAny.itag,
                                bitrate = 800000
                            )
                        } else {
                            val companionAudio = StreamCaptureRegistry.getAacAudioStream(videoId)
                                ?: StreamCaptureRegistry.getBestAudioStream(videoId)
                            if (companionAudio != null) {
                                logInfo("Strategy 2: Fallback to captured video (${capturedAny.itag}) + audio tracks for 360p muxing ($videoId)")
                                return@withContext ResolvedMediaStream(
                                    url = capturedAny.url,
                                    format = "mp4",
                                    isVideo = true,
                                    isMuxed = false,
                                    itag = capturedAny.itag,
                                    bitrate = 800000,
                                    companionAudioUrl = companionAudio
                                )
                            }
                        }
                    }
                }
            } else if (formatOption == DownloadFormatOption.VIDEO_720P) {
                val capturedHd = StreamCaptureRegistry.getBest720pHdVideoStream(videoId)
                if (capturedHd != null) {
                    if (capturedHd.isMuxed) {
                        logInfo("Strategy 2: Resolved live captured 720p muxed video stream for $videoId")
                        return@withContext ResolvedMediaStream(
                            url = capturedHd.url,
                            format = "mp4",
                            isVideo = true,
                            isMuxed = true,
                            itag = capturedHd.itag,
                            bitrate = 1800000
                        )
                    } else {
                        val companionAudio = StreamCaptureRegistry.getAacAudioStream(videoId)
                            ?: StreamCaptureRegistry.getBestAudioStream(videoId)
                        if (companionAudio != null) {
                            logInfo("Strategy 2: Resolved live captured 720p HD video + audio tracks for muxing ($videoId)")
                            return@withContext ResolvedMediaStream(
                                url = capturedHd.url,
                                format = "mp4",
                                isVideo = true,
                                isMuxed = false,
                                itag = capturedHd.itag,
                                bitrate = 1500000,
                                companionAudioUrl = companionAudio
                            )
                        }
                    }
                } else {
                    // Player might be streaming standard/adaptive video (itag 18, 134, 135, etc.)
                    val capturedAny = StreamCaptureRegistry.getAnyVideoStream(videoId)
                    if (capturedAny != null) {
                        if (capturedAny.isMuxed) {
                            logInfo("Strategy 2: Fallback to captured muxed video (${capturedAny.itag}) for $videoId")
                            return@withContext ResolvedMediaStream(
                                url = capturedAny.url,
                                format = "mp4",
                                isVideo = true,
                                isMuxed = true,
                                itag = capturedAny.itag,
                                bitrate = 800000
                            )
                        } else {
                            val companionAudio = StreamCaptureRegistry.getAacAudioStream(videoId)
                                ?: StreamCaptureRegistry.getBestAudioStream(videoId)
                            if (companionAudio != null) {
                                logInfo("Strategy 2: Fallback to captured video (${capturedAny.itag}) + audio tracks for muxing ($videoId)")
                                return@withContext ResolvedMediaStream(
                                    url = capturedAny.url,
                                    format = "mp4",
                                    isVideo = true,
                                    isMuxed = false,
                                    itag = capturedAny.itag,
                                    bitrate = 1000000,
                                    companionAudioUrl = companionAudio
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Strategy 3: Query YouTube InnerTube API directly (client: ANDROID_VR)
        if (videoId != null) {
            val innerTubeResult = queryInnerTubeApi(videoId, formatOption)
            if (innerTubeResult != null) {
                logInfo("Strategy 3: Successfully resolved stream via InnerTube ANDROID_VR API!")
                return@withContext innerTubeResult
            }
        }

        // 4. Strategy 4: Fallback to YouTube Watch Page scraper with WebView session cookies
        if (videoId != null) {
            val scrapeResult = scrapeWatchPageStreams(videoId, formatOption)
            if (scrapeResult != null) {
                logInfo("Strategy 4: Successfully resolved stream via YouTube Watch Page scraper!")
                return@withContext scrapeResult
            }
        }

        // 5. Strategy 5: Fallback to any recent captured stream for the current session
        if (!formatOption.isVideo) {
            val fallbackAudio = StreamCaptureRegistry.getBestAudioStream(null)
            if (!fallbackAudio.isNullOrEmpty()) {
                logInfo("Strategy 5: Using session-level captured audio stream")
                return@withContext ResolvedMediaStream(
                    url = fallbackAudio,
                    format = formatOption.format,
                    isVideo = false,
                    isMuxed = false,
                    bitrate = 160000
                )
            }
        } else {
            val fallbackMuxed = StreamCaptureRegistry.getBestMuxedVideoStream(null)
            if (!fallbackMuxed.isNullOrEmpty()) {
                logInfo("Strategy 5: Using session-level captured muxed video stream")
                return@withContext ResolvedMediaStream(
                    url = fallbackMuxed,
                    format = "mp4",
                    isVideo = true,
                    isMuxed = true,
                    itag = 18,
                    bitrate = 800000
                )
            }
            val fallbackAnyVideo = StreamCaptureRegistry.getAnyVideoStream(null)
            val fallbackAudio = StreamCaptureRegistry.getAacAudioStream(null) ?: StreamCaptureRegistry.getBestAudioStream(null)
            if (fallbackAnyVideo != null) {
                if (fallbackAnyVideo.isMuxed) {
                    logInfo("Strategy 5: Using session-level captured muxed stream (${fallbackAnyVideo.itag})")
                    return@withContext ResolvedMediaStream(
                        url = fallbackAnyVideo.url,
                        format = "mp4",
                        isVideo = true,
                        isMuxed = true,
                        itag = fallbackAnyVideo.itag,
                        bitrate = 800000
                    )
                } else if (fallbackAudio != null) {
                    logInfo("Strategy 5: Using session-level captured video (${fallbackAnyVideo.itag}) + audio tracks for muxing")
                    return@withContext ResolvedMediaStream(
                        url = fallbackAnyVideo.url,
                        format = "mp4",
                        isVideo = true,
                        isMuxed = false,
                        itag = fallbackAnyVideo.itag,
                        bitrate = 1000000,
                        companionAudioUrl = fallbackAudio
                    )
                } else {
                    logInfo("Strategy 5: Using session-level captured direct video track (${fallbackAnyVideo.itag})")
                    return@withContext ResolvedMediaStream(
                        url = fallbackAnyVideo.url,
                        format = "mp4",
                        isVideo = true,
                        isMuxed = false,
                        itag = fallbackAnyVideo.itag,
                        bitrate = 1000000
                    )
                }
            }
        }

        logWarn("All resolution strategies exhausted for URL: '$videoPageUrl' (videoId=$videoId)")
        return@withContext null
    }

    /**
     * Backward-compatible audio resolution wrapper.
     */
    suspend fun resolveAudioStream(videoPageUrl: String): ResolvedMediaStream? {
        return resolveMediaStream(videoPageUrl, DownloadFormatOption.AUDIO_M4A)
    }

    /**
     * Backward-compatible video resolution wrapper.
     */
    suspend fun resolveVideoStream(videoPageUrl: String): ResolvedMediaStream? {
        return resolveMediaStream(videoPageUrl, DownloadFormatOption.VIDEO_720P)
    }

    /**
     * Queries YouTube's InnerTube player API using ANDROID_VR client.
     * Returns direct, signed streaming URLs without deciphering requirements.
     */
    private fun queryInnerTubeApi(videoId: String, formatOption: DownloadFormatOption): ResolvedMediaStream? {
        try {
            val clientJson = JSONObject().apply {
                put("clientName", "ANDROID_VR")
                put("clientVersion", "1.60.19")
                put("deviceMake", "Oculus")
                put("deviceModel", "Quest 3")
                put("osName", "Android")
                put("osVersion", "12")
                put("hl", "en")
                put("gl", "US")
            }

            val contextJson = JSONObject().apply {
                put("client", clientJson)
            }

            val payloadJson = JSONObject().apply {
                put("context", contextJson)
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }

            val requestBody = payloadJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

            val cookies = try {
                CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
            } catch (_: Exception) { "" }

            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(requestBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .apply {
                    if (cookies.isNotBlank()) header("Cookie", cookies)
                }
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logWarn("InnerTube responded with HTTP ${response.code}")
                    return null
                }

                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (status != "OK") {
                    logWarn("InnerTube playabilityStatus: $status for video $videoId")
                    return null
                }

                val streamingData = json.optJSONObject("streamingData") ?: return null
                val formats = streamingData.optJSONArray("formats")
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")

                // Audio request: find best direct audio format (itag 140 for AAC HQ, or itag 251 for Opus)
                if (!formatOption.isVideo) {
                    if (adaptiveFormats != null) {
                        var bestAudioUrl = ""
                        var bestAudioItag = 0
                        var bestAudioBitrate = 0
                        var bestClen = 0L

                        for (i in 0 until adaptiveFormats.length()) {
                            val f = adaptiveFormats.getJSONObject(i)
                            val itag = f.optInt("itag")
                            val mime = f.optString("mimeType", "")
                            val url = f.optString("url", "")
                            val bitrate = f.optInt("bitrate", 0)
                            val clen = f.optLong("contentLength", 0L)

                            if (url.isNotEmpty() && mime.startsWith("audio/")) {
                                if (itag == 140) { // AAC M4A preferred
                                    bestAudioUrl = url
                                    bestAudioItag = itag
                                    bestAudioBitrate = bitrate
                                    bestClen = clen
                                    break
                                } else if (bestAudioUrl.isEmpty() || bitrate > bestAudioBitrate) {
                                    bestAudioUrl = url
                                    bestAudioItag = itag
                                    bestAudioBitrate = bitrate
                                    bestClen = clen
                                }
                            }
                        }

                        if (bestAudioUrl.isNotEmpty()) {
                            val cleanUrl = StreamCaptureRegistry.sanitizeStreamUrl(bestAudioUrl)
                            return ResolvedMediaStream(
                                url = cleanUrl,
                                format = formatOption.format,
                                isVideo = false,
                                isMuxed = false,
                                itag = bestAudioItag,
                                bitrate = bestAudioBitrate,
                                contentLength = bestClen
                            )
                        }
                    }
                } else if (formatOption == DownloadFormatOption.VIDEO_360P) {
                    // 360p video request: prioritize muxed itag 18 with sound
                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            val f = formats.getJSONObject(i)
                            val itag = f.optInt("itag")
                            val url = f.optString("url", "")
                            val clen = f.optLong("contentLength", 0L)
                            val bitrate = f.optInt("bitrate", 800000)

                            if (url.isNotEmpty() && (itag == 18 || itag == 22)) {
                                val cleanUrl = StreamCaptureRegistry.sanitizeStreamUrl(url)
                                return ResolvedMediaStream(
                                    url = cleanUrl,
                                    format = "mp4",
                                    isVideo = true,
                                    isMuxed = true,
                                    itag = itag,
                                    bitrate = bitrate,
                                    contentLength = clen
                                )
                            }
                        }
                    }
                } else if (formatOption == DownloadFormatOption.VIDEO_720P) {
                    // 720p HD request:
                    // First check if direct 720p muxed stream exists (itag 22)
                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            val f = formats.getJSONObject(i)
                            val itag = f.optInt("itag")
                            val url = f.optString("url", "")
                            if (url.isNotEmpty() && itag == 22) {
                                val cleanUrl = StreamCaptureRegistry.sanitizeStreamUrl(url)
                                return ResolvedMediaStream(
                                    url = cleanUrl,
                                    format = "mp4",
                                    isVideo = true,
                                    isMuxed = true,
                                    itag = 22,
                                    bitrate = 1800000
                                )
                            }
                        }
                    }

                    // Otherwise, find adaptive 720p video (itag 136) AND AAC audio (itag 140) for AndroidMediaMuxer
                    if (adaptiveFormats != null) {
                        var video720Url = ""
                        var audioUrl = ""

                        for (i in 0 until adaptiveFormats.length()) {
                            val f = adaptiveFormats.getJSONObject(i)
                            val itag = f.optInt("itag")
                            val mime = f.optString("mimeType", "")
                            val url = f.optString("url", "")

                            if (url.isNotEmpty()) {
                                if (itag == 136 || (mime.startsWith("video/mp4") && f.optString("qualityLabel") == "720p")) {
                                    video720Url = url
                                } else if (itag == 140 || (mime.startsWith("audio/mp4") && audioUrl.isEmpty())) {
                                    audioUrl = url
                                }
                            }
                        }

                        if (video720Url.isNotEmpty() && audioUrl.isNotEmpty()) {
                            val cleanVideoUrl = StreamCaptureRegistry.sanitizeStreamUrl(video720Url)
                            val cleanAudioUrl = StreamCaptureRegistry.sanitizeStreamUrl(audioUrl)
                            return ResolvedMediaStream(
                                url = cleanVideoUrl,
                                format = "mp4",
                                isVideo = true,
                                isMuxed = false,
                                itag = 136,
                                bitrate = 1500000,
                                companionAudioUrl = cleanAudioUrl
                            )
                        }
                    }

                    // Fallback to 360p muxed video if 720p adaptive stream unavailable
                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            val f = formats.getJSONObject(i)
                            val itag = f.optInt("itag")
                            val url = f.optString("url", "")
                            if (url.isNotEmpty() && itag == 18) {
                                val cleanUrl = StreamCaptureRegistry.sanitizeStreamUrl(url)
                                return ResolvedMediaStream(
                                    url = cleanUrl,
                                    format = "mp4",
                                    isVideo = true,
                                    isMuxed = true,
                                    itag = 18,
                                    bitrate = 800000
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logError("Error in queryInnerTubeApi: ${e.message}", e)
        }
        return null
    }

    /**
     * Scrapes YouTube Watch Page HTML with WebView session cookies.
     * Extracts direct format URLs from ytInitialPlayerResponse when available.
     */
    private fun scrapeWatchPageStreams(videoId: String, formatOption: DownloadFormatOption): ResolvedMediaStream? {
        try {
            val cookies = try {
                CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
            } catch (_: Exception) { "" }

            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .apply {
                    if (cookies.isNotBlank()) header("Cookie", cookies)
                }
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val html = response.body?.string() ?: return null

                val matcher = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});").matcher(html)
                if (!matcher.find()) return null

                val jsonStr = matcher.group(1) ?: return null
                val json = JSONObject(jsonStr)
                val sd = json.optJSONObject("streamingData") ?: return null

                val formats = sd.optJSONArray("formats")
                val adaptive = sd.optJSONArray("adaptiveFormats")

                if (!formatOption.isVideo && adaptive != null) {
                    for (i in 0 until adaptive.length()) {
                        val f = adaptive.getJSONObject(i)
                        val itag = f.optInt("itag")
                        val url = f.optString("url", "")
                        if (url.isNotEmpty() && (itag == 140 || f.optString("mimeType").startsWith("audio/"))) {
                            val cleanUrl = StreamCaptureRegistry.sanitizeStreamUrl(url)
                            return ResolvedMediaStream(
                                url = cleanUrl,
                                format = formatOption.format,
                                isVideo = false,
                                isMuxed = false,
                                itag = itag
                            )
                        }
                    }
                } else if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val f = formats.getJSONObject(i)
                        val itag = f.optInt("itag")
                        val url = f.optString("url", "")
                        if (url.isNotEmpty() && (itag == 18 || itag == 22)) {
                            val cleanUrl = StreamCaptureRegistry.sanitizeStreamUrl(url)
                            return ResolvedMediaStream(
                                url = cleanUrl,
                                format = "mp4",
                                isVideo = true,
                                isMuxed = true,
                                itag = itag
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logWarn("Watch page scrape failed: ${e.message}")
        }
        return null
    }
}
