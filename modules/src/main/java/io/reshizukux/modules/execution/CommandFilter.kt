package io.reshizukux.modules.execution

/**
 * HIGH 风险命令静态过滤结果。
 *
 * @property hasHighRisk 是否命中至少一条 HIGH 规则（执行前需用户确认）
 * @property matchedLines 命中的原始脚本行（用于向用户展示）
 */
data class FilterResult(
    val hasHighRisk: Boolean,
    val matchedLines: List<String>
)

/**
 * HIGH 风险命令正则过滤（设计方案 §3.4.2，参考 AxManagerD RuleEngine.kt:18-70 精简为 8 条核心规则）。
 *
 * 不做 shell AST 解析，只做：行级注释剥离 + 正则匹配。命中 HIGH → 弹确认对话框展示命中行。
 */
object CommandFilter {

    /** 8 条核心 HIGH 规则（正则，忽略大小写、容忍空白变形）。 */
    private val HIGH_RULES: List<Regex> = listOf(
        Regex("""rm\s+-[a-z]*r[a-z]*f[a-z]*\s+/(\s|$)""", RegexOption.IGNORE_CASE),   // rm -rf /
        Regex("""\bdd\s+if=""", RegexOption.IGNORE_CASE),                                  // dd if=
        Regex("""\bsetenforce\s+0""", RegexOption.IGNORE_CASE),                            // setenforce 0
        Regex("""\bmkfs(\.|\s)""", RegexOption.IGNORE_CASE),                               // mkfs
        Regex("""/dev/block/by-name""", RegexOption.IGNORE_CASE),                          // /dev/block/by-name
        Regex("""mount\s+-o\s+remount,rw\s+/system""", RegexOption.IGNORE_CASE),           // mount -o remount,rw /system
        Regex("""base64\b.*\beval\b""", RegexOption.IGNORE_CASE),                          // base64 ... eval
        Regex("""curl\b.*\|.*\bsh\b""", RegexOption.IGNORE_CASE)                            // curl ... | sh
    )

    /**
     * 过滤脚本内容：剥离注释行（首非空白字符为 #）后逐行匹配 HIGH 规则。
     */
    fun filter(scriptContent: String): FilterResult {
        val matched = mutableListOf<String>()
        scriptContent.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            for (rule in HIGH_RULES) {
                if (rule.containsMatchIn(trimmed)) {
                    matched.add(trimmed)
                    break
                }
            }
        }
        return FilterResult(hasHighRisk = matched.isNotEmpty(), matchedLines = matched)
    }
}
