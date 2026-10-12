package com.example

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.webkit.WebView

/**
 * Custom WebView inspired by YMusic's decompiled WebViewYtPlayer architecture:
 * Forces getWindowVisibility() to return View.VISIBLE and intercepts onWindowVisibilityChanged()
 * so Chromium's web engine never halts media playback, buffering, or JavaScript timers
 * when the screen is turned off or when the app is placed in the background.
 */
class YMusicCustomWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    override fun getWindowVisibility(): Int {
        return View.VISIBLE
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, View.VISIBLE)
    }
}
