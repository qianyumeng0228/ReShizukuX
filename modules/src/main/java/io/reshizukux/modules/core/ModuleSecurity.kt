package io.reshizukux.modules.core

import android.os.Build
import android.util.Base64
import timber.log.Timber
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipFile

/**
 * ZIP 包安全校验 + 哈希树 + Ed25519 签名校验（设计方案 §3.5）。
 *
 * P1：路径穿越双校验、资源上限、SHA-256 哈希树可用（Ed25519 占位）。
 * P7：Ed25519 真实验证（API 33+ JCE，旧版降级）+ 哈希树排除运行时可变文件。
 */
object ModuleSecurity {

    private const val TAG = "ModuleSecurity"

    /** ZIP entry 数上限（Shevery 同）。 */
    private const val MAX_ENTRIES = 2048

    /** 解压后总体积上限（Shevery 200MB，ReShizukuX 收紧到 100MB）。 */
    private const val MAX_TOTAL_UNCOMPRESSED = 100L * 1024 * 1024

    /** 单个 .sh 脚本文件上限。 */
    private const val MAX_SCRIPT_SIZE = 256L * 1024

    /**
     * Ed25519 (RFC 8032) 公钥的 X.509 SubjectPublicKeyInfo DER 前缀。
     *
     * SEQUENCE { SEQUENCE { OID 1.3.101.110 (2b 65 70) } BIT STRING <32B key> }
     * 拼上 32 字节原始公钥即得 [X509EncodedKeySpec] 可消费的 SPKI，
     * 避免在 API 33 之前手写 EdECPoint 编解码。
     */
    private val ED25519_SPKI_PREFIX = byteArrayOf(
        0x30, 0x2A, 0x30, 0x05, 0x06, 0x03, 0x2B, 0x65, 0x70, 0x03, 0x21, 0x00
    )

    /** 哈希树排除的运行时可变文件（相对模块根，排除后不影响篡改检测）。 */
    private val HASH_TREE_EXCLUDED_FILES = setOf(
        ".install_hash", // 自引用基线文件
        "disable",       // 启停状态标记（enable/disable 会改写）
        "update",        // 更新瞬时标记
        "error.log"      // 执行错误标记
    )

    /** 哈希树排除的运行时可变目录（顶层目录名）。 */
    private val HASH_TREE_EXCLUDED_DIRS = setOf("logs") // action/customize 日志

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
     *
     * P7：排除运行时可变文件（[HASH_TREE_EXCLUDED_FILES] / [HASH_TREE_EXCLUDED_DIRS]），
     * 否则 action.sh 写日志、enable/disable 改写 disable 标记会被误判为"篡改"。
     */
    fun calculateDirHashTree(dir: File): String {
        if (!dir.exists() || !dir.isDirectory) return ""
        val lines = mutableListOf<String>()
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            val relative = f.relativeTo(dir).path.replace(File.separatorChar, '/')
            if (relative in HASH_TREE_EXCLUDED_FILES) return@forEach
            if (relative.substringBefore('/') in HASH_TREE_EXCLUDED_DIRS) return@forEach
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
     * 签名内容 = ZIP 的 SHA-256（hex 字符串的 UTF-8 字节），公钥/签名均为 base64。
     *
     * 行为：
     *  - 无签名 / 无公钥 → 返回 true（未签名模块不阻止安装，UI 标记"未验证"）；
     *  - API ≥ 33：用 JCE [KeyFactory.getInstance]("Ed25519") + X.509 SPKI 真实验证；
     *  - API < 33：Ed25519 JCE 类不可用 → 降级为 SHA-256 哈希树校验-only（返回 true，
     *    不阻止安装；日志标记"签名验证不可用"），避免引入 BouncyCastle 重依赖；
     *  - 签名/公钥格式非法或验证失败 → 返回 false（安装回滚）。
     */
    fun verifyEd25519Signature(
        zipSha256: String,
        signatureBase64: String,
        publicKeyBase64: String
    ): Boolean {
        // 1. 未签名模块：不阻止安装
        if (signatureBase64.isBlank() || publicKeyBase64.isBlank()) return true

        // 2. API < 33：Ed25519 JCE（EdECPublicKeySpec / Ed25519 KeyFactory）最低可用版本。
        //    降级为哈希树校验-only，不阻塞安装。
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Timber.tag(TAG).w(
                "Ed25519 verification unavailable on API %d; falling back to hash-tree only",
                Build.VERSION.SDK_INT
            )
            return true
        }

        // 3. 真实 Ed25519 验证
        return runCatching {
            val pubBytes = Base64.decode(publicKeyBase64.trim(), Base64.DEFAULT)
            val sigBytes = Base64.decode(signatureBase64.trim(), Base64.DEFAULT)
            require(pubBytes.size == 32) {
                "Ed25519 public key must be 32 raw bytes, got ${pubBytes.size}"
            }
            // SPKI = DER 前缀 + 32B 原始公钥
            val spki = ED25519_SPKI_PREFIX + pubBytes
            val kf = KeyFactory.getInstance("Ed25519")
            val publicKey = kf.generatePublic(X509EncodedKeySpec(spki))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(publicKey)
            verifier.update(zipSha256.toByteArray(Charsets.UTF_8))
            verifier.verify(sigBytes)
        }.getOrElse {
            Timber.tag(TAG).w(it, "Ed25519 verification failed")
            false
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
