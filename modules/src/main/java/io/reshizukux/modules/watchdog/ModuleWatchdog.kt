package io.reshizukux.modules.watchdog

import android.content.Context
import io.reshizukux.modules.core.ModuleManager
import io.reshizukux.modules.core.ModuleState
import io.reshizukux.modules.db.InstalledModule
import io.reshizukux.modules.db.ModuleDatabase
import io.reshizukux.modules.execution.ServiceRunner
import io.reshizukux.modules.permission.PermissionController
import timber.log.Timber
import java.io.File

/**
 * 模块 service.sh 存活轮询 + 指数退避重启 + 熔断（设计方案 §3.3.3）。
 *
 * 由 [af.shizuku.manager.service.ShizukuDaemonService] 的 5s 轮询协程在每轮末尾调用
 * [checkAndRevive]，本类不自起循环、不新增前台服务。
 *
 * 退避档位 [BACKOFF_MS]（参考 AxManagerD RuntimeModuleService.kt:84-85）：
 *   1s → 2s → 4s → 8s → 15s → 30s → 60s。
 * 熔断：同一模块 [CIRCUIT_WINDOW_MS]（60s）内重启 ≥ [CIRCUIT_MAX_RESTARTS]（5）次，
 *   置 DB 状态为 ERROR 并停止重启；用户重新 enable（[ModuleManager.enable]）调用
 *   [clearCircuit] 清除计数后恢复。
 *
 * 省电：无任何「ENABLED 且带 service.sh 且有 SERVICE 权限」的模块时立即返回，不做任何 pgrep。
 */
object ModuleWatchdog {

    private const val TAG = "ModuleWatchdog"

    /** 指数退避档位（毫秒），索引递增；最后一档后保持。 */
    private val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L, 60_000L)

    /** 熔断窗口与最大重启次数（与 ShizukuDaemonService server 熔断同参数）。 */
    private const val CIRCUIT_WINDOW_MS = 60_000L
    private const val CIRCUIT_MAX_RESTARTS = 5

    /** 单模块运行时退避状态。 */
    private class ModuleBackoff {
        val restartLog = ArrayDeque<Long>()
        var backoffIdx: Int = 0
        var cooldownUntil: Long = 0L
    }

    /** moduleId → 退避状态（仅 watchdog 进程内有效；熔断持久化在 DB state=ERROR）。 */
    private val states = HashMap<String, ModuleBackoff>()

    /** 用户重新 enable 时调用：清掉该模块的内存熔断/退避计数。 */
    fun clearCircuit(moduleId: String) {
        synchronized(states) { states.remove(moduleId) }
    }

    /**
     * 每轮轮询调用（同步阻塞在 IO 线程）。扫描 ENABLED 且有 service.sh 的模块：
     * 存活则复位退避；死亡则按退避重启，超限熔断为 ERROR。
     */
    fun checkAndRevive(context: Context) {
        val candidates: List<InstalledModule> = try {
            ModuleDatabase.getInstance(context).moduleDao().getAll().filter { row ->
                row.state == ModuleState.ENABLED.name &&
                    File(ModuleManager.getModuleDir(row.id), "service.sh").exists() &&
                    PermissionController.canService(row.id)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "read modules failed")
            return
        }

        // 省电：无后台模块直接返回
        if (candidates.isEmpty()) return

        val now = System.currentTimeMillis()
        for (row in candidates) {
            try {
                reviveOne(row, now)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "checkAndRevive for ${row.id} failed")
            }
        }
    }

    private fun reviveOne(row: InstalledModule, now: Long) {
        val id = row.id
        val backoff = synchronized(states) { states.getOrPut(id) { ModuleBackoff() } }

        val alive = ServiceRunner.isServiceRunning(id)
        if (alive) {
            // 稳定存活：复位退避档，清理窗口外旧时间戳
            synchronized(backoff.restartLog) {
                while (backoff.restartLog.isNotEmpty() &&
                    now - backoff.restartLog.first() > CIRCUIT_WINDOW_MS
                ) {
                    backoff.restartLog.removeFirst()
                }
            }
            backoff.backoffIdx = 0
            return
        }

        // 死亡：先剪枝窗口外时间戳
        synchronized(backoff.restartLog) {
            while (backoff.restartLog.isNotEmpty() &&
                now - backoff.restartLog.first() > CIRCUIT_WINDOW_MS
            ) {
                backoff.restartLog.removeFirst()
            }
        }

        // 熔断判定
        if (backoff.restartLog.size >= CIRCUIT_MAX_RESTARTS) {
            Timber.tag(TAG).e(
                "circuit open for $id: ${backoff.restartLog.size} restarts within " +
                    "${CIRCUIT_WINDOW_MS}ms; marking ERROR and stopping"
            )
            ModuleDatabase.getInstance(ModuleManager.appContext()).moduleDao()
                .updateState(id, ModuleState.ERROR.name)
            synchronized(states) { states.remove(id) }
            return
        }

        // 退避中：未到下一次重启时刻
        if (now < backoff.cooldownUntil) return

        // 执行重启
        val ok = ServiceRunner.startService(id)
        if (!ok) {
            // 启动失败：拉长冷却，避免每 5s 空转（不等同于一次成功重启）
            backoff.cooldownUntil = now + BACKOFF_MS.last()
            Timber.tag(TAG).w("revive $id failed; cooling down ${BACKOFF_MS.last()}ms")
            return
        }

        synchronized(backoff.restartLog) { backoff.restartLog.addLast(now) }
        backoff.backoffIdx = (backoff.backoffIdx + 1).coerceAtMost(BACKOFF_MS.lastIndex)
        backoff.cooldownUntil = now + BACKOFF_MS[backoff.backoffIdx]
        Timber.tag(TAG).i(
            "revived $id (restart #${backoff.restartLog.size}, next cooldown " +
                "${BACKOFF_MS[backoff.backoffIdx]}ms)"
        )
    }
}
