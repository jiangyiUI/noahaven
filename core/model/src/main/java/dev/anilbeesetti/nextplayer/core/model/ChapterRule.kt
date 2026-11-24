package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * TXT 正文分章规则的序号类型，决定「前缀 + 序号 + 后缀」简单模式下匹配的数字字符集。
 */
@Serializable
enum class ChapterNumberType(val storage: String, val displayName: String) {
    CHINESE("chinese", "中文数字"),
    ARABIC("arabic", "阿拉伯数字"),
    MIXED("mixed", "混合数字"),
    ;

    companion object {
        /** 中文数字字符集（含常见大写/异体字）。 */
        const val CHINESE_CLASS = "零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟"

        /** 阿拉伯数字字符集（含全角）。 */
        const val ARABIC_CLASS = "0-9０-９"

        fun fromStorage(value: String?): ChapterNumberType =
            entries.firstOrNull { it.storage == value } ?: MIXED
    }
}

/**
 * TXT 小说正文的章节标题识别规则。
 *
 * 与「阅读」类应用的 rule_txt_chapter 规则格式兼容：
 * - 当 [chapterRegex] 非空时为**正则模式**，直接用该正则匹配；
 * - 当 [chapterRegex] 为空（对应源数据里的 `"rule": null`）时为**简单模式**，
 *   由 [prefix] + [numberType] + [suffix] 自动拼出正则。
 * - [useVolume] 开启后用同样的逻辑生成分卷正则（[volumeRegex] 非空时优先用它）。
 *
 * 内置规则（[isBuiltin]=true）只读，不可编辑/删除；用户自建规则可编辑删除。
 * 只有被设为默认的规则会在新书打开时参与匹配，其余规则仅在「重新分章」里临时选用。
 *
 * @param id 规则唯一标识；内置以 `builtin_` 开头，自建为 UUID。
 * @param name 规则显示名。
 * @param sortOrder 排序/优先级，越小越靠前；合并匹配时同位置取靠前规则的标题。
 * @param enabled 预留字段，当前 UI 不展示开关，但保留以备后续使用。
 * @param isBuiltin 是否内置（只读）。
 * @param chapterRegex 正则模式下的章节正则；空表示简单模式。
 * @param volumeRegex 分卷正则（仅 [useVolume] 时生效，空则用简单字段拼）。
 * @param example 规则匹配示例（仅展示用，内置规则从源数据带入；自建规则可为空）。
 */
@Serializable
data class ChapterRule(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val isBuiltin: Boolean = false,
    val prefix: String = "",
    val numberType: String = ChapterNumberType.MIXED.storage,
    val suffix: String = "",
    val useVolume: Boolean = false,
    val volumePrefix: String = "",
    val volumeNumberType: String = ChapterNumberType.MIXED.storage,
    val volumeSuffix: String = "",
    val chapterRegex: String? = null,
    val volumeRegex: String? = null,
    val example: String? = null,
) {
    /** 正则模式 = 显式填写了章节正则。 */
    val isRegexMode: Boolean get() = !chapterRegex.isNullOrBlank()

    /** 解析后的序号类型（未知值回退到混合数字）。 */
    val parsedNumberType: ChapterNumberType
        get() = ChapterNumberType.fromStorage(numberType)

    val parsedVolumeNumberType: ChapterNumberType
        get() = ChapterNumberType.fromStorage(volumeNumberType)

    /** 实际用于匹配章节的正则字符串。 */
    fun chapterPattern(): String =
        chapterRegex?.takeIf { it.isNotBlank() }
            ?: buildSimplePattern(prefix, parsedNumberType, suffix)

    /** 实际用于匹配分卷的正则字符串；未启用分卷时为 null。 */
    fun volumePattern(): String? {
        if (!useVolume) return null
        return volumeRegex?.takeIf { it.isNotBlank() }
            ?: buildSimplePattern(volumePrefix, parsedVolumeNumberType, volumeSuffix)
    }

    /** 编译章节正则，编译失败返回 null（避免单条坏规则搞崩阅读）。 */
    fun compileChapter(): Regex? = runCatching { Regex(stripInlineFlags(chapterPattern())) }.getOrNull()

    /** 编译分卷正则，编译失败或未启用返回 null。 */
    fun compileVolume(): Regex? =
        volumePattern()?.let { runCatching { Regex(stripInlineFlags(it)) }.getOrNull() }

    /** 测试某一行是否被本规则的章节正则命中。 */
    fun matchesLine(line: String): Boolean = compileChapter()?.find(line.trim()) != null

    companion object {
        /** 去掉正则开头的内联 flag（如 `(?m)`），逐行匹配时无需多行模式。 */
        internal fun stripInlineFlags(pattern: String): String {
            var p = pattern
            while (p.startsWith("(?")) {
                val end = p.indexOf(')')
                if (end <= 2) break
                // 仅当括号内是纯 flag 字母（不含 ':' '=' '!' '<'）时才剥离
                val body = p.substring(2, end)
                if (body.all { it in "imsuxU" }) {
                    p = p.substring(end + 1)
                } else {
                    break
                }
            }
            return p
        }

        /** 由前缀/序号类型/后缀拼出简单模式正则。 */
        internal fun buildSimplePattern(
            prefix: String,
            type: ChapterNumberType,
            suffix: String,
        ): String {
            val numberClass = when (type) {
                ChapterNumberType.CHINESE -> ChapterNumberType.CHINESE_CLASS
                ChapterNumberType.ARABIC -> ChapterNumberType.ARABIC_CLASS
                ChapterNumberType.MIXED ->
                    ChapterNumberType.CHINESE_CLASS + ChapterNumberType.ARABIC_CLASS
            }
            val p = Regex.escape(prefix)
            val s = Regex.escape(suffix)
            return "^\\s*$p\\s*[$numberClass]+\\s*$s.*$"
        }
    }
}
