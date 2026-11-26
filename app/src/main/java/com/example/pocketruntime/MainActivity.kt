package com.example.pocketruntime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.database.Cursor
import android.view.Gravity
import android.view.View
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {
    companion object {
        private const val NETWORK_TIMEOUT_MS = 30_000
        private const val MAX_PACKAGE_ARCHIVE_BYTES = 50 * 1024 * 1024
        private const val MAX_UNPACKED_PACKAGE_BYTES = 50L * 1024 * 1024
        private const val MAX_PACKAGE_FILES = 2_000
        private const val TAR_BLOCK_SIZE = 512
        private const val PREF_MODEL_ID = "token_harbor_model_id"
        private const val MAX_HTML_BYTES = 2 * 1024 * 1024
    }

    private lateinit var rootView: FrameLayout
    private lateinit var runtimeWebView: WebView
    private lateinit var runtimeRoot: File
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var registry: AppRegistry
    private lateinit var secretStore: SecretStore
    private lateinit var provider: TokenHarborProvider
    private lateinit var agentController: AgentController
    private lateinit var appErrorStore: AppErrorStore
    private var selectedApp: LocalApp? = null
    private var currentScreen = "dashboard"
    private var cancellation: AgentCancellation? = null
    private var agentTask: Future<*>? = null
    private var currentStatus: TextView? = null
    private var currentMessages: LinearLayout? = null
    private var currentMessageScroll: ScrollView? = null
    private var transferServer: ProjectTransferServer? = null
    private var transferStatus: TextView? = null
    private val installerExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val appErrors = ConcurrentHashMap<String, String>()
    private val preferences by lazy { getSharedPreferences("runtime_settings", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runtimeRoot = File(filesDir, "root")
        val hadRegistry = File(runtimeRoot, "metadata/apps.json").isFile
        prepareStorage(hadRegistry)
        appErrorStore = AppErrorStore(runtimeRoot)
        registry = AppRegistry(runtimeRoot)
        migrateLegacyApp(hadRegistry)
        ensureDemoApplication(hadRegistry)
        secretStore = SecretStore(filesDir)
        provider = TokenHarborProvider(secretStore)
        agentController = AgentController(runtimeRoot, provider)
        rootView = FrameLayout(this)
        runtimeWebView = WebView(this)
        rootView.addView(runtimeWebView, FrameLayout.LayoutParams(-1, -1))
        setContentView(rootView)
        configureWebView()
        showDashboard()
    }

    override fun onDestroy() {
        cancellation?.cancel()
        transferServer?.close()
        transferServer = null
        installerExecutor.shutdownNow()
        runtimeWebView.destroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rootView.requestLayout()
        runtimeWebView.requestLayout()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentScreen != "dashboard") {
            showDashboard()
            return
        }
        super.onBackPressed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val settings = runtimeWebView.settings
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/apps/", appPathHandler())
            .addPathHandler("/packages/", packagePathHandler())
            .addPathHandler("/root/", legacyRootPathHandler())
            .build()

        runtimeWebView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest
            ) = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val uri = request.url
                val app = selectedApp
                return uri.scheme != "https" ||
                    uri.host != "appassets.androidplatform.net" ||
                    (app == null || !uri.path.orEmpty().startsWith("/apps/${app.directory}/")) &&
                    !uri.path.orEmpty().startsWith("/packages/")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    selectedApp?.let {
                        recordAppError(
                            it.id,
                            error.description?.toString() ?: "Application failed to load."
                        )
                    }
                }
            }
        }
        runtimeWebView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                if (message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                    selectedApp?.let {
                        recordAppError(
                            it.id,
                            "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                        )
                    }
                }
                return true
            }
        }
    }

    private fun appPathHandler() = WebViewAssetLoader.PathHandler { path ->
        val app = selectedApp
        if (app == null) null else {
            val prefix = "${app.directory}/"
            if (!path.startsWith(prefix)) null else {
                val appDirectory = registry.directoryFor(app)
                val file = File(appDirectory, path.removePrefix(prefix)).canonicalFile
                if (!file.path.startsWith(appDirectory.canonicalPath + File.separator) || !file.isFile) {
                    null
                } else {
                    if (file.name.equals("index.html", ignoreCase = true) && file.parentFile == appDirectory) {
                        serveHtmlWithImportMap(file, appDirectory)
                    } else {
                        WebResourceResponse(mimeType(file), null, FileInputStream(file))
                    }
                }
            }
        }
    }

    private fun packagePathHandler() = WebViewAssetLoader.PathHandler { path ->
        servePackageResource(path)
    }

    private fun legacyRootPathHandler() = WebViewAssetLoader.PathHandler { path ->
        val app = selectedApp
        when {
            path.startsWith("app/") && app != null -> {
                val appDirectory = registry.directoryFor(app)
                val file = File(appDirectory, path.removePrefix("app/")).canonicalFile
                if (!file.path.startsWith(appDirectory.canonicalPath + File.separator) || !file.isFile) {
                    null
                } else {
                    WebResourceResponse(mimeType(file), null, FileInputStream(file))
                }
            }
            path.startsWith("packages/") -> servePackageResource(path.removePrefix("packages/"))
            else -> null
        }
    }

    private fun servePackageResource(path: String): WebResourceResponse? {
        val segments = path.split('/').filter(String::isNotEmpty)
        return if (segments.size < 3 || segments.any { it == "." || it == ".." || it.contains('\\') || it.contains(':') }) {
            null
        } else {
            val packagesRoot = File(runtimeRoot, "packages").canonicalFile
            val file = File(packagesRoot, segments.joinToString(File.separator)).canonicalFile
            if (!file.path.startsWith(packagesRoot.path + File.separator) || !file.isFile) null
            else WebResourceResponse(mimeType(file), null, FileInputStream(file))
        }
    }

    private fun prepareStorage(hadRegistry: Boolean) {
        require(runtimeRoot.mkdirs() || runtimeRoot.isDirectory) { "Could not create runtime storage." }
        listOf("apps", "packages", "metadata", "agent").forEach {
            val directory = File(runtimeRoot, it)
            require(directory.mkdirs() || directory.isDirectory) { "Could not create $it storage." }
        }
        if (!hadRegistry) {
            val legacyApp = File(runtimeRoot, "app")
            if (legacyApp.isDirectory && (legacyApp.list()?.isNotEmpty() == true)) {
                val appsRoot = File(runtimeRoot, "apps")
                var target = File(appsRoot, "twitch-chat")
                var suffix = 2
                while (target.exists()) target = File(appsRoot, "twitch-chat-${suffix++}")
                if (!legacyApp.renameTo(target)) {
                    legacyApp.copyRecursively(target, overwrite = false)
                    if (!legacyApp.deleteRecursively()) {
                        throw IllegalStateException("Legacy app was copied but could not be removed from its old location.")
                    }
                }
            }
        }
    }

    private fun migrateLegacyApp(hadRegistry: Boolean) {
        if (hadRegistry) return
        val appsRoot = File(runtimeRoot, "apps")
        val legacy = appsRoot.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.startsWith("twitch-chat") }
            ?: return
        registry.registerExisting("Twitch Chat", legacy.name)
    }

    private fun ensureDemoApplication(hadRegistry: Boolean) {
        if (hadRegistry || registry.list().isNotEmpty()) return
        val app = registry.create("Twitch Chat")
        val directory = registry.directoryFor(app)
        writeFile(File(directory, "packages.json"), packagesManifest())
        writeFile(File(directory, "index.html"), appIndexHtml())
        writeFile(File(directory, "style.css"), appStyles())
        writeFile(File(directory, "app.js"), appScript())
    }

    private fun serveHtmlWithImportMap(file: File, appDirectory: File): WebResourceResponse {
        require(file.length() <= MAX_HTML_BYTES) { "index.html exceeds the 2 MB limit." }
        val html = file.readText(Charsets.UTF_8)
        val generatedMap = buildImportMap(appDirectory)
        val generatedImports = generatedMap.getJSONObject("imports")
        val importMapPattern = Regex("(?is)<script\\b(?=[^>]*\\btype\\s*=\\s*['\\\"]?importmap)[^>]*>(.*?)</script>")
        val existingMapTag = importMapPattern.find(html)
        val transformed = if (existingMapTag != null) {
            if (generatedImports.length() == 0) {
                html
            } else {
                val existingMap = JSONObject(existingMapTag.groupValues[1])
                val existingImports = if (existingMap.has("imports")) {
                    existingMap.optJSONObject("imports")
                        ?: throw IllegalArgumentException("The app's import map 'imports' field must be an object.")
                } else {
                    JSONObject()
                }
                val keys = generatedImports.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!existingImports.has(key)) existingImports.put(key, generatedImports.get(key))
                }
                existingMap.put("imports", existingImports)
                val mergedTag = existingMap.toString().replace("<", "\\u003c")
                val bodyRange = existingMapTag.groups[1]!!.range
                html.replaceRange(bodyRange, mergedTag)
            }
        } else if (generatedImports.length() == 0) {
            html
        } else {
            val script = "<script type=\"importmap\">${generatedMap.toString().replace("<", "\\u003c")}</script>"
            val insertion = Regex("(?i)<head[^>]*>").find(html)?.range?.last?.plus(1)
            if (insertion != null) html.substring(0, insertion) + script + html.substring(insertion)
            else script + html
        }
        return WebResourceResponse("text/html", "UTF-8", transformed.byteInputStream())
    }

    private fun buildImportMap(appDirectory: File): JSONObject {
        val manifest = File(appDirectory, "packages.json")
        if (!manifest.isFile) return JSONObject().put("imports", JSONObject())
        val dependencies = JSONObject(manifest.readText()).optJSONObject("dependencies") ?: JSONObject()
        val imports = JSONObject()
        val names = dependencies.keys()
        while (names.hasNext()) {
            val name = names.next()
            val version = dependencies.optString(name)
            if (!name.matches(Regex("[a-z0-9][a-z0-9._-]*")) ||
                !version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) continue
            val packageDirectory = File(File(File(runtimeRoot, "packages"), name), version)
            val packageJson = File(packageDirectory, "package.json")
            if (!packageJson.isFile) continue
            val metadata = JSONObject(packageJson.readText())
            val candidates = mutableListOf<String>()
            listOf("browser", "module", "main").forEach { field ->
                metadata.optString(field).takeIf(String::isNotBlank)?.let(candidates::add)
            }
            val exports = metadata.opt("exports")
            when (exports) {
                is String -> candidates.add(exports)
                is JSONObject -> {
                    val rootExport = exports.opt(".")
                    when (rootExport) {
                        is String -> candidates.add(rootExport)
                        is JSONObject -> listOf("browser", "import", "default")
                            .forEach { condition -> rootExport.optString(condition).takeIf(String::isNotBlank)?.let(candidates::add) }
                    }
                }
            }
            candidates.add("index.js")
            val entry = candidates.firstNotNullOfOrNull { candidate ->
                if (candidate.startsWith("/") || candidate.contains('\\') || candidate.split('/').any { it == ".." }) {
                    null
                } else {
                    val entryFile = File(packageDirectory, candidate).canonicalFile
                    candidate.takeIf {
                        entryFile.path.startsWith(packageDirectory.canonicalPath + File.separator) && entryFile.isFile
                    }
                }
            }
            if (entry != null) {
                val encodedEntry = entry.split('/').joinToString("/") { Uri.encode(it) }
                val url = "https://appassets.androidplatform.net/packages/$name/$version/$encodedEntry"
                imports.put(name, url)
                imports.put("$name/", "https://appassets.androidplatform.net/packages/$name/$version/")
            }
        }
        return JSONObject().put("imports", imports)
    }

    private fun mimeType(file: File): String {
        val extension = file.extension.lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: when (extension) {
                "mjs" -> "text/javascript"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                else -> "application/octet-stream"
            }
    }

    private fun loadApplication(app: LocalApp) {
        selectedApp = app
        val applicationDirectory = registry.directoryFor(app)
        val entryPoint = File(applicationDirectory, "index.html")
        if (!entryPoint.isFile) {
            AlertDialog.Builder(this)
                .setTitle("App is not ready")
                .setMessage("${app.name} has no index.html yet. Open its app generator to create one.")
                .setNegativeButton("Back", null)
                .setPositiveButton("Open generator") { _, _ -> showEditor(app) }
                .show()
            return
        }
        showStatusScreen(app.name, "Preparing app and checking dependencies…")
        installerExecutor.execute {
            try {
                installDependencies(applicationDirectory)
                registry.markOpened(app)
                mainHandler.post {
                    if (currentScreen != "status" || selectedApp?.id != app.id) return@post
                    clearAppError(app.id)
                    currentScreen = "runner"
                    rootView.removeAllViews()
                    rootView.addView(runtimeWebView, FrameLayout.LayoutParams(-1, -1))
                    runtimeWebView.onResume()
                    runtimeWebView.loadUrl("https://appassets.androidplatform.net/apps/${app.directory}/index.html")
                }
            } catch (error: Exception) {
                recordAppError(app.id, error.message ?: "Application preparation failed.")
                mainHandler.post {
                    if (currentScreen == "status" && selectedApp?.id == app.id) {
                        showStatusScreen(app.name, "Could not prepare app:\n${error.message ?: "Unknown error"}")
                    }
                }
            }
        }
    }

    private fun showDashboard() {
        cancellation?.cancel()
        cancellation = null
        transferServer?.close()
        transferServer = null
        transferStatus = null
        if (currentScreen == "runner") {
            runtimeWebView.stopLoading()
            runtimeWebView.onPause()
        }
        selectedApp = null
        currentScreen = "dashboard"
        currentStatus = null
        currentMessages = null
        currentMessageScroll = null

        val screen = column()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(
            label("Pocket Runtime", 26f, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        top.addView(actionButton("Manage") { showManageApps() })
        top.addView(actionButton("Settings") { showSettings() })
        screen.addView(top)
        screen.addView(label("Your local apps", 16f, false).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(18), 0, dp(8))
        })
        val scroll = ScrollView(this)
        val appList = column()
        val apps = registry.list()
        if (apps.isEmpty()) {
            appList.addView(
                label(
                    "No apps yet. Add an empty project, then long-press its card to ask the app generator to build it.",
                    16f,
                    false
                ).apply {
                    setTextColor(Color.LTGRAY)
                    setPadding(dp(16), dp(20), dp(16), dp(20))
                }
            )
        } else {
            apps.chunked(2).forEach { rowApps ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                rowApps.forEachIndexed { index, app ->
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(4), dp(4), dp(4), dp(8))
                    }
                    val card = label("${app.name}\nTap to run\nLong-press to generate", 15f, true).apply {
                        setPadding(dp(12), dp(16), dp(12), dp(16))
                        setBackgroundColor(Color.rgb(31, 41, 55))
                        minHeight = dp(112)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { loadApplication(app) }
                        setOnLongClickListener {
                            showEditor(app)
                            true
                        }
                    }
                    tile.addView(card, LinearLayout.LayoutParams(-1, -2))
                    row.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        if (index == 0) marginEnd = dp(4) else marginStart = dp(4)
                    })
                }
                if (rowApps.size == 1) {
                    row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                }
                appList.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
        }
        scroll.addView(appList)
        screen.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val projectActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        projectActions.addView(actionButton("Connect to PC") { showComputerTransfer() },
            LinearLayout.LayoutParams(0, -2, 1f))
        projectActions.addView(actionButton("Add App") { showCreateAppDialog() },
            LinearLayout.LayoutParams(0, -2, 1f))
        screen.addView(projectActions)
        setScreen(screen)
    }

    private fun showCreateAppDialog() {
        val nameInput = EditText(this).apply {
            hint = "Project name"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Add an empty app")
            .setMessage("This creates an empty project folder. Use the app generator to add files.")
            .setView(nameInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Create") { _, _ ->
                try {
                    registry.create(nameInput.text.toString())
                    showDashboard()
                } catch (error: Exception) {
                    showError("Could not create app", error.message ?: "Please try a different name.")
                }
            }
            .show()
    }

    private fun showManageApps() {
        currentScreen = "manage-apps"
        selectedApp = null
        val screen = column()
        screen.addView(header("Manage apps") { showDashboard() })
        screen.addView(label("Delete a project and its saved agent conversation. Shared packages are kept.", 14f, false).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(12), 0, dp(12))
        })
        registry.list().forEach { app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(6), dp(4), dp(6))
                setBackgroundColor(Color.rgb(31, 41, 55))
            }
            row.addView(label(app.name, 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(actionButton("Delete") { confirmDeleteApp(app) })
            screen.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
            })
        }
        setScreen(screen)
    }

    private fun showComputerTransfer() {
        currentScreen = "computer-transfer"
        selectedApp = null
        transferStatus = null
        val screen = column()
        screen.addView(header("Connect to computer") { showDashboard() })
        screen.addView(label(
            "Open the address below in a computer browser on the same trusted Wi-Fi. Enter the one-time code to send a project folder to your phone or download an app.",
            15f,
            false
        ).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(16), 0, dp(12))
        })
        val activeServer = transferServer
        if (activeServer != null) {
            screen.addView(label("Computer address", 14f, true).apply { setPadding(0, dp(10), 0, dp(4)) })
            screen.addView(label(activeServer.url, 18f, true).apply {
                setTextIsSelectable(true)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setBackgroundColor(Color.rgb(31, 41, 55))
            })
            screen.addView(label("One-time pairing code", 14f, true).apply { setPadding(0, dp(12), 0, dp(4)) })
            screen.addView(label(activeServer.pairingCode, 30f, true).apply {
                setTextIsSelectable(true)
                setPadding(0, dp(4), 0, dp(10))
            })
            screen.addView(label("Transfer automatically stops after 10 minutes. Only use this on a trusted network.", 13f, false).apply {
                setTextColor(Color.LTGRAY)
            })
            screen.addView(actionButton("Stop transfer") {
                transferServer?.close()
                transferServer = null
                showComputerTransfer()
            })
        } else {
            val status = label("Transfer server is off.", 14f, false).apply {
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(12), 0, dp(12))
            }
            transferStatus = status
            screen.addView(status)
            screen.addView(actionButton("Start transfer") { startComputerTransfer() })
        }
        setScreen(screen)
    }

    private fun startComputerTransfer() {
        transferStatus?.text = "Starting local transfer server…"
        installerExecutor.execute {
            try {
                val server = ProjectTransferServer(
                    registry = registry,
                    temporaryDirectory = cacheDir,
                    onProjectImported = { app ->
                        mainHandler.post {
                            if (currentScreen == "computer-transfer") {
                                transferStatus?.text = "${app.name} received from your computer."
                            }
                        }
                    }
                )
                transferServer = server
                mainHandler.post {
                    if (currentScreen == "computer-transfer") showComputerTransfer()
                    else {
                        server.close()
                        transferServer = null
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (currentScreen == "computer-transfer") {
                        transferStatus?.text = error.message ?: "Could not start the transfer server."
                    }
                }
            }
        }
    }

    private fun confirmDeleteApp(app: LocalApp) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${app.name}?")
            .setMessage("This permanently deletes this app's project files and its saved agent conversation. Shared packages are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                try {
                    registry.delete(app)
                    agentController.clearConversation(app)
                    appErrorStore.clear(app.id)
                    val backups = File(runtimeRoot, "metadata/backups/${app.id}")
                    if (backups.exists() && !backups.deleteRecursively()) {
                        throw IllegalStateException("App deleted, but its backup files could not be removed.")
                    }
                    appErrors.remove(app.id)
                    showDashboard()
                } catch (error: Exception) {
                    showError("Could not fully delete app", error.message ?: "The app or its associated data could not be removed.")
                    showDashboard()
                }
            }
            .show()
    }

    private fun showSettings() {
        cancellation?.cancel()
        cancellation = null
        currentScreen = "settings"
        selectedApp = null
        val screen = column()
        screen.addView(header("Settings") { showDashboard() })
        screen.addView(label("AI provider", 16f, true).apply { setPadding(0, dp(16), 0, dp(6)) })
        screen.addView(label("Token Harbor", 16f, false).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(4), 0, dp(12))
        })

        val keyInput = EditText(this).apply {
            hint = if (secretStore.isConfigured()) "Saved key protected; enter to replace" else "Token Harbor API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        screen.addView(keyInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val keyActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        lateinit var reveal: Button
        reveal = actionButton("Reveal") {
            val visible = keyInput.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
            keyInput.inputType = InputType.TYPE_CLASS_TEXT or
                if (visible) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            reveal.text = if (visible) "Reveal" else "Hide"
            keyInput.setSelection(keyInput.text.length)
        }
        keyActions.addView(reveal, LinearLayout.LayoutParams(0, -2, 1f))
        keyActions.addView(actionButton("Clear key") {
            AlertDialog.Builder(this)
                .setTitle("Clear API key?")
                .setMessage("The saved Token Harbor key will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    try {
                        secretStore.clear()
                        keyInput.hint = "Token Harbor API key"
                        showSettings()
                    } catch (error: Exception) {
                        showError("Could not clear key", error.message ?: "Credential storage failed.")
                    }
                }
                .show()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        screen.addView(keyActions)

        val selectedModel = arrayOf(preferences.getString(PREF_MODEL_ID, "").orEmpty())
        val modelLabel = label(modelDisplay(selectedModel[0]), 15f, false).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(14), 0, dp(8))
        }
        screen.addView(modelLabel)
        var availableModels = emptyList<ProviderModel>()
        val status = label(
            if (secretStore.isConfigured()) "API key is stored using Android Keystore encryption." else "Add an API key to discover models.",
            14f,
            false
        ).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(10), 0, dp(10))
        }
        screen.addView(status)

        fun saveKeyIfEntered() {
            val key = keyInput.text.toString()
            if (key.isNotBlank()) {
                secretStore.store(key)
                keyInput.text.clear()
                keyInput.hint = "Saved key protected; enter to replace"
            }
        }

        val fetchModels = {
            try {
                saveKeyIfEntered()
                status.text = "Fetching available models…"
                status.setTextColor(Color.LTGRAY)
                installerExecutor.execute {
                    try {
                        val fetched = provider.listModels()
                        mainHandler.post {
                            availableModels = fetched
                            val stillAvailable = fetched.any { it.id == selectedModel[0] }
                            fetched.firstOrNull { it.id == selectedModel[0] }?.let {
                                modelLabel.text = "Selected model: ${it.label}\n${it.id}"
                            }
                            status.text = when {
                                fetched.isEmpty() -> "No models are currently available."
                                selectedModel[0].isNotBlank() && !stillAvailable ->
                                    "Models loaded, but the saved model is unavailable. Select a current model; none was substituted."
                                else -> "Loaded ${fetched.size} models from Token Harbor."
                            }
                            status.setTextColor(if (fetched.isEmpty()) Color.YELLOW else Color.LTGRAY)
                        }
                    } catch (error: Exception) {
                        mainHandler.post {
                            status.text = error.message ?: "Could not fetch models."
                            status.setTextColor(Color.rgb(248, 113, 113))
                        }
                    }
                }
            } catch (error: Exception) {
                status.text = error.message ?: "Could not save credentials."
                status.setTextColor(Color.rgb(248, 113, 113))
            }
        }
        screen.addView(actionButton("Fetch / refresh models") { fetchModels() })
        screen.addView(actionButton("Choose model") {
            if (availableModels.isEmpty()) {
                status.text = "Fetch the current model list first."
                return@actionButton
            }
            AlertDialog.Builder(this)
                .setTitle("Choose a model")
                .setItems(availableModels.map { it.label }.toTypedArray()) { _, index ->
                    selectedModel[0] = availableModels[index].id
                    modelLabel.text = "Selected model: ${availableModels[index].label}\n${selectedModel[0]}"
                    preferences.edit().putString(PREF_MODEL_ID, selectedModel[0]).apply()
                    status.text = "Model selection saved."
                }
                .show()
        })
        screen.addView(actionButton("Save settings") {
            try {
                saveKeyIfEntered()
                if (selectedModel[0].isNotBlank()) {
                    preferences.edit().putString(PREF_MODEL_ID, selectedModel[0]).apply()
                }
                status.text = if (secretStore.isConfigured()) {
                    "Settings saved. Refresh models to validate the key."
                } else {
                    "No API key is configured."
                }
                status.setTextColor(Color.LTGRAY)
            } catch (error: Exception) {
                status.text = error.message ?: "Could not save settings."
                status.setTextColor(Color.rgb(248, 113, 113))
            }
        })
        val settingsScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(screen)
        }
        setScreen(settingsScroll)
    }

    private fun showEditor(app: LocalApp) {
        selectedApp = app
        currentScreen = "editor"
        cancellation?.cancel()
        cancellation = null
        val screen = column()
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(actionButton("Back") { showDashboard() })
        toolbar.addView(label(app.name, 18f, true), LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(actionButton("Clear") {
            if (agentTask?.isDone == false) {
                currentStatus?.text = "Stop generation before clearing the conversation."
                return@actionButton
            }
            AlertDialog.Builder(this)
                .setTitle("Clear conversation?")
                .setMessage("This removes this app's stored agent history only. App files will not be changed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    try {
                        agentController.clearConversation(app)
                        refreshConversation()
                    } catch (error: Exception) {
                        showError("Could not clear conversation", error.message ?: "Please try again.")
                    }
                }
                .show()
        })
        screen.addView(toolbar)

        val messageScroll = ScrollView(this)
        currentMessageScroll = messageScroll
        currentMessages = column()
        messageScroll.addView(currentMessages)
        screen.addView(messageScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        currentStatus = label("Ready", 13f, false).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(6), 0, dp(6))
        }
        screen.addView(currentStatus)
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val requestInput = EditText(this).apply {
            hint = "Describe what to build…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = 4
        }
        inputRow.addView(requestInput, LinearLayout.LayoutParams(0, -2, 1f))
        val send = actionButton("Send") { sendAgentRequest(app, requestInput) }
        inputRow.addView(send)
        screen.addView(inputRow)
        screen.addView(actionButton("Stop generation") {
            cancellation?.cancel()
            currentStatus?.text = "Stopping…"
        })
        setScreen(screen)
        refreshConversation()
    }

    private fun sendAgentRequest(app: LocalApp, input: EditText) {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (agentTask?.isDone == false) {
            currentStatus?.text = "An app-generation request is already running."
            return
        }
        val model = preferences.getString(PREF_MODEL_ID, "").orEmpty()
        if (model.isBlank()) {
            currentStatus?.text = "Choose a model in Settings before generating an app."
            return
        }
        if (!secretStore.isConfigured()) {
            currentStatus?.text = "Add a Token Harbor API key in Settings before generating an app."
            return
        }

        val appDirectory = registry.directoryFor(app)
        val workspace = WorkspaceTools(
            app = app,
            appDirectory = appDirectory,
            runtimeRoot = runtimeRoot,
            installPackage = { name, version ->
                installPackage(name, version, File(runtimeRoot, "packages"))
            },
            testApp = { testApp(app) },
            getErrors = { getAppError(app.id) }
        )
        val taskCancellation = AgentCancellation()
        cancellation = taskCancellation
        input.text.clear()
        currentStatus?.text = "Waiting…"
        currentMessages?.addView(messageBubble("You", text, true))
        agentTask = installerExecutor.submit {
            try {
                val response = agentController.send(
                    app = app,
                    model = model,
                    userText = text,
                    tools = workspace,
                    cancellation = taskCancellation,
                    onStatus = { status ->
                        mainHandler.post {
                            if (currentScreen == "editor" && selectedApp?.id == app.id) {
                                currentStatus?.text = status
                            }
                        }
                    },
                    onConversationUpdate = { history ->
                        mainHandler.post {
                            if (currentScreen == "editor" && selectedApp?.id == app.id) {
                                renderConversation(history)
                            }
                        }
                    }
                )
                mainHandler.post {
                    if (currentScreen == "editor" && selectedApp?.id == app.id) {
                        refreshConversation()
                        currentStatus?.text = if (response.isNotBlank()) "Complete" else "Finished."
                    }
                }
            } catch (error: InterruptedException) {
                mainHandler.post {
                    if (currentScreen == "editor" && selectedApp?.id == app.id) {
                        currentStatus?.text = "Generation stopped. The saved conversation is available to continue."
                        refreshConversation()
                    }
                }
            } catch (error: Exception) {
                mainHandler.post {
                    if (currentScreen == "editor" && selectedApp?.id == app.id) {
                        currentStatus?.text = "Error: ${error.message ?: "Generation failed."}"
                        refreshConversation()
                    }
                }
            }
        }
    }

    private fun refreshConversation() {
        val app = selectedApp ?: return
        try {
            renderConversation(agentController.loadConversation(app))
        } catch (error: Exception) {
            currentStatus?.text = "Could not load conversation: ${error.message ?: "stored history is invalid."}"
        }
    }

    private fun renderConversation(history: org.json.JSONArray) {
        val target = currentMessages ?: return
        target.removeAllViews()
        val toolNames = mutableMapOf<String, String>()
        for (index in 0 until history.length()) {
            val message = history.getJSONObject(index)
            when (message.optString("role")) {
                "user" -> target.addView(messageBubble("You", message.optString("content"), true))
                "assistant" -> {
                    val content = message.optString("content")
                    if (content.isNotBlank()) target.addView(messageBubble("Pocket Runtime", content, false))
                    val calls = message.optJSONArray("tool_calls")
                    if (calls != null) {
                        for (callIndex in 0 until calls.length()) {
                            val call = calls.optJSONObject(callIndex) ?: continue
                            val id = call.optString("id")
                            val name = call.optJSONObject("function")?.optString("name").orEmpty()
                            if (id.isNotBlank()) toolNames[id] = name
                            if (name.isNotBlank()) target.addView(messageBubble("Running tool", name, false))
                        }
                    }
                }
                "tool" -> {
                    val name = toolNames[message.optString("tool_call_id")].orEmpty()
                    target.addView(messageBubble(
                        if (name.isBlank()) "Tool result" else "$name result",
                        summarizeToolResult(message.optString("content")),
                        false
                    ))
                }
            }
        }
        currentMessageScroll?.post { currentMessageScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun summarizeToolResult(content: String): String {
        val result = try {
            JSONObject(content)
        } catch (_: Exception) {
            return content.take(2_000)
        }
        if (result.has("error") && !result.isNull("error")) return "Failed: ${result.optString("error")}"
        when {
            result.has("written") -> return "Updated ${result.optString("written")}."
            result.has("deleted") -> return "Deleted ${result.optString("deleted")}."
            result.has("installed") -> return "Installed ${result.optString("installed")}."
            result.has("result") -> return result.optString("result").take(2_000)
            result.has("path") && result.has("content") ->
                return "Read ${result.optString("path")}:\n${result.optString("content").take(1_600)}"
            result.has("files") -> {
                val files = result.optJSONArray("files")
                return "${files?.length() ?: 0} files/directories listed."
            }
            result.has("matches") -> {
                val matches = result.optJSONArray("matches")
                return "${matches?.length() ?: 0} search matches found."
            }
            result.has("error") -> return "No runtime errors reported."
            else -> return result.toString().take(2_000)
        }
    }

    private fun showStatusScreen(title: String, message: String) {
        currentScreen = "status"
        val screen = column()
        screen.addView(header(title) { showDashboard() })
        screen.addView(label(message, 16f, false).apply {
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(16), 0, dp(12))
        })
        screen.addView(actionButton("Back to apps") { showDashboard() })
        setScreen(screen)
    }

    private fun testApp(app: LocalApp): String {
        val appDirectory = registry.directoryFor(app)
        val entry = File(appDirectory, "index.html")
        require(entry.isFile) { "This app is not runnable yet: index.html is missing." }
        try {
            installDependencies(appDirectory)
        } catch (error: Exception) {
            recordAppError(app.id, error.message ?: "Application preparation failed.")
            throw error
        }
        clearAppError(app.id)
        return "Prepared ${app.name}; index.html is present and declared exact dependencies are installed."
    }

    private fun recordAppError(appId: String, message: String) {
        appErrors[appId] = message
        try {
            appErrorStore.record(appId, message)
        } catch (error: Exception) {
            Log.e("PocketRuntime", "Could not persist an app error record.", error)
        }
    }

    private fun getAppError(appId: String): String? =
        appErrors[appId] ?: appErrorStore.get(appId)

    private fun clearAppError(appId: String) {
        appErrors.remove(appId)
        try {
            appErrorStore.clear(appId)
        } catch (error: Exception) {
            Log.e("PocketRuntime", "Could not clear an app error record.", error)
        }
    }

    private fun modelDisplay(id: String): String =
        if (id.isBlank()) "No model selected" else "Selected model: $id"

    private fun setScreen(view: View) {
        rootView.removeAllViews()
        rootView.addView(view, FrameLayout.LayoutParams(-1, -1))
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(14))
        setBackgroundColor(Color.rgb(17, 24, 39))
    }

    private fun header(title: String, onBack: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(actionButton("Back", onBack))
            addView(label(title, 20f, true), LinearLayout.LayoutParams(0, -2, 1f))
        }

    private fun label(text: String, size: Float, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun messageBubble(sender: String, text: String, isUser: Boolean): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(if (isUser) Color.rgb(30, 64, 110) else Color.rgb(31, 41, 55))
            addView(label(sender, 12f, true).apply { setTextColor(Color.rgb(125, 211, 252)) })
            addView(label(text, 15f, false).apply {
                setPadding(0, dp(4), 0, 0)
                setTextIsSelectable(true)
            })
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(8)
                if (isUser) marginStart = dp(28) else marginEnd = dp(28)
            }
        }

    private fun actionButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { onClick() }
            minHeight = dp(44)
        }

    private fun showError(title: String, message: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun writeFile(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun installDependencies(applicationRoot: File) {
        val manifestFile = File(applicationRoot, "packages.json")
        if (!manifestFile.isFile) return
        val manifest = JSONObject(manifestFile.readText())
        val dependencies = manifest.optJSONObject("dependencies") ?: JSONObject()
        val packageRoot = File(runtimeRoot, "packages")
        require(packageRoot.mkdirs() || packageRoot.isDirectory) { "Could not create package storage." }

        val names = dependencies.keys()
        while (names.hasNext()) {
            val name = names.next()
            val version = dependencies.optString(name)
            require(name.matches(Regex("[a-z0-9][a-z0-9._-]*"))) {
                "Only unscoped npm package names are supported: $name"
            }
            require(version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) {
                "Use an exact package version for $name"
            }
            installPackage(name, version, packageRoot)
        }
    }

    private fun installPackage(name: String, version: String, packageRoot: File) {
        val versionDirectory = File(File(packageRoot, name), version)
        if (isValidInstalledPackage(versionDirectory, name, version)) return

        val metadataUrl = URL("https://registry.npmjs.org/$name/$version")
        val metadata = JSONObject(readUrl(metadataUrl, 2 * 1024 * 1024).toString(Charsets.UTF_8))
        require(metadata.optString("name") == name && metadata.optString("version") == version) {
            "npm registry returned unexpected metadata for $name@$version"
        }

        val distribution = metadata.optJSONObject("dist")
            ?: throw IllegalStateException("npm metadata has no distribution record")
        val tarballUrl = URL(distribution.optString("tarball"))
        require(tarballUrl.protocol == "https" && tarballUrl.host == "registry.npmjs.org") {
            "Refusing untrusted npm tarball host: ${tarballUrl.host}"
        }
        val archive = readUrl(tarballUrl, MAX_PACKAGE_ARCHIVE_BYTES)
        verifyPackageIntegrity(archive, distribution)

        val stage = File(
            versionDirectory.parentFile,
            ".${version}.install-${System.nanoTime()}"
        )
        try {
            require(stage.mkdirs()) { "Could not create package staging directory" }
            extractPackageArchive(archive, stage)
            require(isValidInstalledPackage(stage, name, version)) {
                "Downloaded package is missing its package metadata or browser distribution"
            }
            if (versionDirectory.exists()) {
                throw IllegalStateException("Invalid cached package already exists at $versionDirectory")
            }
            Files.move(stage.toPath(), versionDirectory.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private fun isValidInstalledPackage(directory: File, name: String, version: String): Boolean {
        val metadataFile = File(directory, "package.json")
        if (!metadataFile.isFile || metadataFile.length() > 2 * 1024 * 1024) return false
        return try {
            val metadata = JSONObject(metadataFile.readText())
            metadata.optString("name") == name && metadata.optString("version") == version
        } catch (_: Exception) {
            false
        }
    }

    private fun readUrl(url: URL, maxBytes: Int): ByteArray {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "PocketRuntime/1.0")
        try {
            val responseCode = connection.responseCode
            require(responseCode in 200..299) {
                "Download failed (${connection.responseCode}) for ${url.host}"
            }
            val contentLength = connection.contentLengthLong
            require(contentLength < 0 || contentLength <= maxBytes) {
                "Download exceeds the allowed size"
            }
            return connection.inputStream.use { input -> readBounded(input, maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Download exceeds the allowed size" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun verifyPackageIntegrity(archive: ByteArray, distribution: JSONObject) {
        val integrity = distribution.optString("integrity")
        if (integrity.isNotBlank()) {
            val supported = integrity.split(' ').mapNotNull { item ->
                val separator = item.indexOf('-')
                if (separator <= 0) return@mapNotNull null
                val algorithm = item.substring(0, separator)
                val hash = item.substring(separator + 1).substringBefore('?')
                val jcaAlgorithm = when (algorithm) {
                    "sha512" -> "SHA-512"
                    "sha384" -> "SHA-384"
                    "sha256" -> "SHA-256"
                    "sha1" -> "SHA-1"
                    else -> return@mapNotNull null
                }
                Triple(algorithm, hash, jcaAlgorithm)
            }.sortedByDescending { it.first }

            require(supported.isNotEmpty()) { "npm package uses an unsupported integrity algorithm" }
            val (_, expected, algorithm) = supported.first()
            val actual = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance(algorithm).digest(archive)
            )
            val verified = actual == expected
            require(verified) { "npm package integrity check failed" }
            return
        }

        val expectedSha1 = distribution.optString("shasum")
        require(expectedSha1.matches(Regex("[0-9a-fA-F]{40}"))) {
            "npm metadata does not provide a supported package integrity hash"
        }
        val actualSha1 = MessageDigest.getInstance("SHA-1").digest(archive)
            .joinToString("") { "%02x".format(it) }
        require(actualSha1.equals(expectedSha1, ignoreCase = true)) {
            "npm package integrity check failed"
        }
    }

    private fun extractPackageArchive(archive: ByteArray, destination: File) {
        GZIPInputStream(ByteArrayInputStream(archive)).use { tar ->
            val destinationPath = destination.canonicalFile.toPath()
            var fileCount = 0
            var totalBytes = 0L

            while (true) {
                val header = ByteArray(TAR_BLOCK_SIZE)
                val firstByte = tar.read()
                if (firstByte < 0) break
                header[0] = firstByte.toByte()
                readFully(tar, header, 1, TAR_BLOCK_SIZE - 1)
                if (header.all { it == 0.toByte() }) break

                fileCount++
                require(fileCount <= MAX_PACKAGE_FILES) { "Package contains too many files" }
                val rawName = tarString(header, 0, 100)
                val prefix = tarString(header, 345, 155)
                val archivePath = if (prefix.isEmpty()) rawName else "$prefix/$rawName"
                require(archivePath.startsWith("package/")) {
                    "Unexpected path in npm package archive"
                }
                val components = archivePath.split('/')
                require(components.none { it == ".." || it == "." || it.contains('\\') || it.contains(':') }) {
                    "Unsafe path in npm package archive"
                }
                val relativePath = components.drop(1).filter { it.isNotEmpty() }
                    .joinToString(File.separator)
                if (relativePath.isEmpty()) {
                    skipTarEntry(tar, tarOctal(header, 124, 12))
                    continue
                }

                val entryPath = destinationPath.resolve(relativePath).normalize()
                require(entryPath.startsWith(destinationPath)) {
                    "Package archive path escapes its install directory"
                }
                val entry = entryPath.toFile()
                val size = tarOctal(header, 124, 12)
                val type = header[156]
                when (type) {
                    0.toByte(), '0'.code.toByte() -> {
                        totalBytes += size
                        require(totalBytes <= MAX_UNPACKED_PACKAGE_BYTES) {
                            "Package expands beyond the allowed size"
                        }
                        entry.parentFile?.mkdirs()
                        FileOutputStream(entry).use { output ->
                            copyTarEntry(tar, output, size)
                        }
                    }
                    '5'.code.toByte() -> {
                        require(size == 0L) { "Invalid directory in package archive" }
                        require(entry.mkdirs() || entry.isDirectory) {
                            "Could not create package directory"
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported entry in npm package archive")
                }
                skipTarPadding(tar, size)
            }
        }
    }

    private fun readFully(input: InputStream, destination: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val count = input.read(destination, offset + read, length - read)
            require(count >= 0) { "Truncated npm package archive" }
            read += count
        }
    }

    private fun tarString(header: ByteArray, offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { header[it] == 0.toByte() }
            ?: offset + length
        return header.copyOfRange(offset, end).toString(Charsets.UTF_8)
    }

    private fun tarOctal(header: ByteArray, offset: Int, length: Int): Long {
        val value = tarString(header, offset, length).trim().trim('\u0000')
        if (value.isEmpty()) return 0L
        require(value.all { it in '0'..'7' }) { "Invalid size in npm package archive" }
        return value.toLongOrNull(8) ?: throw IllegalArgumentException("Invalid package archive size")
    }

    private fun copyTarEntry(input: InputStream, output: FileOutputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count > 0) { "Truncated npm package file" }
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    private fun skipTarEntry(input: InputStream, size: Long) {
        var remaining = size
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count > 0) { "Truncated npm package file" }
            remaining -= count
        }
        skipTarPadding(input, size)
    }

    private fun skipTarPadding(input: InputStream, size: Long) {
        var remaining = (TAR_BLOCK_SIZE - (size % TAR_BLOCK_SIZE)) % TAR_BLOCK_SIZE
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                require(input.read() >= 0) { "Truncated npm package archive" }
                remaining--
            }
        }
    }

    private fun packagesManifest(): String = """
        {
          "dependencies": {
            "comfy.js": "1.1.29"
          }
        }
    """.trimIndent()

    private fun appIndexHtml(): String = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8" />
                <meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>Pocket Runtime</title>
                <link rel="stylesheet" href="style.css" />
            </head>
            <body>
                <div id="app"></div>
                <script type="module" src="app.js"></script>
            </body>
            </html>
        """.trimIndent()

    private fun appStyles(): String = """
        :root {
          --bg: #0f172a;
          --panel: #111827;
          --panel-soft: #1f2937;
          --accent: #3b82f6;
          --accent-strong: #2563eb;
          --text: #f8fafc;
          --muted: #cbd5e1;
          --success: #34d399;
          --danger: #f87171;
        }

        * { box-sizing: border-box; }
        html, body {
          margin: 0;
          min-height: 100%;
          background: var(--bg);
          color: var(--text);
          font-family: Arial, sans-serif;
        }

        body {
          display: flex;
          align-items: center;
          justify-content: center;
          min-height: 100vh;
          background: linear-gradient(180deg, #020817 0%, #0f172a 100%);
        }

        #app {
          width: min(100vw, 420px);
          min-height: 100vh;
          background: rgba(15, 23, 42, 0.9);
          padding: 24px 18px 32px;
        }

        .panel {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          min-height: 100vh;
        }

        .logo {
          width: 84px;
          height: 84px;
          display: grid;
          place-items: center;
          border-radius: 50%;
          background: rgba(59, 130, 246, 0.14);
          border: 2px solid rgba(96, 165, 250, 0.45);
          color: var(--text);
          font-size: 42px;
          margin-bottom: 24px;
        }

        h1 {
          margin: 0;
          font-size: 28px;
          text-align: center;
          color: var(--text);
        }

        h2 {
          margin: 0 0 18px;
          text-align: center;
          color: var(--text);
        }

        .field {
          width: 100%;
          margin-top: 18px;
        }

        input {
          width: 100%;
          height: 52px;
          border-radius: 12px;
          border: 1px solid rgba(148, 163, 184, 0.35);
          background: rgba(15, 23, 42, 0.8);
          color: var(--text);
          font-size: 18px;
          padding: 0 14px;
          outline: none;
        }

        button {
          width: 100%;
          margin-top: 18px;
          background: var(--accent-strong);
          color: white;
          border: none;
          border-radius: 12px;
          height: 54px;
          font-size: 18px;
          font-weight: 700;
          cursor: pointer;
        }

        button.secondary {
          background: rgba(148, 163, 184, 0.2);
          border: 1px solid rgba(148, 163, 184, 0.2);
        }

        .status {
          width: 100%;
          margin-top: 10px;
          color: var(--muted);
          font-size: 14px;
          text-align: center;
        }

        .chat-shell {
          display: flex;
          flex-direction: column;
          height: 100vh;
          padding-top: 12px;
        }

        .chat-header {
          display: flex;
          align-items: center;
          gap: 12px;
          padding: 8px 0 14px;
          border-bottom: 1px solid rgba(148, 163, 184, 0.2);
        }

        .back-button {
          width: auto;
          min-width: 44px;
          height: 40px;
          margin: 0;
          background: transparent;
          border: 1px solid rgba(148, 163, 184, 0.25);
          font-size: 24px;
          padding: 0 12px;
        }

        .chat-input {
          flex: 1;
          margin: 0;
        }

        .message-list {
          flex: 1;
          overflow: auto;
          padding-top: 18px;
        }

        .message {
          margin-bottom: 14px;
          display: flex;
          flex-direction: column;
          gap: 4px;
        }

        .message .user {
          font-size: 12px;
          font-weight: 700;
          color: #7dd3fc;
        }

        .message .text {
          font-size: 15px;
          color: var(--text);
          line-height: 1.45;
        }

        .empty-state {
          color: var(--muted);
          text-align: center;
          padding-top: 24px;
        }
    """.trimIndent()

    private fun appScript(): String = """
        const app = document.getElementById('app');
        const state = {
          view: 'menu',
          channel: 'lirik',
          status: 'loading',
          messages: [],
          connection: null,
        };

        function normalizeChannel(value) {
          const channel = String(value || '').trim().replace(/^#+/, '').toLowerCase();
          return /^[a-z0-9_]{1,25}$/.test(channel) ? channel : '';
        }

        function escapeHtml(value) {
          return String(value).replace(/[&<>"']/g, (character) => ({
            '&': '&amp;',
            '<': '&lt;',
            '>': '&gt;',
            '"': '&quot;',
            "'": '&#39;',
          }[character]));
        }

        function renderMenu() {
          app.innerHTML = `
            <div class="panel">
              <div class="logo">◉</div>
              <h1>Twitch Chat</h1>
              <div class="field">
                <input id="channel-input" value="${'$'}{state.channel}" placeholder="Enter channel name" />
              </div>
              <button id="open-chat">Open Chat</button>
              <div class="status">${'$'}{escapeHtml(state.status)}</div>
            </div>
          `;

          const input = document.getElementById('channel-input');
          const button = document.getElementById('open-chat');
          button.addEventListener('click', () => {
            const nextChannel = normalizeChannel(input.value);
            if (!nextChannel) {
              state.status = 'Please enter a channel name';
              renderMenu();
              return;
            }
            state.channel = nextChannel;
            openChat();
          });

          input.addEventListener('keydown', (event) => {
            if (event.key === 'Enter') {
              button.click();
            }
          });
        }

        function renderChat() {
          const previousList = document.getElementById('message-list');
          const wasAtBottom = !previousList ||
            previousList.scrollHeight - previousList.scrollTop - previousList.clientHeight < 48;
          const previousScrollTop = previousList ? previousList.scrollTop : 0;
          const messagesHtml = state.messages.length
            ? state.messages.map((message) => `
                <div class="message">
                  <span class="user">${'$'}{escapeHtml(message.user)}</span>
                  <span class="text">${'$'}{escapeHtml(message.message)}</span>
                </div>
              `).join('')
            : '<div class="empty-state">Waiting for messages…</div>';

          app.innerHTML = `
            <div class="chat-shell">
              <div class="chat-header">
                <button class="back-button" id="back-button">←</button>
                <input id="channel-switch" class="chat-input" value="${'$'}{state.channel}" aria-label="Channel" />
              </div>
              <div class="message-list" id="message-list">
                ${'$'}{messagesHtml}
              </div>
              <div class="status">${'$'}{escapeHtml(state.status)}</div>
            </div>
          `;

          const input = document.getElementById('channel-switch');
          input.addEventListener('keydown', (event) => {
            if (event.key === 'Enter') {
              const channel = normalizeChannel(input.value);
              if (!channel) {
                state.status = 'Channel name cannot be empty';
                renderChat();
                return;
              }
              state.channel = channel;
              connectChannel(channel);
            }
          });

          const backButton = document.getElementById('back-button');
          backButton.addEventListener('click', () => {
            if (state.connection && window.ComfyJS) {
              state.connection = null;
              window.ComfyJS.Disconnect();
            }
            state.view = 'menu';
            state.status = 'Ready';
            renderMenu();
          });

          const list = document.getElementById('message-list');
          list.scrollTop = wasAtBottom ? list.scrollHeight : previousScrollTop;
        }

        async function initializeRuntime() {
          try {
            const manifestResponse = await fetch(new URL('packages.json', location.href));
            if (!manifestResponse.ok) {
              throw new Error('Could not read app/packages.json');
            }
            const manifest = await manifestResponse.json();
            const dependencies = manifest.dependencies || {};
            const entries = Object.entries(dependencies);

            for (const [dependencyName, dependencyRange] of entries) {
              const normalizedName = dependencyName.toLowerCase();
              const version = String(dependencyRange).replace(/^[^0-9]*/, '');
              const packageUrl = location.origin + '/packages/' + normalizedName + '/' + version + '/dist/comfy.min.js';
              await import(packageUrl);
            }

            if (!window.ComfyJS || typeof window.ComfyJS.Init !== 'function') {
              throw new Error('ComfyJS package did not register itself');
            }

            state.status = 'ready';
            state.view = 'menu';
            renderMenu();
          } catch (error) {
            console.error(error);
            state.status = 'dependency error';
            app.innerHTML = `<div class="panel"><div class="logo">!</div><h2>Runtime error</h2><div class="status">${'$'}{escapeHtml(error.message)}</div></div>`;
          }
        }

        function connectChannel(channelName) {
          if (state.connection) {
            window.ComfyJS.Disconnect();
          }

          const comfyJS = window.ComfyJS;
          if (!comfyJS) {
            state.status = 'ComfyJS did not load. Check your internet connection and retry.';
            renderChat();
            return;
          }

          state.messages = [];
          state.connection = true;
          state.status = `Connecting to #${'$'}{channelName}...`;
          comfyJS.onConnected = () => {
            if (!state.connection) return;
            state.status = `Connected to #${'$'}{channelName}`;
            renderChat();
          };
          comfyJS.onReconnect = () => {
            if (!state.connection) return;
            state.status = `Reconnecting to #${'$'}{channelName}...`;
            renderChat();
          };
          comfyJS.onError = (error) => {
            if (!state.connection) return;
            state.status = `Connection error: ${'$'}{error && error.message ? error.message : error}`;
            renderChat();
          };
          comfyJS.onChat = (user, message, flags, self, extra) => {
            if (self || !state.connection) return;
            state.messages.push({
              user: (extra && extra.displayName) || user || 'viewer',
              message: message || '',
            });
            if (state.messages.length > 300) {
              state.messages = state.messages.slice(state.messages.length - 300);
            }
            renderChat();
          };

          try {
            comfyJS.Init(channelName);
          } catch (error) {
            state.connection = null;
            state.status = `Connection error: ${'$'}{error.message || error}`;
          }
          renderChat();
        }

        function openChat() {
          state.view = 'chat';
          connectChannel(state.channel);
          renderChat();
        }

        initializeRuntime();
    """.trimIndent()

}
