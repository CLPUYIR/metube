package com.example

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView

data class TrackMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: String = "0:00",
    val currentTime: String = "0:00",
    val currentTimeSec: Float = 0f,
    val durationSec: Float = 0f,
    val isPlaying: Boolean = false,
    val thumbnailUrl: String = "",
    val url: String = "",
    val streamUrl: String = ""
)

/**
 * Bi-directional JavaScript bridge between Android and the YouTube / YouTube Music DOM.
 * Maps exact queries and selectors from core/media_controller.py.
 */
class JsMediaBridge(
    private val onTrackChanged: (TrackMetadata) -> Unit,
    private val onPlaybackStateChanged: (Boolean) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTitle = ""
    private var lastIsPlaying = false

    @JavascriptInterface
    fun onTrackMetadata(
        title: String,
        artist: String,
        album: String,
        duration: String,
        currentTime: String,
        isPlaying: Boolean,
        thumbnailUrl: String,
        url: String,
        streamUrl: String = ""
    ) {
        onTrackMetadataWithProgress(
            title = title,
            artist = artist,
            album = album,
            duration = duration,
            currentTime = currentTime,
            currentTimeSec = 0f,
            durationSec = 0f,
            isPlaying = isPlaying,
            thumbnailUrl = thumbnailUrl,
            url = url,
            streamUrl = streamUrl
        )
    }

    @JavascriptInterface
    fun onActiveVideoDiscovered(videoId: String, title: String) {
        if (videoId.isNotEmpty()) {
            com.example.data.downloader.StreamCaptureRegistry.setActiveVideo(videoId, title)
        }
    }

    @JavascriptInterface
    fun onStreamUrlDiscovered(url: String) {
        if (url.isNotEmpty() && url.contains("googlevideo.com")) {
            com.example.data.downloader.StreamCaptureRegistry.registerStream(url)
        }
    }

    @JavascriptInterface
    fun onStreamUrlDiscoveredWithVideo(url: String, videoId: String) {
        if (url.isNotEmpty() && url.contains("googlevideo.com")) {
            com.example.data.downloader.StreamCaptureRegistry.registerStream(url, videoId)
        }
    }

    @JavascriptInterface
    fun onTrackMetadataWithProgress(
        title: String,
        artist: String,
        album: String,
        duration: String,
        currentTime: String,
        currentTimeSec: Float,
        durationSec: Float,
        isPlaying: Boolean,
        thumbnailUrl: String,
        url: String,
        streamUrl: String = ""
    ) {
        mainHandler.post {
            val track = TrackMetadata(
                title = title.trim(),
                artist = artist.trim(),
                album = album.trim(),
                duration = duration,
                currentTime = currentTime,
                currentTimeSec = currentTimeSec,
                durationSec = durationSec,
                isPlaying = isPlaying,
                thumbnailUrl = thumbnailUrl,
                url = url,
                streamUrl = streamUrl
            )

            val extractedVid = com.example.data.downloader.StreamUrlResolver.extractVideoId(track.url)
            if (!extractedVid.isNullOrEmpty()) {
                com.example.data.downloader.StreamCaptureRegistry.setActiveVideo(extractedVid, track.title)
            }

            if (isPlaying != lastIsPlaying) {
                lastIsPlaying = isPlaying
                onPlaybackStateChanged(isPlaying)
            }

            if (track.title.isNotEmpty() && track.title != lastTitle) {
                lastTitle = track.title
                onTrackChanged(track)
            } else if (track.title.isNotEmpty()) {
                onTrackChanged(track)
            }
        }
    }

    companion object {
        const val JAVASCRIPT_NAME = "AndroidMediaBridge"

        val BRIDGE_INJECTION_SCRIPT = """
            (function() {
                if (window._ymusicBridgeInstalled) return;
                window._ymusicBridgeInstalled = true;

                function isValidTitle(t) {
                    if (!t) return false;
                    const trimmed = t.trim();
                    if (!trimmed) return false;
                    const lower = trimmed.toLowerCase();
                    return lower !== 'youtube' && 
                           lower !== 'youtube music' && 
                           lower !== 'home' && 
                           lower !== 'subscriptions' && 
                           lower !== 'library' && 
                           lower !== 'explore' && 
                           !lower.startsWith('youtube (');
                }

                function harvestMetadata() {
                    let info = {
                        title: '',
                        artist: '',
                        album: '',
                        duration: '0:00',
                        currentTime: '0:00',
                        currentTimeSec: 0,
                        durationSec: 0,
                        isPlaying: false,
                        thumbnailUrl: '',
                        url: window.location.href,
                        streamUrl: ''
                    };

                    const video = document.querySelector('video') || document.querySelector('audio');
                    if (video) {
                        info.isPlaying = !video.paused && !video.ended && video.readyState > 1;
                        if (info.isPlaying) {
                            window._ymusicShouldBePlaying = true;
                            window._ymusicUserRequestedPause = false;
                        } else {
                            window._ymusicShouldBePlaying = false;
                        }
                        const hasFiniteDuration = isFinite(video.duration) && !isNaN(video.duration) && video.duration > 0;
                        info.currentTimeSec = isFinite(video.currentTime) && !isNaN(video.currentTime) ? (video.currentTime || 0) : 0;
                        info.durationSec = hasFiniteDuration ? video.duration : 0;
                        const fmt = function(secs) {
                            if (!isFinite(secs) || isNaN(secs) || secs < 0) return '0:00';
                            const m = Math.floor(secs / 60);
                            const s = Math.floor(secs % 60);
                            return m + ':' + (s < 10 ? '0' : '') + s;
                        };
                        info.currentTime = fmt(info.currentTimeSec);
                        info.duration = hasFiniteDuration ? fmt(video.duration) : 'LIVE';
                    }

                    // 1. YouTube Player API (#movie_player or .html5-video-player)
                    let videoId = '';
                    try {
                        const moviePlayer = document.querySelector('#movie_player, .html5-video-player');
                        if (moviePlayer && typeof moviePlayer.getVideoData === 'function') {
                            const vData = moviePlayer.getVideoData();
                            if (vData) {
                                if (vData.video_id) {
                                    videoId = vData.video_id;
                                    info.url = 'https://www.youtube.com/watch?v=' + vData.video_id;
                                }
                                if (isValidTitle(vData.title)) {
                                    info.title = vData.title.trim();
                                }
                                if (vData.author && vData.author.trim()) {
                                    info.artist = vData.author.trim();
                                }
                            }
                            if (typeof moviePlayer.getPlayerState === 'function') {
                                const st = moviePlayer.getPlayerState();
                                if (st === 1) {
                                    info.isPlaying = true;
                                    window._ymusicShouldBePlaying = true;
                                } else if (st === 2 || st === 0) {
                                    info.isPlaying = false;
                                    window._ymusicShouldBePlaying = false;
                                }
                            }
                            if (typeof moviePlayer.getPlayerResponse === 'function') {
                                try {
                                    const pResp = moviePlayer.getPlayerResponse();
                                    if (pResp && pResp.streamingData) {
                                        const sd = pResp.streamingData;
                                        const fmts = (sd.formats || []).concat(sd.adaptiveFormats || []);
                                        for (let i = 0; i < fmts.length; i++) {
                                            const f = fmts[i];
                                            if (f && f.url && window.AndroidMediaBridge && typeof window.AndroidMediaBridge.onStreamUrlDiscovered === 'function') {
                                                window.AndroidMediaBridge.onStreamUrlDiscovered(f.url);
                                            }
                                        }
                                    }
                                } catch(err) {}
                            }
                        }
                    } catch(e) {}

                    // 2. YouTube Music player bar (desktop & mobile)
                    const ytmTitle = document.querySelector('ytmusic-player-bar .title, .middle-controls .title');
                    if (ytmTitle && isValidTitle(ytmTitle.textContent)) {
                        info.title = ytmTitle.textContent.trim();
                        const ytmByline = document.querySelector('ytmusic-player-bar .byline, .middle-controls .byline');
                        if (ytmByline) {
                            const links = ytmByline.querySelectorAll('a');
                            if (links.length >= 1 && links[0].textContent.trim()) {
                                info.artist = links[0].textContent.trim();
                            }
                            if (links.length >= 2 && links[1].textContent.trim()) {
                                info.album = links[1].textContent.trim();
                            } else if (!info.artist && ytmByline.textContent.trim()) {
                                info.artist = ytmByline.textContent.trim();
                            }
                        }
                        const ytmThumb = document.querySelector('ytmusic-player-bar .image, ytmusic-player-bar img.image, ytmusic-player-bar .thumbnail img');
                        if (ytmThumb && ytmThumb.src) {
                            info.thumbnailUrl = ytmThumb.src;
                        }
                    }

                    // 3. System MediaSession API (YouTube and YTM populate this automatically)
                    if (navigator.mediaSession && navigator.mediaSession.metadata) {
                        const meta = navigator.mediaSession.metadata;
                        if (isValidTitle(meta.title) && (!info.title || !isValidTitle(info.title))) {
                            info.title = meta.title.trim();
                        }
                        if (meta.artist && meta.artist.trim() && (!info.artist || info.artist === 'YouTube')) {
                            info.artist = meta.artist.trim();
                        }
                        if (meta.album && meta.album.trim() && !info.album) {
                            info.album = meta.album.trim();
                        }
                        if (meta.artwork && meta.artwork.length > 0 && !info.thumbnailUrl) {
                            info.thumbnailUrl = meta.artwork[meta.artwork.length - 1].src;
                        }
                    }

                    // 4. Mobile YouTube & Desktop YouTube DOM selectors for Title
                    if (!isValidTitle(info.title)) {
                        const titleSelectors = [
                            'h2.slim-video-metadata-title',
                            '.slim-video-metadata-title',
                            'ytm-slim-video-metadata-renderer .title',
                            'ytm-slim-video-metadata-renderer h2',
                            '.slim-video-information-title',
                            'ytm-media-item .media-item-headline',
                            '.ytp-title-link',
                            '.ytp-title-full-text',
                            '.ytp-title',
                            'h1.ytd-watch-metadata yt-formatted-string',
                            'ytd-watch-metadata #title h1',
                            '#title h1 yt-formatted-string',
                            '#container h1 .yt-formatted-string'
                        ];
                        for (let sel of titleSelectors) {
                            const el = document.querySelector(sel);
                            if (el && el.textContent && isValidTitle(el.textContent)) {
                                info.title = el.textContent.trim();
                                break;
                            }
                        }
                    }

                    // 5. Channel / Artist DOM Selectors
                    if (!info.artist || info.artist === 'YouTube') {
                        const channelSelectors = [
                            '.slim-owner-channel-name',
                            'ytm-channel-thumbnail-with-link-renderer + .channel-name',
                            '#upload-info #channel-name a',
                            '#owner #channel-name a',
                            'ytd-channel-name a',
                            '.ytp-title-channel-name'
                        ];
                        for (let sel of channelSelectors) {
                            const el = document.querySelector(sel);
                            if (el && el.textContent && el.textContent.trim()) {
                                info.artist = el.textContent.trim();
                                break;
                            }
                        }
                    }

                    // 6. Video ID & URL fallback
                    if (!videoId) {
                        const match = (window.location.href).match(/[?&]v=([a-zA-Z0-9_-]{11})/);
                        if (match && match[1]) {
                            videoId = match[1];
                            info.url = 'https://www.youtube.com/watch?v=' + videoId;
                        } else {
                            const shortsMatch = (window.location.href).match(/\/shorts\/([a-zA-Z0-9_-]{11})/);
                            if (shortsMatch && shortsMatch[1]) {
                                videoId = shortsMatch[1];
                                info.url = 'https://www.youtube.com/watch?v=' + videoId;
                            } else {
                                const liveMatch = (window.location.href).match(/\/live\/([a-zA-Z0-9_-]{11})/);
                                if (liveMatch && liveMatch[1]) {
                                    videoId = liveMatch[1];
                                    info.url = 'https://www.youtube.com/watch?v=' + videoId;
                                }
                            }
                        }
                    }

                    if (videoId && window.AndroidMediaBridge && typeof window.AndroidMediaBridge.onActiveVideoDiscovered === 'function') {
                        window.AndroidMediaBridge.onActiveVideoDiscovered(videoId, info.title || '');
                    }

                    // 7. Thumbnail resolution
                    if (!info.thumbnailUrl && videoId) {
                        info.thumbnailUrl = 'https://i.ytimg.com/vi/' + videoId + '/hqdefault.jpg';
                    }

                    // 8. Document Title fallback (only if non-empty and valid)
                    if (!isValidTitle(info.title) && document.title) {
                        const cleaned = document.title
                            .replace(' - YouTube Music', '')
                            .replace(' - YouTube', '')
                            .trim();
                        if (isValidTitle(cleaned)) {
                            info.title = cleaned;
                        }
                    }

                    // 9. Stream URL
                    try {
                        if (video) {
                            if (video.src && !video.src.startsWith('blob:')) {
                                info.streamUrl = video.src;
                            } else if (video.currentSrc && !video.currentSrc.startsWith('blob:')) {
                                info.streamUrl = video.currentSrc;
                            }
                        }
                    } catch(e) {}

                    // 10. Send to Android Bridge
                    if (window.AndroidMediaBridge) {
                        if (typeof window.AndroidMediaBridge.onTrackMetadataWithProgress === 'function') {
                            window.AndroidMediaBridge.onTrackMetadataWithProgress(
                                info.title,
                                info.artist,
                                info.album,
                                info.duration,
                                info.currentTime,
                                info.currentTimeSec || 0,
                                info.durationSec || 0,
                                info.isPlaying,
                                info.thumbnailUrl,
                                info.url,
                                info.streamUrl
                            );
                        } else {
                            window.AndroidMediaBridge.onTrackMetadata(
                                info.title,
                                info.artist,
                                info.album,
                                info.duration,
                                info.currentTime,
                                info.isPlaying,
                                info.thumbnailUrl,
                                info.url,
                                info.streamUrl
                            );
                        }
                    }

                    // Attach video listeners dynamically whenever video element is available
                    attachVideoListeners();
                }

                function attachVideoListeners() {
                    const video = document.querySelector('video') || document.querySelector('audio');
                    if (video && !video._ymusicListening) {
                        video._ymusicListening = true;
                        ['play', 'playing'].forEach(function(evt) {
                            video.addEventListener(evt, function() {
                                window._ymusicShouldBePlaying = true;
                                window._ymusicUserRequestedPause = false;
                                harvestMetadata();
                            }, { passive: true });
                        });
                        video.addEventListener('pause', function() {
                            if (window._ymusicUserRequestedPause) {
                                window._ymusicShouldBePlaying = false;
                            } else if (window._ymusicShouldBePlaying) {
                                setTimeout(function() {
                                    if (!window._ymusicUserRequestedPause && video.paused) {
                                        video.play().catch(function(){});
                                    }
                                }, 50);
                            }
                            harvestMetadata();
                        }, { passive: true });
                        video.addEventListener('ended', function() {
                            window._ymusicShouldBePlaying = false;
                            harvestMetadata();
                        }, { passive: true });
                        ['timeupdate', 'loadedmetadata', 'ratechange'].forEach(function(evt) {
                            video.addEventListener(evt, function() {
                                harvestMetadata();
                            }, { passive: true });
                        });
                    }
                }

                // Polling interval (350ms for high responsiveness)
                setInterval(harvestMetadata, 350);

                // Event hooks for SPA navigation
                window.addEventListener('yt-navigate-finish', function() { setTimeout(harvestMetadata, 80); });
                window.addEventListener('yt-page-data-updated', function() { setTimeout(harvestMetadata, 80); });
                window.addEventListener('popstate', function() { setTimeout(harvestMetadata, 80); });
                window.addEventListener('state-change', function() { setTimeout(harvestMetadata, 80); });

                // Initial harvest
                harvestMetadata();
            })();
        """.trimIndent()

        // ------------------ Web Controller Invocation Methods ------------------ //

        fun triggerPlayPause(webView: WebView?) {
            val script = """
                (function() {
                    const video = document.querySelector('video') || document.querySelector('audio');
                    const mp = document.querySelector('#movie_player, .html5-video-player');
                    
                    let isPlaying = false;
                    if (mp && typeof mp.getPlayerState === 'function') {
                        isPlaying = (mp.getPlayerState() === 1);
                    } else if (video) {
                        isPlaying = (!video.paused && !video.ended);
                    }

                    if (isPlaying) {
                        window._ymusicUserRequestedPause = true;
                        window._ymusicShouldBePlaying = false;
                        let paused = false;
                        if (mp && typeof mp.pauseVideo === 'function') {
                            try { mp.pauseVideo(); paused = true; } catch(e) {}
                        }
                        if (video && !video.paused) {
                            try { video.pause(); paused = true; } catch(e) {}
                        }
                        if (!paused) {
                            const ytmBtn = document.querySelector('ytmusic-player-bar #play-pause-button[aria-label*="Pause"], ytmusic-player-bar #play-pause-button[title*="Pause"]');
                            if (ytmBtn) { try { ytmBtn.click(); } catch(e) {} }
                            else {
                                const ytBtn = document.querySelector('.ytp-play-button[aria-label*="Pause"], button[aria-label*="Pause"]');
                                if (ytBtn) { try { ytBtn.click(); } catch(e) {} }
                            }
                        }
                    } else {
                        window._ymusicUserRequestedPause = false;
                        window._ymusicShouldBePlaying = true;
                        let resumed = false;
                        if (mp && typeof mp.playVideo === 'function') {
                            try { mp.playVideo(); resumed = true; } catch(e) {}
                        }
                        if (video && video.paused) {
                            try { video.play().catch(function(){}); resumed = true; } catch(e) {}
                        }
                        if (!resumed) {
                            const ytmBtn = document.querySelector('ytmusic-player-bar #play-pause-button[aria-label*="Play"], ytmusic-player-bar #play-pause-button[title*="Play"]');
                            if (ytmBtn) { try { ytmBtn.click(); } catch(e) {} }
                            else {
                                const ytBtn = document.querySelector('.ytp-play-button[aria-label*="Play"], button[aria-label*="Play"]');
                                if (ytBtn) { try { ytBtn.click(); } catch(e) {} }
                            }
                        }
                    }
                    if (typeof harvestMetadata === 'function') {
                        setTimeout(harvestMetadata, 60);
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun triggerNext(webView: WebView?) {
            val script = """
                (function() {
                    const ytmNext = document.querySelector('ytmusic-player-bar .next-button, ytmusic-player-bar #next-button');
                    if (ytmNext) { ytmNext.click(); return; }
                    const ytNext = document.querySelector('.ytp-next-button');
                    if (ytNext) { ytNext.click(); return; }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun triggerPrevious(webView: WebView?) {
            val script = """
                (function() {
                    const ytmPrev = document.querySelector('ytmusic-player-bar .previous-button, ytmusic-player-bar #previous-button');
                    if (ytmPrev) { ytmPrev.click(); return; }
                    const video = document.querySelector('video');
                    if (video) { video.currentTime = 0; }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun seekToFraction(webView: WebView?, fraction: Float) {
            val clamped = fraction.coerceIn(0f, 1f)
            val script = """
                (function() {
                    const video = document.querySelector('video') || document.querySelector('audio');
                    if (video && isFinite(video.duration) && video.duration > 0) {
                        video.currentTime = video.duration * $clamped;
                        if (typeof harvestMetadata === 'function') setTimeout(harvestMetadata, 60);
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun seekToSeconds(webView: WebView?, seconds: Float) {
            val script = """
                (function() {
                    const video = document.querySelector('video') || document.querySelector('audio');
                    if (video) {
                        video.currentTime = Math.max(0, $seconds);
                        if (typeof harvestMetadata === 'function') setTimeout(harvestMetadata, 60);
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun seekForward(webView: WebView?, seconds: Int = 10) {
            val script = """
                (function() {
                    const video = document.querySelector('video');
                    if (video) {
                        const maxTime = isFinite(video.duration) && video.duration > 0 ? video.duration : (video.currentTime + $seconds);
                        video.currentTime = Math.min(maxTime, video.currentTime + $seconds);
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun seekBackward(webView: WebView?, seconds: Int = 10) {
            val script = """
                (function() {
                    const video = document.querySelector('video');
                    if (video) {
                        video.currentTime = Math.max(0, video.currentTime - $seconds);
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun setVolume(webView: WebView?, percent: Int) {
            val vol = (percent.coerceIn(0, 100)) / 100.0
            val script = """
                (function() {
                    const video = document.querySelector('video');
                    if (video) {
                        video.volume = $vol;
                        video.muted = false;
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun likeTrack(webView: WebView?) {
            val script = """
                (function() {
                    const likeBtn = document.querySelector('ytmusic-like-button-renderer #button-shape-like button, #top-level-buttons-computed ytd-toggle-button-renderer:first-child button');
                    if (likeBtn) { likeBtn.click(); }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun pauseWebPlayback(webView: WebView?) {
            val script = """
                (function() {
                    window._ymusicUserRequestedPause = true;
                    window._ymusicShouldBePlaying = false;
                    let paused = false;
                    const mp = document.querySelector('#movie_player, .html5-video-player');
                    if (mp && typeof mp.pauseVideo === 'function') {
                        try { mp.pauseVideo(); paused = true; } catch(e) {}
                    }
                    const video = document.querySelector('video') || document.querySelector('audio');
                    if (video && !video.paused) {
                        try { video.pause(); paused = true; } catch(e) {}
                    }
                    if (!paused) {
                        const ytmPlayBtn = document.querySelector('ytmusic-player-bar #play-pause-button[aria-label*="Pause"], ytmusic-player-bar #play-pause-button[title*="Pause"]');
                        if (ytmPlayBtn) { try { ytmPlayBtn.click(); } catch(e) {} }
                        else {
                            const ytBtn = document.querySelector('.ytp-play-button[aria-label*="Pause"], button[aria-label*="Pause"]');
                            if (ytBtn) { try { ytBtn.click(); } catch(e) {} }
                        }
                    }
                    if (typeof harvestMetadata === 'function') {
                        setTimeout(harvestMetadata, 60);
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }

        fun resumeWebPlayback(webView: WebView?) {
            val script = """
                (function() {
                    window._ymusicUserRequestedPause = false;
                    window._ymusicShouldBePlaying = true;
                    let resumed = false;
                    const mp = document.querySelector('#movie_player, .html5-video-player');
                    if (mp && typeof mp.playVideo === 'function') {
                        try { mp.playVideo(); resumed = true; } catch(e) {}
                    }
                    const video = document.querySelector('video') || document.querySelector('audio');
                    if (video && video.paused) {
                        try { video.play().catch(function(){}); resumed = true; } catch(e) {}
                    }
                    if (!resumed) {
                        const ytmPlayBtn = document.querySelector('ytmusic-player-bar #play-pause-button[aria-label*="Play"], ytmusic-player-bar #play-pause-button[title*="Play"]');
                        if (ytmPlayBtn) { try { ytmPlayBtn.click(); } catch(e) {} }
                        else {
                            const ytBtn = document.querySelector('.ytp-play-button[aria-label*="Play"], button[aria-label*="Play"]');
                            if (ytBtn) { try { ytBtn.click(); } catch(e) {} }
                        }
                    }
                    if (typeof harvestMetadata === 'function') {
                        setTimeout(harvestMetadata, 60);
                    }
                })();
            """.trimIndent()
            webView?.evaluateJavascript(script, null)
        }
    }
}
