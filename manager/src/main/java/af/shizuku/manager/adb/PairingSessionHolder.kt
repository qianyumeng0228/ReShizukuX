package af.shizuku.manager.adb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 向导 <-> [AdbPairingService] 之间的共享配对会话状态。
 *
 * 向导（Compose）和通知 RemoteInput（Service）两条通道共享同一个 pairingPort：
 *  - Service 的 mDNS 发现端口后写入 [pairingPortFlow]；
 *  - 向导观察 [pairingPortFlow] 自动进入 Step 3；
 *  - 任一条通道收到 6 位码后，通过 [AdbPairingService.dialogReplyIntent] 发给 Service 执行配对；
 *  - Service 配对完成后回调 [resultCallback]，向导据此进入 Step 5。
 *
 * [portableMode] = true 时，Service 跳过 autoGrantAndStart / StarterActivity 跳转，
 * 仅通知向导结果，由向导负责后续 connect + startServer。
 */
object PairingSessionHolder {

    /** mDNS 发现的 pairing 端口（0 = 尚未发现）。 */
    private val _pairingPortFlow = MutableStateFlow(0)
    val pairingPortFlow: StateFlow<Int> = _pairingPortFlow

    /** mDNS 解析的 host。 */
    @Volatile
    var pairingHost: String = "127.0.0.1"
        private set

    /** Portable 模式：Service 配对成功后不自动拉起 StarterActivity，仅回调向导。 */
    @Volatile
    var portableMode: Boolean = false

    /** 配对结果回调（success, port, error）。由向导设置，Service 调用。 */
    @Volatile
    var resultCallback: ((success: Boolean, port: Int, error: Throwable?) -> Unit)? = null

    /** Service 的 mDNS 发现配对端口时调用。 */
    fun onPairingPortFound(port: Int, host: String) {
        if (port > 0 && _pairingPortFlow.value <= 0) {
            pairingHost = host
            _pairingPortFlow.value = port
        }
    }

    /** 向导取消/完成时重置。 */
    fun clear() {
        _pairingPortFlow.value = 0
        pairingHost = "127.0.0.1"
        portableMode = false
        resultCallback = null
    }
}
