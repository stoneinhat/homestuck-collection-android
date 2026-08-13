package dev.tuhc.android.companion

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.tuhc.android.R

/**
 * Bottom-screen window that shows the story text. Hosts its own WebView
 * pointed at the same localhost server as the main Activity's WebView.
 *
 * Follows the proven AYN Thor presentation rules: never focusable (so
 * gamepad/keyboard focus stays on the game/reader up top - touch is still
 * delivered here), landscape-locked (the Thor's bottom panel is natively
 * portrait with a rotated landscape override), and fully recreated on
 * display changes rather than reused.
 */
class TextPresentation(
    outerContext: Context,
    display: Display,
    private val startUrl: String,
    private val bridge: DsBridge,
    private val injectJs: String,
) : Presentation(outerContext, display, R.style.CompanionPresentationTheme) {

    var webView: WebView? = null
        private set

    init {
        // A Presentation is a Dialog. Outside-touch cancel would treat the
        // *top* screen as "outside" and can dim/select the Activity on first tap.
        setCancelable(false)
        setCanceledOnTouchOutside(false)
        if (outerContext is Activity) {
            setOwnerActivity(outerContext)
        }
    }

    private fun suppressDialogDim() {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            attributes = attributes.apply {
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                dimAmount = 0f
            }
        }
        ownerActivity?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0f)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        suppressDialogDim()

        window?.apply {
            decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }

        val wv = WebView(context)
        webView = wv
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
        }
        wv.setBackgroundColor(Color.BLACK)
        wv.defaultFocusHighlightEnabled = false
        // Must be attached before the page loads to be visible to its JS.
        wv.addJavascriptInterface(bridge, "TuhcDs")
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                // Role must be set before the shared script runs so it
                // self-activates as the text follower.
                view.evaluateJavascript(
                    "window.__TUHC_DS_ROLE__='text';\n$injectJs",
                    null
                )
            }
        }
        setContentView(wv)
        wv.loadUrl(startUrl)
    }

    override fun onStart() {
        super.onStart()
        // Dialog.show() can re-apply theme dim after onCreate.
        suppressDialogDim()
    }

    fun evalJs(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    override fun onStop() {
        webView?.destroy()
        webView = null
        super.onStop()
    }
}
