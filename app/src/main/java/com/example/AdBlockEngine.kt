package com.example

import android.net.Uri
import java.util.Collections

/**
 * High-performance ad-blocking and DOM injection engine extracted directly
 * from core/adblocker.py and core/web_page.py.
 */
object AdBlockEngine {

    const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Domain blacklist from core/adblocker.py
     */
    private val BLOCKED_DOMAINS: Set<String> = Collections.unmodifiableSet(
        hashSetOf(
            // Core Google & DoubleClick Ad Networks
            "doubleclick.net",
            "googleads.g.doubleclick.net",
            "pubads.g.doubleclick.net",
            "securepubads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "adservice.google.com",
            "google-analytics.com",
            "analytics.google.com",
            "stats.g.doubleclick.net",
            "cm.g.doubleclick.net",
            "fls-na.amazon-adsystem.com",
            "ad.doubleclick.net",
            "static.doubleclick.net",
            "googlesyndication.com",

            // YouTube ad serving & video promos
            "ad.youtube.com",
            "ads.youtube.com",
            "video-stats.l.google.com",
            "s0.2mdn.net",
            "2mdn.net",

            // Conversion & telemetry beacons
            "www.googleadservices.com",
            "googleadservices.com",
            "pagead2.googleadservices.com",
            "ade.googlesyndication.com",
            "adservice.google.de",
            "ads.google.com"
        )
    )

    /**
     * Fast URL verification matching PyQt6 AdBlockUrlRequestInterceptor
     */
    fun shouldBlock(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path?.lowercase() ?: ""

        // 1. Check adservice.google.* ccTLDs (e.g. adservice.google.de, adservice.google.co.uk)
        if (host.startsWith("adservice.google.") || host.contains(".adservice.google.")) {
            return true
        }

        // 2. Exact match or subdomain suffix match
        for (blockedHost in BLOCKED_DOMAINS) {
            if (host == blockedHost || host.endsWith(".$blockedHost")) {
                return true
            }
        }

        // 3. YouTube ad telemetry and beacon paths without affecting videoplayback
        if (host.contains("youtube.com")) {
            if (path.startsWith("/api/stats/ads") || path.startsWith("/pagead/")) {
                return true
            }
        }

        return false
    }

    /**
     * Injected CSS to cleanly eliminate promotional units, banners, and upsell popups
     */
    val ADBLOCK_CSS = """
        .ytp-ad-overlay-container,
        .ytp-ad-overlay-slot,
        .ytp-ad-text-overlay,
        .ytp-ad-image-overlay,
        ytd-promoted-sparkles-web-renderer,
        ytd-promoted-sparkles-text-search-web-renderer,
        ytd-promoted-video-renderer,
        ytd-display-ad-renderer,
        ytd-banner-promo-renderer,
        ytd-in-feed-ad-layout-renderer,
        ytd-ad-slot-renderer,
        #player-ads,
        #masthead-ad,
        ytmusic-mealbar-promo-renderer,
        ytmusic-banner-promo-renderer,
        ytmusic-popup-container ytmusic-upsell-dialog-renderer,
        tp-yt-paper-dialog:has(ytmusic-upsell-dialog-renderer),
        tp-yt-iron-overlay-backdrop {
            display: none !important;
            visibility: hidden !important;
            height: 0px !important;
            width: 0px !important;
            pointer-events: none !important;
            opacity: 0 !important;
        }
    """.trimIndent()

