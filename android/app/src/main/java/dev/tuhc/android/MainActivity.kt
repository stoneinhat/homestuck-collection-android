package dev.tuhc.android

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.tuhc.android.companion.CompanionDisplayManager
import dev.tuhc.android.companion.DsBridge
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var webView: WebView? = null
    private var server: LocalServer? = null
    private var savedWebState: Bundle? = null
    private var pathBox: EditText? = null
    private var statusLabel: TextView? = null
    private var extracting = false
    private var companionManager: CompanionDisplayManager? = null

    companion object {
        private const val REQ_PICK_FOLDER = 41
        private const val REQ_PICK_ZIP = 42
    }

    private val prefs by lazy { getSharedPreferences("tuhc", Context.MODE_PRIVATE) }

    private val dsInjectJs: String by lazy {
        resources.openRawResource(R.raw.dualscreen).bufferedReader().readText()
    }

    /** Sync callbacks from both WebViews' dual-screen bridges. */
    private val dsListener = object : DsBridge.Listener {
        override fun onDsUrl(role: String, url: String) {
            if (role != "panel") return
            runOnUiThread {
                companionManager?.let {
                    it.leaderUrl = url
                    it.presentationEval(
                        "window.__tuhcDsFollowNav && window.__tuhcDsFollowNav(${JSONObject.quote(url)});"
                    )
                }
            }
        }

        override fun onDsNavRequest(url: String) {
            runOnUiThread {
                // The leader owns history + persistence; navigate it and the
                // resulting pushState report brings the follower along.
                webView?.evaluateJavascript(
                    "window.vm && window.vm.\$pushURL(${JSONObject.quote(url)});",
                    null
                )
            }
        }

        override fun onDsReady(role: String) {
            if (role != "text") return
            runOnUiThread {
                companionManager?.let {
                    it.presentationEval(
                        "window.__tuhcDsFollowNav && window.__tuhcDsFollowNav(${JSONObject.quote(it.leaderUrl)});"
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedWebState = savedInstanceState?.getBundle("webState")

        // Keep the screen on while reading/watching flashes
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val packDir = findAssetPack()
        if (!hasStoragePermission() || packDir == null) {
            showSetupScreen(packDir)
        } else {
            launchApp(packDir)
        }
    }

    // ---------- setup flow ----------

    private fun hasStoragePermission(): Boolean =
        Environment.isExternalStorageManager()

    private fun findAssetPack(): File? {
        val saved = prefs.getString("assetPackDir", null)
        val candidates = listOfNotNull(
            saved?.let { File(it) },
            File(Environment.getExternalStorageDirectory(), "TUHC/AssetPackV2"),
            File(Environment.getExternalStorageDirectory(), "AssetPackV2"),
            File(Environment.getExternalStorageDirectory(), "Download/AssetPackV2")
        )
        return candidates.firstOrNull { isValidPack(it) }
    }

    private fun isValidPack(dir: File): Boolean =
        dir.isDirectory &&
            File(dir, "archive").isDirectory &&
            File(dir, "storyfiles").isDirectory

    @SuppressLint("SetTextI18n")
    private fun showSetupScreen(currentGuess: File?) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#35bfff"))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(Color.BLACK)
            textSize = 15f
            setPadding(0, pad / 2, 0, pad / 2)
        }

        layout.addView(label("THE UNOFFICIAL HOMESTUCK COLLECTION").apply {
            textSize = 20f
            paint.isFakeBoldText = true
        })
        layout.addView(label(
            "One-time setup.\n\n" +
                "1. Grant file access so the app can read the asset pack.\n\n" +
                "2. Point the app at Asset Pack V2: pick the extracted folder, " +
                "or pick the downloaded .zip and the app will extract it for " +
                "you (~4.3 GB, needs ~9 GB free during extraction)."
        ))

        val permBtn = Button(this).apply {
            text = if (hasStoragePermission()) "File access granted ✓" else "Grant file access"
            isEnabled = !hasStoragePermission()
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }
        layout.addView(permBtn)

        val box = EditText(this).apply {
            setText(
                currentGuess?.absolutePath
                    ?: File(
                        Environment.getExternalStorageDirectory(),
                        "TUHC/AssetPackV2"
                    ).absolutePath
            )
            setTextColor(Color.BLACK)
        }
        pathBox = box
        layout.addView(label("Asset Pack V2 folder:"))
        layout.addView(box)

        val pickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        pickRow.addView(Button(this).apply {
            text = "Browse folders…"
            setOnClickListener {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE),
                    REQ_PICK_FOLDER
                )
            }
        })
        pickRow.addView(Button(this).apply {
            text = "Pick .zip instead…"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("application/zip", "application/octet-stream")
                    )
                }
                startActivityForResult(intent, REQ_PICK_ZIP)
            }
        })
        layout.addView(pickRow)

        val status = label("")
        statusLabel = status
        val goBtn = Button(this).apply {
            text = "Start reading"
            setOnClickListener {
                if (!hasStoragePermission()) {
                    status.text = "File access has not been granted yet."
                    return@setOnClickListener
                }
                val dir = File(box.text.toString().trim())
                if (!isValidPack(dir)) {
                    status.text =
                        "That folder doesn't look like Asset Pack V2 " +
                        "(expected 'archive' and 'storyfiles' inside it)."
                    return@setOnClickListener
                }
                prefs.edit().putString("assetPackDir", dir.absolutePath).apply()
                launchApp(dir)
            }
        }
        layout.addView(goBtn)
        layout.addView(status)

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    /** Map a SAF document/tree URI onto a filesystem path. */
    private fun uriToPath(uri: Uri, isTree: Boolean): File? {
        val docId = try {
            if (isTree) android.provider.DocumentsContract.getTreeDocumentId(uri)
            else android.provider.DocumentsContract.getDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) return null
        val (volume, rel) = parts
        val root = if (volume == "primary") {
            Environment.getExternalStorageDirectory()
        } else {
            File("/storage/$volume")
        }
        return File(root, rel)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            REQ_PICK_FOLDER -> {
                val dir = uriToPath(uri, isTree = true)
                if (dir == null) {
                    statusLabel?.text = "Couldn't resolve that folder; type the path instead."
                    return
                }
                pathBox?.setText(dir.absolutePath)
                statusLabel?.text = if (isValidPack(dir)) {
                    "Looks good - tap Start reading."
                } else {
                    "Selected, but this doesn't look like the asset pack " +
                        "(no 'archive' + 'storyfiles' inside)."
                }
            }
            REQ_PICK_ZIP -> {
                if (!hasStoragePermission()) {
                    statusLabel?.text = "Grant file access first, then pick the zip again."
                    return
                }
                extractZip(uri)
            }
        }
    }

    /** Extract a picked asset pack zip to TUHC/ on internal storage. */
    private fun extractZip(uri: Uri) {
        if (extracting) return
        extracting = true
        val destRoot = File(Environment.getExternalStorageDirectory(), "TUHC")
        destRoot.mkdirs()
        statusLabel?.text = "Extracting… this takes a few minutes."
        thread {
            var entries = 0
            var topDir: String? = null
            var error: String? = null
            try {
                contentResolver.openInputStream(uri).use { raw ->
                    ZipInputStream(raw!!.buffered()).use { zin ->
                        var entry = zin.nextEntry
                        while (entry != null) {
                            val name = entry.name.replace('\\', '/')
                            // Zip-slip guard
                            val out = File(destRoot, name).canonicalFile
                            if (!out.path.startsWith(destRoot.canonicalPath)) {
                                entry = zin.nextEntry
                                continue
                            }
                            if (topDir == null && name.contains('/')) {
                                topDir = name.substringBefore('/')
                            }
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fos -> zin.copyTo(fos, 1 shl 16) }
                            }
                            entries++
                            if (entries % 250 == 0) {
                                val n = entries
                                runOnUiThread {
                                    statusLabel?.text = "Extracting… $n files done."
                                }
                            }
                            entry = zin.nextEntry
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            runOnUiThread {
                extracting = false
                if (error != null) {
                    statusLabel?.text = "Extraction failed: $error"
                    return@runOnUiThread
                }
                // The pack zip usually contains one top-level folder
                val candidate = topDir?.let { File(destRoot, it) } ?: destRoot
                val dir = if (isValidPack(candidate)) candidate
                    else if (isValidPack(destRoot)) destRoot else null
                if (dir == null) {
                    statusLabel?.text =
                        "Extracted $entries files, but the result doesn't look " +
                        "like Asset Pack V2. Check ${destRoot.absolutePath}."
                    return@runOnUiThread
                }
                pathBox?.setText(dir.absolutePath)
                prefs.edit().putString("assetPackDir", dir.absolutePath).apply()
                statusLabel?.text = "Done - $entries files."
                launchApp(dir)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the setup screen after returning from the permission dialog
        if (webView == null) {
            val packDir = findAssetPack()
            if (hasStoragePermission() && packDir != null) {
                launchApp(packDir)
            } else {
                showSetupScreen(packDir)
            }
        }
        companionManager?.setAppForeground(true)
    }

    override fun onPause() {
        companionManager?.setAppForeground(false)
        super.onPause()
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        if (newIntent?.hasExtra("ds_force_no_secondary") == true) {
            companionManager?.forceNoSecondary =
                newIntent.getBooleanExtra("ds_force_no_secondary", false)
        }
    }

    // ---------- main app ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun launchApp(packDir: File) {
        // .nomedia keeps the pack's images out of the gallery
        try {
            File(packDir, ".nomedia").createNewFile()
            packDir.parentFile?.takeIf { it.name == "TUHC" }?.let {
                File(it, ".nomedia").createNewFile()
            }
        } catch (e: Exception) {
            // best-effort
        }
        if (server == null) {
            server = LocalServer(assets, packDir).also {
                it.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            }
        } else {
            server?.assetPackDir = packDir
        }

        val wv = WebView(this)
        webView = wv
        WebView.setWebContentsDebuggingEnabled(true)

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
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                android.util.Log.d(
                    "TUHCWebView",
                    "[${msg.messageLevel()}] ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})"
                )
                return true
            }
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                companionManager?.reinjectLeaderIfShowing()
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                // Keep the app inside the WebView; open true externals in a browser
                return if (url.startsWith("http://127.0.0.1") ||
                    url.startsWith("http://localhost")
                ) {
                    false
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        // no browser / offline: ignore
                    }
                    true
                }
            }
        }
        wv.setBackgroundColor(Color.parseColor("#35bfff"))
        wv.defaultFocusHighlightEnabled = false
        // Bridge must be attached before the page loads to be visible to JS.
        wv.addJavascriptInterface(DsBridge("panel", dsListener), "TuhcDs")
        setContentView(wv)
        enterImmersiveMode()
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)

        val state = savedWebState
        if (state != null) {
            wv.restoreState(state)
        } else {
            wv.loadUrl("http://127.0.0.1:${LocalServer.PORT}/")
        }

        if (companionManager == null) {
            companionManager = CompanionDisplayManager(
                activity = this,
                bridgeListener = dsListener,
                injectJs = dsInjectJs,
                leaderEval = { script -> webView?.evaluateJavascript(script, null) },
                onLeaderDualScreen = { dual -> applyLeaderDualScreen(dual) }
            ).also {
                it.forceNoSecondary =
                    intent?.getBooleanExtra("ds_force_no_secondary", false) ?: false
                it.start()
                it.setAppForeground(true)
            }
        }
    }

    /**
     * Dual-screen panel mode must not use WebView overview (fit-to-width)
     * zoom: the collection's viewport is width=650, so overview scales that
     * to the full 1920px top screen and crops the panel vertically.
     */
    private fun applyLeaderDualScreen(dual: Boolean) {
        val wv = webView ?: return
        wv.settings.loadWithOverviewMode = !dual
        if (dual) wv.setInitialScale(100)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0f)
    }

    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && webView != null) enterImmersiveMode()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.let {
            val state = Bundle()
            it.saveState(state)
            outState.putBundle("webState", state)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        companionManager?.stop()
        companionManager = null
        server?.stop()
        server = null
    }
}
