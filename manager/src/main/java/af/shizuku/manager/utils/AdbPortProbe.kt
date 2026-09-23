package af.shizuku.manager.utils

import android.content.Context
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.adb.AdbPortProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loopback (127.0.0.1) ADB TCP port probe — the "fast path" that lets [af.shizuku.manager.worker.AdbStartWorker]
 * skip the 15-second mDNS discovery when the wireless-debugging port is already listening locally.
 *
 * Candidate order (most-likely-first, per Portable orchestrator spec):
 *   1. [EnvironmentUtils.getAdbTcpPort] — the live system property / TV fallback port
 *   2. [ShizukuSettings.getLastPort] — the port of the last successful session
 *   3. 5555 — the legacy `adb tcpip` default
 *
 * Each candidate gets a 250 ms connect timeout; the first that accepts a loopback TCP connection wins.
 * Returns -1 when none is listening (caller should fall through to mDNS discovery).
 */
object AdbPortProbe {

    /** Probes candidate ports on 127.0.0.1 and returns the first live one, or -1. */
    suspend fun getLiveAdbTcpPort(context: Context): Int = withContext(Dispatchers.IO) {
        val candidates = LinkedHashSet<Int>()

        // 1. Live system property port (or TV fallback).
        val sysPort = EnvironmentUtils.getAdbTcpPort()
        if (sysPort in 1..65535) candidates.add(sysPort)

        // 2. Last successful session port.
        val lastPort = ShizukuSettings.getLastPort()
        if (lastPort in 1..65535) candidates.add(lastPort)

        // 3. Legacy default.
        candidates.add(5555)

        for (port in candidates) {
            if (AdbPortProber.isPortOpen(port, PROBE_TIMEOUT_MS)) {
                return@withContext port
            }
        }
        -1
    }

    private const val PROBE_TIMEOUT_MS = 250
}
