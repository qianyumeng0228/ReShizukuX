package io.reshizukux.modules.webui

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleState
import io.reshizukux.modules.permission.PermissionController
import java.io.File

/**
 * 模块 WebUI 宿主 Activity（设计方案 §3.6，参考 Shevery ModuleWebViewActivity.kt）。
 *
 * 独立 Activity 承载 WebView（不嵌入 Compose，避免 WebView/Compose 互操作坑）。
 * 通过 Intent extra [EXTRA_MODULE_ID] 指定要打开的模块。
 *
 * 安全设置（设计方案 §3.6.2）：
 *  - javaScriptEnabled=true、domStorageEnabled=true
 *  - allowContentAccess=false、allowFileAccess=false、allowFileAccessFromFileURLs=true
 *  - mixedContentMode=MIXED_CONTENT_NEVER_ALLOW
 *  - blockNetworkLoads=true（WebView 层禁止联网；canWebNetwork 恒 false）
 *  - 第三方 Cookie 关闭
 *  - WebViewClient 同时拦截 shouldOverrideUrlLoading / shouldInterceptRequest：
 *    仅允许 file:// 且 canonical 路径在模块 webRoot 内；http/https 在 WebView 内直接拒绝
 *    （download() 走 app 进程 HttpsURLConnection，不依赖 WebView 联网）。
 *
 * 挂桥条件：模块 ENABLED 且 [PermissionController.canWebBridge] 通过。
 */
class ModuleWebViewActivity : AppCompatActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val moduleId = intent.getStringExtra(EXTRA_MODULE_ID).orEmpty()
        if (moduleId.isEmpty()) {
            showError("Missing module id.")
            return
        }
        val info = ModuleManager.getModule(moduleId)
        if (info == null) {
            showError("Module not installed: $moduleId")
            return
        }
        val moduleDir = ModuleManager.getModuleDir(moduleId)
        val webRoot = File(moduleDir, "webroot")
        val index = File(webRoot, "index.html")
        if (!index.isFile) {
            showError("Module has no WebUI (webroot/index.html missing): ${info.name}")
            return
        }

        title = info.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            // 任务要求：allowFileAccess=false 但 allowFileAccessFromFileURLs=true
            // —— file:// 页面可同目录互相访问子资源，但不能通过 WebView 读任意路径文件。
            settings.allowFileAccess = false
            settings.allowFileAccessFromFileURLs = true
            settings.allowUniversalAccessFromFileURLs = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // WebView 层禁止联网（canWebNetwork 恒 false；download() 走 app 进程）
            settings.blockNetworkLoads = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

            webViewClient = ModuleWebViewClient(webRoot)

            // 挂桥条件：模块 enabled + canWebBridge
            if (info.state == ModuleState.ENABLED && PermissionController.canWebBridge(moduleId)) {
                addJavascriptInterface(
                    ModuleJsBridge(
                        moduleId = moduleId,
                        moduleDir = moduleDir,
                        webRoot = webRoot,
                        webView = this
                    ),
                    ModuleJsBridge.JS_INTERFACE_NAME
                )
            }

            loadUrl(index.toURI().toString())
        }
        webView = wv

        val layout = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        setContentView(layout)
    }

    private fun showError(message: String) {
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(
                    TextView(this@ModuleWebViewActivity).apply {
                        text = message
                        setTextColor(Color.BLACK)
                        textSize = 16f
                        setPadding(48, 48, 48, 48)
                        gravity = Gravity.START or Gravity.TOP
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        webView?.apply {
            removeJavascriptInterface(ModuleJsBridge.JS_INTERFACE_NAME)
            destroy()
        }
        webView = null
        super.onDestroy()
    }

    /**
     * WebViewClient：拦截导航与资源请求，确保所有 file:// 资源都在 webRoot 内。
     * http/https 在 WebView 内直接拒绝（blockNetworkLoads=true 也会拦，这里再双保险）。
     */
    private class ModuleWebViewClient(
        private val webRoot: File
    ) : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                "file" -> !isInsideWebRoot(uri.path.orEmpty())
                // http/https 不在 WebView 内打开
                else -> true
            }
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val uri = request.url
            if (uri.scheme?.lowercase() == "file" && !isInsideWebRoot(uri.path.orEmpty())) {
                // 返回空响应，阻断越界 file:// 请求
                return WebResourceResponse("text/plain", "utf-8", null)
            }
            return super.shouldInterceptRequest(view, request)
        }

        private fun isInsideWebRoot(path: String): Boolean = runCatching {
            val rootPath = webRoot.canonicalPath
            val targetPath = File(path).canonicalPath
            targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        }.getOrDefault(false)
    }

    companion object {
        const val EXTRA_MODULE_ID = "module_id"
    }
}
