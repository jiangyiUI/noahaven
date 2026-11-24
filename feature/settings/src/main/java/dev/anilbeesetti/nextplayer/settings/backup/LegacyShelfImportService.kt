package dev.anilbeesetti.nextplayer.settings.backup

import android.content.Context
import android.net.Uri
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.media.index.BookFileIndex
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfGroup
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.annotation.Single

@Single
class LegacyShelfImportService(
    private val context: Context,
    private val bookFileIndex: BookFileIndex,
    private val bookShelfRepository: BookShelfRepository,
    private val preferencesRepository: PreferencesRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class LegacyShelfImportPreview(
        val totalActive: Int,
        val matchedTotal: Int,
        /** 将新建的分组（含缺失的祖先），显示用，"根 / 子 / 孙" 形式。 */
        val groupsToCreate: List<String>,
        /** 叶子分组的完整名称路径 -> 匹配到的书籍绝对路径。 */
        val groupMatches: Map<List<String>, List<String>>,
        val uncategorizedGroup: String,
        val uncategorizedPaths: List<String>,
        /** 未匹配的书：分组路径显示串 -> 书名列表。 */
        val notFoundByGroup: Map<String, List<String>>,
    )

    @Serializable
    data class LegacyCategory(
        val id: String,
        val name: String,
        @SerialName("parent_id") val parentId: String? = null,
    )

    @Serializable
    data class LegacyBook(
        val id: String,
        val title: String,
        @SerialName("category_id") val categoryId: String? = null,
        @SerialName("deleted_at") val deletedAt: String? = null,
    )

    suspend fun parseAndPreview(
        categoryUri: Uri,
        bookUri: Uri,
    ): LegacyShelfImportPreview = withContext(Dispatchers.IO) {
        val categories = runCatching {
            readText(categoryUri)?.let {
                json.decodeFromString<List<LegacyCategory>>(it)
            }
        }.getOrNull().orEmpty()
        val books = runCatching {
            readText(bookUri)?.let {
                json.decodeFromString<List<LegacyBook>>(it)
            }
        }.getOrNull().orEmpty()

        val activeBooks = books.filter { it.deletedAt.isNullOrBlank() }

        val localBases = preferencesRepository.applicationPreferences.value.bookFolders
        val fileNameToPath = buildFileNameToPathMap(localBases)

        val idToCategory = categories.associateBy { it.id }

        // 某个分类从根到自身的完整名称路径（含自身），带环检测
        fun pathOf(catId: String): List<String>? {
            val chain = mutableListOf<String>()
            val seen = HashSet<String>()
            var cur = idToCategory[catId] ?: return null
            while (seen.add(cur.id)) {
                chain += cur.name
                val pid = cur.parentId ?: break
                cur = idToCategory[pid] ?: break
            }
            return chain.asReversed()
        }

        // 叶子分组路径 -> 匹配到的书籍绝对路径
        val groupMatches = LinkedHashMap<List<String>, MutableList<String>>()
        val uncategorizedPaths = mutableListOf<String>()
        val notFound = LinkedHashMap<String, MutableList<String>>()

        for (book in activeBooks) {
            val path = fileNameToPath[book.title.trim().lowercase()]
            if (path == null) {
                val groupLabel = book.categoryId?.let { pathOf(it) }?.let { formatPath(it) }
                    ?: UNCATEGORIZED_GROUP
                notFound.getOrPut(groupLabel) { mutableListOf() } += book.title
                continue
            }
            val group = book.categoryId?.let { pathOf(it) }
            if (group.isNullOrEmpty()) {
                uncategorizedPaths += path
            } else {
                groupMatches.getOrPut(group) { mutableListOf() } += path
            }
        }

        // 计算将要新建的分组：叶子 + 所有缺失祖先，按现有分组的「父级链 + 名字」比对
        val existingGroups = bookShelfRepository.groups.first()
        val existingKeys = existingGroups
            .map { parentChainOf(existingGroups, it.parentId) to it.name }
            .toSet()
        val neededKeys = LinkedHashSet<Pair<List<String>, String>>()
        for (leaf in groupMatches.keys) {
            var chain = leaf.dropLast(1)
            var name = leaf.last()
            while (true) {
                neededKeys += chain to name
                if (chain.isEmpty()) break
                name = chain.last()
                chain = chain.dropLast(1)
            }
        }
        val groupsToCreate = neededKeys
            .filterNot { it in existingKeys }
            .sortedBy { it.first.size }
            .map { formatPath(it.first, it.second) }

        LegacyShelfImportPreview(
            totalActive = activeBooks.size,
            matchedTotal = groupMatches.values.sumOf { it.size } + uncategorizedPaths.size,
            groupsToCreate = groupsToCreate,
            groupMatches = groupMatches,
            uncategorizedGroup = UNCATEGORIZED_GROUP,
            uncategorizedPaths = uncategorizedPaths,
            notFoundByGroup = notFound,
        )
    }

    suspend fun applyImport(preview: LegacyShelfImportPreview) = withContext(Dispatchers.IO) {
        // 叶子分组路径 -> (父级链, 名字)
        val leafRequests = preview.groupMatches.keys.map { it.dropLast(1) to it.last() }
        val keyToId = bookShelfRepository.ensureGroupsByPaths(leafRequests)
        for ((path, paths) in preview.groupMatches) {
            val groupId = keyToId[path.dropLast(1) to path.last()] ?: continue
            bookShelfRepository.addPaths(groupId = groupId, paths = paths)
        }
        if (preview.uncategorizedPaths.isNotEmpty()) {
            val groupId = bookShelfRepository.ensureUngroupedGroup(preview.uncategorizedGroup)
            bookShelfRepository.addPaths(groupId = groupId, paths = preview.uncategorizedPaths)
        }
    }

    suspend fun exportNotFound(preview: LegacyShelfImportPreview, uri: Uri) =
        withContext(Dispatchers.IO) {
            val text = buildString {
                appendLine("noahaven 书架导入未匹配清单")
                appendLine("生成时间: ${java.text.SimpleDateFormat.getInstance().format(java.util.Date())}")
                appendLine()
                preview.notFoundByGroup.forEach { (group, titles) ->
                    appendLine("【$group】${titles.size} 本")
                    titles.forEach { appendLine("  - $it") }
                    appendLine()
                }
            }
            context.contentResolver.openOutputStream(uri)?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            }
        }

    private fun formatPath(path: List<String>): String = path.joinToString(" / ")

    private fun formatPath(chain: List<String>, name: String): String =
        (chain + name).joinToString(" / ")

    /** 现有分组某节点的父级名称链（从根到直接父）。 */
    private fun parentChainOf(groups: List<BookShelfGroup>, parentId: Long?): List<String> {
        val byId = groups.associateBy { it.id }
        val chain = mutableListOf<String>()
        var current = parentId
        while (current != null) {
            val group = byId[current] ?: break
            chain += group.name
            current = group.parentId
        }
        return chain.asReversed()
    }

    private fun buildFileNameToPathMap(localBases: List<String>): Map<String, String> {
        val map = HashMap<String, String>()
        for (base in localBases) {
            if (base.isBlank()) continue
            val dir = File(base)
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    map[file.nameWithoutExtension.trim().lowercase()] = file.absolutePath
                }
            }
        }
        return map
    }

    private fun readText(uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull()
    }

    companion object {
        private const val UNCATEGORIZED_GROUP = "未分组"
    }
}
