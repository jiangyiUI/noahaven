package dev.anilbeesetti.nextplayer.feature.books.data

import dev.anilbeesetti.nextplayer.core.model.ChapterNumberType
import dev.anilbeesetti.nextplayer.core.model.ChapterRule
import kotlinx.serialization.json.Json

/** TXT 分章规则的常量与默认值。 */
internal object ChapterRuleDefaults {

    /** 兜底规则 id：当默认规则分不到任何章节时，用它再分一次。 */
    const val FALLBACK_RULE_ID = "builtin_2"

    /** 首次启动、用户未设置默认规则时使用的默认规则 id。 */
    const val DEFAULT_RULE_ID = "builtin_2"

    /** 前缀下拉预设（同时也允许手输）。 */
    val PREFIX_PRESETS: List<String> = listOf("第", "Chapter", "==")

    /** 后缀下拉预设（同时也允许手输）。 */
    val SUFFIX_PRESETS: List<String> =
        listOf("章", "节", "回", "部", "篇", "话", "场", "卷")

    /** 序号类型下拉项。 */
    val NUMBER_TYPES: List<ChapterNumberType> =
        listOf(ChapterNumberType.CHINESE, ChapterNumberType.ARABIC, ChapterNumberType.MIXED)

    /** 首次启动播种的自建规则（用户可编辑/删除，播种后不再自动加回）。 */
    val DEFAULT_CUSTOM_RULES: List<ChapterRule> = listOf(
        ChapterRule(
            id = "0198a76e-6a3e-7745-97aa-44385eb31bfc",
            name = "括号混合",
            sortOrder = 0,
            isBuiltin = false,
            prefix = "第",
            numberType = ChapterNumberType.MIXED.storage,
            suffix = "章",
            useVolume = false,
            chapterRegex =
                "^\\s*[（(]?\\s*(?<![一二三四五六七八九十])([一二三四五六七八九十百千万0-9０-９]+)\\s*[）)]?\\s*(?!(?:[^\\s）)\\n]*[^\\d一二三四五六七八九十百千万0-9０-９\\s）)]))[^\\n]*",
        ),
        ChapterRule(
            id = "01994a80-a0d7-78a4-9cb2-38d73c9e1ce8",
            name = "中文数字：章节名",
            sortOrder = 1,
            isBuiltin = false,
            prefix = "第",
            numberType = ChapterNumberType.MIXED.storage,
            suffix = "章",
            useVolume = false,
            chapterRegex = "^(?:[一二三四五六七八九十百千万]+：.*|.+章：.*)$",
        ),
    )

    /** 序列化自建规则用的 Json（与备份导入导出无关，仅内部持久化）。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
