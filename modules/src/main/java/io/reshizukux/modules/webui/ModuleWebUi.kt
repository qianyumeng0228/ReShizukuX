package io.reshizukux.modules.webui

import android.content.Context
import android.content.Intent
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleState
import io.reshizukux.modules.permission.PermissionController
import java.io.File

/**
 * WebUI 启动入口（设计方案 §3.6.3，P6 UI 会调用）。
 *
 * 检查项：
 *  1. 模块已安装且存在
 *  2. webroot/index.html 存在
 *  3. 模块 ENABLED
 *  4. [PermissionController.canWebBridge] 通过
 *
 * 任一不满足返回 false，调用方据此禁用/隐藏"打开 WebUI"按钮。
 */
object ModuleWebUi {

    /** 判断当前模块是否可以打开 WebUI（不启动 Activity）。 */
    fun canOpenWebUI(moduleId: String): Boolean {
        val info = ModuleManager.getModule(moduleId) ?: return false
        if (info.state != ModuleState.ENABLED) return false
        if (!PermissionController.canWebBridge(moduleId)) return false
        val index = File(ModuleManager.getModuleDir(moduleId), "webroot/index.html")
        return index.isFile
    }

    /**
     * 启动 [ModuleWebViewActivity]。返回 true 表示已发起 Intent；false 表示前置检查未通过。
     */
    fun start(context: Context, moduleId: String): Boolean {
        if (!canOpenWebUI(moduleId)) return false
        val intent = Intent(context, ModuleWebViewActivity::class.java).apply {
            putExtra(ModuleWebViewActivity.EXTRA_MODULE_ID, moduleId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }
}
