package io.reshizukux.modules.core

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * ZIP 包安全校验 + 哈希树 + 签名骨架（设计方案 §3.5）。
 *
 * P1：路径穿越双校验、资源上限、SHA-256 哈希树可用；Ed25519 验证为骨架，P7 完整实现。
 */
object ModuleSecurity {

    /** ZIP entry 数上限（Shevery 同）。 */
    private const val MAX_ENTRIES = 2048

    /** 解压后总体积上限（Shevery 200MB，ReShizukuX 收紧到 100MB）。 */
    private const val MAX_TOTAL_UNCOMPRESSED = 100L * 1024 * 1024

    /** 单个 .sh 脚本文件上限。 */
    private const val MAX_SCRIPT_SIZE = 256L * 1024

    /**
     * 静态校验 ZIP entries，返回违规描述列表；空列表 = 通过。
     *
     * 双重路径穿越校验之第一重（字符串级）：entry.name 含 `../`、绝对路径、盘符分隔符即拒绝。
     * 第二重（canonical 级）在解包后由 [isCanonicalUnder] 兜底（见 ModuleManager 解包流程）。
     */
    fun validateZipEntries(zipFile: ZipFile): List<String> {
        val violations = mutableListOf<String>()
        val entries = zipFile.entries()
        var count = 0
        var totalUncompressed = 0L
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            count++
            if (count > MAX_ENTRIES) {
                violations.add("ZIP entry count exceeds $MAX_ENTRIES (got >$count)")
                break
            }
            val name = entry.name
            // 第一重：字符串级路径穿越
            if (name.contains("../") || name.contains("..\\")) {
                violations.add("path traversal in entry name: $name")
                continue
            }
            if (name.startsWith("/") || name.startsWith("\\")) {
                violations.add("absolute path entry rejected: $name")
                continue
            }
            if (name.contains(":")) {
                violations.add("drive/colon entry rejected: $name")
                continue
            }
            totalUncompressed += entry.size
            if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED) {
                violations.add("uncompressed total exceeds 100MB")
                break
            }
            if (name.endsWith(".sh") && !entry.isDirectory && entry.size > MAX_SCRIPT_SIZE) {
                violations.add("script $name exceeds 256KB (${entry.size} bytes)")
            }
        }
        return violations
    }

    /**
     * 第二重 canonical 级校验：解包后的 file.canonicalPath 必须以 targetDir.canonicalPath 前缀开头。
     */
    fun isCanonicalUnder(child: File, targetDir: File): Boolean {
        val target = targetDir.canonicalPath
        val childPath = child.canonicalPath
        return childPath == target || childPath.startsWith(target + File.separator)
    }

    /** 计算 ZIP 文件本身的 SHA-256（hex）。 */
    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    /**
     * 目录内所有文件的哈希树（篡改检测，设计方案 §3.5.4 Level 1）。
     *
     * 对所有相对路径排序后，逐行拼接 "<相对路径> <文件SHA-256>\n"，再对整段文本取 SHA-256。
     */
    fun calculateDirHashTree(dir: File): String {
        if (!dir.exists() || !dir.isDirectory) return ""
        val lines = mutableListOf<String>()
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val relative = f.relativeTo(dir).path.replace(File.separatorChar, '/')
            lines.add("$relative ${calculateSha256(f)}")
        }
        lines.sort()
        val joined = lines.joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(joined.toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Ed25519 签名验证（设计方案 §3.5.4 Level 2）。
     *
     * P1 骨架：暂返回 true 占位。P7 用 BouncyCastle / Conscrypt 完整实现：
     * 签名内容 = ZIP 的 SHA-256（hex 字符串字节），公钥/签名均为 base64。
     */
    fun verifyEd25519Signature(
        zipSha256: String,
        signatureBase64: String,
        publicKeyBase64: String
    ): Boolean {
        // TODO(P7): implement real Ed25519 verification.
        return true
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
