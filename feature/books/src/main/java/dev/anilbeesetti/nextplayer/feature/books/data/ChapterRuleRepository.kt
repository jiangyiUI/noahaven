package dev.anilbeesetti.nextplayer.feature.books.data

import android.content.Context
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.ChapterRule
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Single

/**
 * TXT 分章规则仓库：合并内置规则（asset 只读）与用户自建规则（持久化在 ApplicationPreferences），
 * 并提供新建/编辑/删除/设默认等操作。
 */
@Single
class ChapterRuleRepository(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
) {

    private val builtinRules: List<ChapterRule> by lazy { loadBuiltin() }

    /** 全部规则（内置 + 自建），按 sortOrder 升序，sortOrder 相同则内置在前。 */
    val rules: Flow<List<ChapterRule>> =
        preferencesRepository.applicationPreferences.map { combineRules(it) }

    /** 当前默认规则 id（无效值回退到 [ChapterRuleDefaults.DEFAULT_RULE_ID]）。 */
    val defaultRuleId: Flow<String> =
        preferencesRepository.applicationPreferences.map { resolveDefaultId(it) }

    /** 当前默认规则；取不到时回退到内置常规模式。 */
    suspend fun defaultRule(): ChapterRule = ruleById(defaultRuleId.first())
        ?: builtinRules.firstOrNull { it.id == ChapterRuleDefaults.FALLBACK_RULE_ID }
        ?: builtinRules.first()

    suspend fun ruleById(id: String): ChapterRule? = rules.first().firstOrNull { it.id == id }

    /** 首次启动播种初始自建规则与默认规则；之后不再重复播种。 */
    suspend fun ensureSeeded() {
        val prefs = preferencesRepository.applicationPreferences.value
        if (prefs.bookChapterRulesSeeded) return
        preferencesRepository.updateApplicationPreferences { current ->
            val customJson = if (current.bookChapterRules.isBlank()) {
                encode(ChapterRuleDefaults.DEFAULT_CUSTOM_RULES)
            } else {
                current.bookChapterRules
            }
            current.copy(
                bookChapterRules = customJson,
                bookChapterRulesSeeded = true,
                bookDefaultChapterRuleId = current.bookDefaultChapterRuleId
                    ?: ChapterRuleDefaults.DEFAULT_RULE_ID,
            )
        }
    }

    /**
     * 新建或更新一条自建规则（内置规则不可改）。
     * @param setAsDefault 保存后是否把它设为默认规则（新建时由仓库生成 id，故在此一并设置）。
     */
    suspend fun upsertCustomRule(rule: ChapterRule, setAsDefault: Boolean = false) {
        preferencesRepository.updateApplicationPreferences { current ->
            val list = parseCustom(current).toMutableList()
            val toSave = if (rule.id.isBlank()) {
                rule.copy(
                    id = UUID.randomUUID().toString(),
                    sortOrder = (list.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                    isBuiltin = false,
                )
            } else {
                rule.copy(isBuiltin = false)
            }
            val index = list.indexOfFirst { it.id == toSave.id }
            if (index >= 0) list[index] = toSave else list.add(toSave)
            val newDefault = if (setAsDefault) toSave.id else current.bookDefaultChapterRuleId
            current.copy(
                bookChapterRules = encode(list),
                bookDefaultChapterRuleId = newDefault,
            )
        }
    }

    /** 删除一条自建规则；若被删的是默认规则，默认回退到内置常规模式。 */
    suspend fun deleteCustomRule(id: String) {
        preferencesRepository.updateApplicationPreferences { current ->
            val list = parseCustom(current).filterNot { it.id == id }
            val newDefault = if (current.bookDefaultChapterRuleId == id) {
                ChapterRuleDefaults.DEFAULT_RULE_ID
            } else {
                current.bookDefaultChapterRuleId
            }
            current.copy(
                bookChapterRules = encode(list),
                bookDefaultChapterRuleId = newDefault,
            )
        }
    }

    suspend fun setDefaultRuleId(id: String) {
        preferencesRepository.updateApplicationPreferences {
            it.copy(bookDefaultChapterRuleId = id)
        }
    }

    private fun combineRules(prefs: ApplicationPreferences): List<ChapterRule> {
        val custom = parseCustom(prefs)
        val defaultId = prefs.bookDefaultChapterRuleId
        // 排序：默认规则置顶 → 自定义规则 → 内置规则；同组内按 sortOrder 升序，保持稳定。
        fun rank(rule: ChapterRule): Int = when {
            defaultId != null && rule.id == defaultId -> 0
            !rule.isBuiltin -> 1
            else -> 2
        }
        return (builtinRules + custom).sortedWith(
            compareBy<ChapterRule> { rank(it) }
                .thenBy { it.sortOrder }
                .thenBy { if (it.isBuiltin) 0 else 1 },
        )
    }

    private fun resolveDefaultId(prefs: ApplicationPreferences): String {
        val stored = prefs.bookDefaultChapterRuleId
        val allIds = (builtinRules + parseCustom(prefs)).map { it.id }.toSet()
        return if (stored != null && stored in allIds) stored else ChapterRuleDefaults.DEFAULT_RULE_ID
    }

    private fun parseCustom(prefs: ApplicationPreferences): List<ChapterRule> {
        val raw = prefs.bookChapterRules
        if (raw.isBlank()) return emptyList()
        return runCatching {
            ChapterRuleDefaults.json.decodeFromString<List<ChapterRule>>(raw)
        }.getOrDefault(emptyList()).filterNot { it.isBuiltin }
    }

    private fun encode(list: List<ChapterRule>): String =
        ChapterRuleDefaults.json.encodeToString(list)

    private fun loadBuiltin(): List<ChapterRule> {
        val text = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        val arr = runCatching {
            ChapterRuleDefaults.json.parseToJsonElement(text).jsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val sortOrder = obj["sort_order"]?.jsonPrimitive?.intOrNull ?: 0
            val rule = obj["rule"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val example = obj["example"]?.jsonPrimitive?.contentOrNull
            ChapterRule(
                id = id,
                name = name,
                sortOrder = sortOrder,
                enabled = true,
                isBuiltin = true,
                chapterRegex = rule,
                example = example,
            )
        }
    }

    companion object {
        private const val ASSET_NAME = "builtin_txt_chapter_rules.json"
    }
}
