package dev.tuhc.android.companion

import android.webkit.JavascriptInterface

/**
 * JS <-> Android bridge for dual-screen sync, exposed to both WebViews as
 * `window.TuhcDs`. One instance per WebView, each tagged with its role:
 * "panel" (top screen, the leader that owns history/persistence) or "text"
 * (bottom screen, the follower).
 */
class DsBridge(
    private val role: String,
    private val listener: Listener,
) {
    interface Listener {
        /** Leader reports its current SPA url after every navigation. */
        fun onDsUrl(role: String, url: String)

        /** Follower asks for a navigation; it is executed on the leader. */
        fun onDsNavRequest(url: String)

        /** A WebView's Vue root is mounted and the dual-screen JS is live. */
        fun onDsReady(role: String)
    }

    @JavascriptInterface
    fun notifyUrl(url: String) = listener.onDsUrl(role, url)

    @JavascriptInterface
    fun requestNav(url: String) = listener.onDsNavRequest(url)

    @JavascriptInterface
    fun ready() = listener.onDsReady(role)

    @JavascriptInterface
    fun getRole(): String = role
}