    /**
     * Precision video ad auto-skip, 16x fast-forward, and popup dismissal JavaScript
     */
    val ADBLOCK_JS = """
        (function() {
            'use strict';
            if (window._ymusicAdBlockerInstalled) return;
            window._ymusicAdBlockerInstalled = true;

            let wasAdShowing = false;

            function handleVideoAds() {
                try {
                    const player = document.querySelector('#movie_player, .html5-video-player');
                    const isAdShowing = player && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting'));
                    const video = document.querySelector('video');

                    if (isAdShowing && video) {
                        wasAdShowing = true;

                        // 1. Immediately click any skip buttons if available
                        const skipButtons = document.querySelectorAll(
                            '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytp-ad-skip-button-text, button.ytp-ad-skip-button-modern, .ytp-ad-preview-container button'
                        );
                        for (let i = 0; i < skipButtons.length; i++) {
                            const btn = skipButtons[i];
                            if (btn && typeof btn.click === 'function') {
                                btn.click();
                            }
                        }

                        // 2. Fast forward the in-stream ad video safely (guarded against live streams)
                        try {
                            const isLive = !isFinite(video.duration);
                            if (!isLive) {
                                video.muted = true;
                                video.playbackRate = 16.0;
                                if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration - 0.2) {
                                    video.currentTime = video.duration - 0.1;
                                }
                            }
                        } catch (e) {}
                    } else if (wasAdShowing && video) {
                        // Ad finished: restore normal playback speed and unmute
                        wasAdShowing = false;
                        try {
                            if (video.playbackRate > 2.0) {
                                video.playbackRate = 1.0;
                            }
                            video.muted = false;
                        } catch (e) {}
                    }

                    // Close overlay close buttons
                    const overlayCloseButtons = document.querySelectorAll(
                        '.ytp-ad-overlay-close-button, .ytp-ad-overlay-close-container'
                    );
                    for (let i = 0; i < overlayCloseButtons.length; i++) {
                        const closeBtn = overlayCloseButtons[i];
                        if (closeBtn && typeof closeBtn.click === 'function') {
                            closeBtn.click();
                        }
                    }

                    // Auto-dismiss "Still watching?", upsell, and consent dialogs
                    const dismissBtns = document.querySelectorAll(
                        'yt-button-renderer#dismiss-button, ytmusic-upsell-dialog-renderer #dismiss-button, #dismiss-button, button.dismiss-button, ytmusic-you-there-renderer #button, button[aria-label="Dismiss"], button[aria-label="Reject all"], button[aria-label="Accept all"], tp-yt-paper-dialog #dismiss-button'
                    );
                    for (let i = 0; i < dismissBtns.length; i++) {
                        const dBtn = dismissBtns[i];
                        if (dBtn && typeof dBtn.click === 'function') {
                            dBtn.click();
                        }
                    }

                    // Remove opened iron backdrops that block interaction on YouTube Music
                    const backdrops = document.querySelectorAll('tp-yt-iron-overlay-backdrop.opened, tp-yt-iron-overlay-backdrop');
                    for (let i = 0; i < backdrops.length; i++) {
                        backdrops[i].remove();
                    }
                } catch (e) {}
            }

            let throttleTimer = null;
            function scheduleAdCheck() {
                if (throttleTimer) return;
                throttleTimer = setTimeout(function() {
                    throttleTimer = null;
                    handleVideoAds();
                }, 500);
            }

            setInterval(scheduleAdCheck, 1000);

            try {
                const observer = new MutationObserver(function() {
                    scheduleAdCheck();
                });

                if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true });
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        if (document.body) {
                            observer.observe(document.body, { childList: true, subtree: true });
                        }
                    });
                }
            } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Document Visibility API spoofing script extracted from core/web_page.py
     */
    val BACKGROUND_PLAYBACK_JS = """
        (function() {
            'use strict';
            if (window._ymusicBgPlaybackInstalled) return;
            window._ymusicBgPlaybackInstalled = true;

            try {
                if (typeof document === 'undefined' || !document) return;

                // Ensure desktop window.chrome mock exists so YouTube Music desktop scripts don't fail
                if (!window.chrome) {
                    window.chrome = { app: {}, runtime: {}, loadTimes: function() {}, csi: function() {} };
                }

                // Unlock audio playback immediately on user tap for YouTube Music
                document.addEventListener('click', function() {
                    const m = document.querySelector('video') || document.querySelector('audio');
                    if (m && m.paused && window._ymusicShouldBePlaying) {
                        m.play().catch(function(){});
                    }
                }, { capture: true, passive: true });

                // 1. Spoof document visibility properties so YouTube never thinks tab is hidden or screen locked
                try {
                    Object.defineProperty(document, 'hidden', {
                        get: function() { return false; },
                        configurable: true
                    });
                    Object.defineProperty(document, 'visibilityState', {
                        get: function() { return 'visible'; },
                        configurable: true
                    });
                    Object.defineProperty(document, 'webkitHidden', {
                        get: function() { return false; },
                        configurable: true
                    });
                    Object.defineProperty(document, 'webkitVisibilityState', {
                        get: function() { return 'visible'; },
                        configurable: true
                    });
                    document.hasFocus = function() { return true; };
                } catch(e) {}

                // 2. Intercept and swallow all visibility, blur, and freeze events
                const swallowEvent = function(e) {
                    if (e) {
                        e.stopImmediatePropagation();
                        e.stopPropagation();
                    }
                };

                ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide', 'freeze', 'mouseleave'].forEach(function(evt) {
                    window.addEventListener(evt, swallowEvent, true);
                    document.addEventListener(evt, swallowEvent, true);
                });

                // Prevent page from overwriting onvisibilitychange / onblur
                try {
                    Object.defineProperty(document, 'onvisibilitychange', {
                        get: function() { return null; },
                        set: function() {},
                        configurable: true
                    });
                    Object.defineProperty(window, 'onblur', {
                        get: function() { return null; },
                        set: function() {},
                        configurable: true
                    });
                } catch(e) {}

                // 3. Spoof IntersectionObserver so YouTube thinks the player is always visible on screen
                try {
                    if (window.IntersectionObserver) {
                        const OriginalIntersectionObserver = window.IntersectionObserver;
                        window.IntersectionObserver = function(callback, options) {
                            const wrappedCallback = function(entries, observer) {
                                const modified = entries.map(function(entry) {
                                    if (entry.target && (
                                        entry.target.tagName === 'VIDEO' ||
                                        entry.target.tagName === 'AUDIO' ||
                                        entry.target.id === 'movie_player' ||
                                        (entry.target.classList && entry.target.classList.contains('html5-video-player'))
                                    )) {
                                        return new Proxy(entry, {
                                            get: function(target, prop) {
                                                if (prop === 'isIntersecting') return true;
                                                if (prop === 'intersectionRatio') return 1;
                                                return target[prop];
                                            }
                                        });
                                    }
                                    return entry;
                                });
                                return callback(modified, observer);
                            };
                            return new OriginalIntersectionObserver(wrappedCallback, options);
                        };
                        window.IntersectionObserver.prototype = OriginalIntersectionObserver.prototype;
                    }
                } catch(e) {}

                // 4. Track media playback state for seamless lockscreen and bridge synchronization
                const onPlay = function() {
                    window._ymusicWasPlaying = true;
                    window._ymusicShouldBePlaying = true;
                    window._ymusicUserRequestedPause = false;
                };
                const onPause = function() {
                    window._ymusicWasPlaying = false;
                    window._ymusicShouldBePlaying = false;
                };

                const bindMediaEvents = function(mediaEl) {
                    if (!mediaEl || mediaEl._ymusicBound) return;
                    mediaEl._ymusicBound = true;
                    mediaEl.addEventListener('play', onPlay, true);
                    mediaEl.addEventListener('playing', onPlay, true);
                    mediaEl.addEventListener('pause', onPause, true);
                };

                const initialMedia = document.querySelector('video') || document.querySelector('audio');
                if (initialMedia) bindMediaEvents(initialMedia);

                // Observe DOM for newly attached video/audio elements (e.g. on navigation)
                try {
                    const mediaObserver = new MutationObserver(function() {
                        const m = document.querySelector('video') || document.querySelector('audio');
                        if (m) bindMediaEvents(m);
                    });
                    if (document.documentElement) {
                        mediaObserver.observe(document.documentElement, { childList: true, subtree: true });
                    }
                } catch(e) {}

            } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Wraps CSS injection into script element
     */
    fun getCssInjectionScript(): String {
        val sanitizedCss = ADBLOCK_CSS.replace("`", "\\`")
        return """
            (function() {
                try {
                    let existing = document.getElementById('ymusic-adblock-styles');
                    if (!existing) {
                        const style = document.createElement('style');
                        style.id = 'ymusic-adblock-styles';
                        style.type = 'text/css';
                        style.textContent = `$sanitizedCss`;
                        if (document.head) {
                            document.head.appendChild(style);
                        } else if (document.documentElement) {
                            document.documentElement.appendChild(style);
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
