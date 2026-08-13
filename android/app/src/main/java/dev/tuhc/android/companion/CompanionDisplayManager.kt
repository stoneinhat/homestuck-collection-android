package dev.tuhc.android.companion

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import dev.tuhc.android.LocalServer

/**
 * Owns the lifecycle of the bottom-screen [TextPresentation].
 *
 * Rules (from the AYN Thor dual-screen mod guide): discover displays through
 * DISPLAY_CATEGORY_PRESENTATION every time and never cache display ids, show
 * the presentation only while the app is foregrounded (with a short grace
 * period so transient focus loss doesn't flicker it), and rebuild it from
 * scratch when displays come and go.
 */
class CompanionDisplayManager(
    private val activity: Activity,
    private val bridgeListener: DsBridge.Listener,
    private val injectJs: String,
    /** Runs a JS snippet in the main (top screen) WebView. */
    private val leaderEval: (String) -> Unit,
    /** True while the companion is up so the leader can leave fit-to-width. */
    private val onLeaderDualScreen: (Boolean) -> Unit = {},
) {
    companion object {
        private const val TAG = "TUHCCompanion"
        private const val BACKGROUND_GRACE_MS = 400L
    }

    private val displayManager =
        activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())

    private var presentation: TextPresentation? = null
    private var appForeground = false
    private var started = false

    /** Last SPA path reported by the leader; new followers start here. */
    var leaderUrl: String = "/"

    /** Debug switch: `adb shell am start ... --ez ds_force_no_secondary true` */
    var forceNoSecondary: Boolean = false
        set(value) {
            field = value
            if (started) refresh()
        }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) {
            // Ignore: the primary display fires this on rotation while the
            // companion is up; rebuilding here causes flicker loops.
        }
    }

    fun start() {
        if (started) return
        started = true
        displayManager.registerDisplayListener(displayListener, handler)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        displayManager.unregisterDisplayListener(displayListener)
        dismiss()
    }

    fun setAppForeground(foreground: Boolean) {
        appForeground = foreground
        if (foreground) {
            refresh()
        } else {
            handler.postDelayed({ if (!appForeground) dismiss() }, BACKGROUND_GRACE_MS)
        }
    }

    fun isShowing(): Boolean = presentation?.isShowing == true

    fun presentationEval(script: String) {
        presentation?.evalJs(script)
    }

    fun refresh() {
        if (!started || !appForeground || forceNoSecondary) {
            dismiss()
            return
        }
        val display = findSecondaryPresentationDisplay()
        if (display == null) {
            dismiss()
            return
        }
        val current = presentation
        if (current != null && current.isShowing &&
            current.display.displayId == display.displayId
        ) {
            return
        }
        dismiss()

        val startUrl = "http://127.0.0.1:${LocalServer.PORT}$leaderUrl"
        val next = TextPresentation(
            activity,
            display,
            startUrl,
            DsBridge("text", bridgeListener),
            injectJs
        )
        try {
            next.show()
            presentation = next
            Log.i(TAG, "Text companion shown on display ${display.displayId}")
            activateLeader()
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.w(TAG, "Display vanished while showing companion", e)
        }
    }

    private fun dismiss() {
        presentation?.let {
            try {
                it.dismiss()
            } catch (e: Exception) {
                Log.w(TAG, "Error dismissing companion", e)
            }
            deactivateLeader()
            Log.i(TAG, "Text companion dismissed")
        }
        presentation = null
    }

    /** Switch the top WebView into panel mode (hide text, report urls). */
    private fun activateLeader() {
        onLeaderDualScreen(true)
        leaderEval("$injectJs\n;window.__tuhcDsActivate && window.__tuhcDsActivate('panel');")
    }

    /**
     * Re-arm the leader after its WebView (re)loads a page: page loads wipe
     * injected JS, and the companion may have been shown before the main
     * page finished loading. Call from the main WebViewClient.onPageFinished.
     */
    fun reinjectLeaderIfShowing() {
        if (isShowing()) activateLeader()
    }

    /** Restore the top WebView to normal single-screen rendering. */
    private fun deactivateLeader() {
        onLeaderDualScreen(false)
        leaderEval("window.__tuhcDsDeactivate && window.__tuhcDsDeactivate();")
    }

    private fun findSecondaryPresentationDisplay(): Display? =
        displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }
}
