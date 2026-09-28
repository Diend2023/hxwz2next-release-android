package com.hxwz2next.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 幻想纹章新篇 安卓壳主界面。
 * 启动 → 更新界面（初始化不可跳过；热更新可跳过）→ 完成进入 WebView（WebViewAssetLoader 加载本地资源）。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MAIN_URL = "https://appassets.androidplatform.net/web/index.html"
    }

    private lateinit var updatePanel: View
    private lateinit var webContainer: FrameLayout
    private lateinit var webView: WebView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnRetry: Button
    private lateinit var btnSkip: Button

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var updateManager: UpdateManager
    private var updateJob: Job? = null
    private var enteringWeb = false
    private var webFallbackTried = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterFullscreen()
        setContentView(R.layout.activity_main)

        updatePanel = findViewById(R.id.updatePanel)
        webContainer = findViewById(R.id.webContainer)
        webView = findViewById(R.id.webView)
        tvStatus = findViewById(R.id.tvStatus)
        progressBar = findViewById(R.id.progressBar)
        btnRetry = findViewById(R.id.btnRetry)
        btnSkip = findViewById(R.id.btnSkip)

        btnRetry.setOnClickListener { retryUpdate() }
        btnSkip.setOnClickListener { skipUpdate() }

        updateManager = UpdateManager(this)
        startUpdate()
    }

    /** 横屏 + 沉浸式全屏（隐藏系统栏，滑动短暂浮现）。 */
    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 触摸时确保 WebView 持有 view 焦点。
     * 否则游戏 JS 对输入框的 focus() 触发的 showSoftInput 会因
     * "view is not served"（PHASE_CLIENT_VIEW_SERVED 失败）被系统拒绝，软键盘无法唤起。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (enteringWeb && ::webView.isInitialized && !webView.hasFocus()) {
            webView.requestFocus(View.FOCUS_DOWN)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun startUpdate() {
        updateJob = scope.launch {
            updateManager.run(::onUpdateState)
        }
    }

    private fun retryUpdate() {
        btnRetry.visibility = View.GONE
        progressBar.visibility = View.VISIBLE
        startUpdate()
    }

    private fun skipUpdate() {
        updateJob?.cancel()
        finishUpdateToWeb()
    }

    private fun onUpdateState(state: UpdateState) {
        when (state.phase) {
            UpdateState.Phase.INIT -> {
                // 初始化（基线复制 + 解压 app.zip）不可跳过
                btnSkip.visibility = View.GONE
                tvStatus.text = state.message.ifEmpty { "初始化本地资源…" }
                progressBar.isIndeterminate = false
                if (state.total > 0) {
                    progressBar.max = state.total
                    progressBar.progress = state.current
                }
            }
            UpdateState.Phase.CHECKING -> {
                // 热更新阶段可跳过
                btnSkip.visibility = View.VISIBLE
                tvStatus.text = "检查更新…"
                progressBar.isIndeterminate = true
            }
            UpdateState.Phase.DOWNLOADING -> {
                btnSkip.visibility = View.VISIBLE
                tvStatus.text = state.message
                progressBar.isIndeterminate = false
                progressBar.max = state.total
                progressBar.progress = state.current
            }
            UpdateState.Phase.DONE -> {
                btnSkip.visibility = View.GONE
                if (!enteringWeb) finishUpdateToWeb()
            }
            UpdateState.Phase.FAILED -> {
                // 失败后可跳过进入游戏或重试
                btnSkip.visibility = View.VISIBLE
                tvStatus.text = "更新失败：${state.message}"
                progressBar.isIndeterminate = false
                btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun finishUpdateToWeb() {
        if (enteringWeb) return
        enteringWeb = true

        tvStatus.text = "正在进入游戏…"
        progressBar.visibility = View.GONE
        btnRetry.visibility = View.GONE
        btnSkip.visibility = View.GONE

        webContainer.visibility = View.VISIBLE
        updatePanel.visibility = View.GONE
        setupWebView()
        // 初始焦点交给 WebView，保证游戏内输入框 focus() 可直接唤起 IME
        webView.requestFocus(View.FOCUS_DOWN)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        // file:// 兜底路径需要文件访问；虚拟域主路径不受影响
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = false
        @Suppress("DEPRECATION")
        webSettings.setAllowFileAccessFromFileURLs(true)

        // debug 构建开启 WebView 远程调试（chrome://inspect），便于排查游戏 JS 问题
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        // 通过 WebViewAssetLoader 以 https 虚拟域加载外部私有目录，避免 file:// 跨域限制。
        // 注意：InternalStoragePathHandler 仅支持 filesDir 下的目录，外部私有目录需自定义 PathHandler
        val webDir = UpdateManager.resolveWebDir(this)
        val assetLoader = try {
            WebViewAssetLoader.Builder()
                .addPathHandler("/web/", object : WebViewAssetLoader.PathHandler {
                    override fun handle(path: String): WebResourceResponse? {
                        return readWebFile(webDir, path)
                    }
                })
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "WebViewAssetLoader 不可用", e)
            null
        }

        // 游戏 JS console 桥接到 logcat（诊断黑屏/JS 异常的关键）
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    "WebViewJS",
                    "[${consoleMessage.messageLevel()}] ${consoleMessage.message()} " +
                        "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                )
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                // 仅拦截虚拟域；其余请求（网络）放行
                if (url.scheme == "https" && url.host == "appassets.androidplatform.net") {
                    val resp = assetLoader?.shouldInterceptRequest(url)
                    if (resp != null) {
                        Log.d(TAG, "assetLoader 拦截: $url")
                        return resp
                    }
                    Log.w(TAG, "assetLoader 未拦截: $url")
                }
                return null
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError
            ) {
                Log.e(TAG, "onReceivedError: code=${error.errorCode} desc=${error.description} url=${request?.url}")
                // 仅主框架加载失败时降级到 file://；子资源 404（如个别音效缺失）不触发页面重载
                if (request != null && request.isForMainFrame && !webFallbackTried) {
                    webFallbackTried = true
                    val fallback = "file://${File(UpdateManager.resolveWebDir(this@MainActivity), "index.html").absolutePath}"
                    Log.w(TAG, "降级加载: $fallback")
                    view.loadUrl(fallback)
                }
            }
        }

        webView.loadUrl(MAIN_URL)
    }

    /**
     * 从游戏资源根目录读取文件并构造 WebResourceResponse。
     * 路径做规范化校验，防止 ../ 穿越目录。
     */
    private fun readWebFile(webDir: File, path: String): WebResourceResponse? {
        try {
            val canonicalRoot = webDir.canonicalPath
            val file = File(webDir, path).canonicalFile
            if (!file.path.startsWith(canonicalRoot + File.separator)) {
                Log.w(TAG, "非法路径: $path")
                return null
            }
            if (!file.isFile) return null
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.extension.lowercase())
                ?: "application/octet-stream"
            return WebResourceResponse(mime, null, FileInputStream(file))
        } catch (e: Exception) {
            Log.w(TAG, "读取文件失败: $path", e)
            return null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webView.destroy()
    }
}
