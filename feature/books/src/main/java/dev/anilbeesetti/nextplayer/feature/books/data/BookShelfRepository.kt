package dev.anilbeesetti.nextplayer.feature.books.data

import dev.anilbeesetti.nextplayer.core.database.dao.BookShelfDao
import dev.anilbeesetti.nextplayer.core.database.entities.BookShelfGroupEntity
import dev.anilbeesetti.nextplayer.core.database.entities.BookShelfItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/** 书架分组：支持多级嵌套（parentId=null 为一级），isUngrouped 标记固定的「未分组」。 */
data class BookShelfGroup(
    val id: Long,
    val name: String,
    val parentId: Long? = null,
    val isUngrouped: Boolean = false,
    val sortOrder: Int = 0,
)

/** 书架条目：只记录路径和分组，删除它不会影响磁盘文件。 */
data class BookShelfEntry(
    val id: Long,
    val groupId: Long,
    val path: String,
    val addedAt: Long,
)

/** 收藏书架的数据访问：分组、条目以及去重写入。 */
@Single(binds = [BookShelfBackupDataSource::class])
class BookShelfRepository(
    private val bookShelfDao: BookShelfDao,
) : BookShelfBackupDataSource {

    val groups: Flow<List<BookShelfGroup>> = bookShelfDao.observeGroups()
        .map { list -> list.map { it.toDomain() } }

    fun observeItems(groupId: Long?): Flow<List<BookShelfEntry>> {
        val source = if (groupId == null) {
            bookShelfDao.observeItems()
        } else {
            bookShelfDao.observeItems(groupId = groupId)
        }
        return source.map { items ->
            items.map {
                BookShelfEntry(
                    id = it.id,
                    groupId = it.groupId,
                    path = it.path,
                    addedAt = it.addedAt,
                )
            }
        }
    }

    /**
     * 确保固定的「未分组」分组存在，返回其 id。
     * 升级兼容：旧版本把未归类的书都放进了第一个顶级分组，这里直接把它提升为「未分组」
     * （只改标记，不改名——UI 上固定显示本地化「未分组」），避免老用户多出一个空分组。
     */
    suspend fun ensureUngroupedGroup(name: String): Long {
        bookShelfDao.getUngroupedGroup()?.let { return it.id }
        val firstTopLevel = bookShelfDao.getGroups().firstOrNull { it.parentId == null }
        if (firstTopLevel != null) {
            bookShelfDao.updateGroups(
                listOf(
                    firstTopLevel.copy(
                        isUngrouped = true,
                        sortOrder = UNGROUPED_SORT_ORDER,
                    ),
                ),
            )
            return firstTopLevel.id
        }
        val safeName = name.trim().ifBlank { "Ungrouped" }
        return bookShelfDao.insertGroup(
            BookShelfGroupEntity(
                name = safeName,
                parentId = null,
                isUngrouped = true,
                sortOrder = UNGROUPED_SORT_ORDER,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 没有分组时落到「未分组」（它总是存在），未指定目标的书都进这里。 */
    suspend fun ensureDefaultGroup(name: String): Long = ensureUngroupedGroup(name)

    suspend fun createGroup(name: String, parentId: Long? = null): Long {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return -1L
        val siblings = bookShelfDao.getGroups()
            .filter { it.parentId == parentId && !it.isUngrouped }
        return bookShelfDao.insertGroup(
            BookShelfGroupEntity(
                name = trimmed,
                parentId = parentId,
                sortOrder = (siblings.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /** 在指定分组下新建子分组。 */
    suspend fun createSubGroup(parentId: Long, name: String): Long =
        createGroup(name = name, parentId = parentId)

    suspend fun getGroup(groupId: Long): BookShelfGroup? =
        bookShelfDao.getGroups().firstOrNull { it.id == groupId }?.toDomain()

    /** 批量加入分组，同组同路径的重复项由数据库唯一索引忽略；返回真正新增的数量。 */
    suspend fun addPaths(groupId: Long, paths: Collection<String>): Int {
        val distinctPaths = paths.filter { it.isNotBlank() }.distinct()
        if (distinctPaths.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val inserted = bookShelfDao.insertItems(
            distinctPaths.map { path ->
                BookShelfItemEntity(groupId = groupId, path = path, addedAt = now)
            },
        )
        return inserted.count { it != -1L }
    }

    suspend fun removeItems(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        bookShelfDao.deleteItems(ids = ids.toList())
    }

    suspend fun renameGroup(groupId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        bookShelfDao.updateGroupName(id = groupId, name = trimmed)
    }

    /**
     * 递归删除分组：该分组 + 所有子孙分组 + 这些分组的全部书籍记录。
     * 磁盘文件不受影响；「未分组」不可删除。
     */
    suspend fun deleteGroupRecursive(groupId: Long) {
        val all = bookShelfDao.getGroups()
        val root = all.firstOrNull { it.id == groupId } ?: return
        if (root.isUngrouped) return
        val ids = descendantIds(all, rootId = groupId, includeSelf = true).toList()
        if (ids.isEmpty()) return
        bookShelfDao.deleteItemsByGroups(groupIds = ids)
        bookShelfDao.deleteGroups(ids = ids)
    }

    /** 旧入口：递归删除（含子分组）。 */
    suspend fun deleteGroup(groupId: Long) = deleteGroupRecursive(groupId)

    /** 把分组挪到同级的第 [targetIndex] 个（从 0 开始），只在同父的兄弟间重排。 */
    suspend fun moveGroupTo(groupId: Long, targetIndex: Int) {
        val all = bookShelfDao.getGroups()
        val group = all.firstOrNull { it.id == groupId } ?: return
        if (group.isUngrouped) return
        val siblings = all
            .filter { it.parentId == group.parentId && !it.isUngrouped }
            .sortedBy { it.sortOrder }
            .toMutableList()
        val currentIndex = siblings.indexOfFirst { it.id == groupId }
        if (currentIndex == -1) return
        val moved = siblings.removeAt(currentIndex)
        val destination = targetIndex.coerceIn(0, siblings.size)
        if (destination == currentIndex) return
        siblings.add(destination, moved)
        bookShelfDao.updateGroups(
            siblings.mapIndexed { index, entity -> entity.copy(sortOrder = index) },
        )
    }

    /**
     * 把整个分组子树（自身 + 子孙分组 + 这些分组的书籍记录）移动到 [newParentId] 下，
     * 传 null 移到顶级。禁止移入自身或自身的子孙（避免循环），禁止移入「未分组」。
     * 返回是否成功。
     */
    suspend fun moveGroupSubtree(groupId: Long, newParentId: Long?): Boolean {
        val all = bookShelfDao.getGroups()
        val root = all.firstOrNull { it.id == groupId } ?: return false
        if (root.isUngrouped) return false
        if (newParentId != null) {
            if (newParentId in descendantIds(all, rootId = groupId, includeSelf = true)) {
                return false
            }
            val target = all.firstOrNull { it.id == newParentId } ?: return false
            if (target.isUngrouped) return false
        }
        bookShelfDao.updateGroupParent(id = groupId, parentId = newParentId)
        return true
    }

    /**
     * 复制整个分组子树到 [newParentId] 下（null=顶级）：新建同名分组结构，
     * 并把每个分组里的书籍记录复制过去（磁盘文件不动）。返回新根分组 id。
     */
    suspend fun copyGroupSubtree(groupId: Long, newParentId: Long?): Long? {
        val all = bookShelfDao.getGroups()
        if (all.none { it.id == groupId }) return null
        // 不能复制到自身或自身子孙下，也不能复制进「未分组」（它不允许有子分组）
        if (newParentId != null) {
            if (newParentId in descendantIds(all, rootId = groupId, includeSelf = true)) {
                return null
            }
            if (all.firstOrNull { it.id == newParentId }?.isUngrouped == true) {
                return null
            }
        }
        val items = bookShelfDao.getAllItems()
        val byParent = all.groupBy { it.parentId }
        // 自顶向下遍历整棵子树，保证父分组先于子分组创建
        val subtree = mutableListOf<BookShelfGroupEntity>()
        val queue = ArrayDeque<Long>().apply { add(groupId) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val entity = all.firstOrNull { it.id == current } ?: continue
            subtree += entity
            for (child in byParent[current].orEmpty()) queue.add(child.id)
        }
        val oldToNew = mutableMapOf<Long, Long>()
        for (entity in subtree) {
            val mappedParent = if (entity.id == groupId) {
                newParentId
            } else {
                oldToNew[entity.parentId]
            }
            val newId = bookShelfDao.insertGroup(
                BookShelfGroupEntity(
                    name = entity.name,
                    parentId = mappedParent,
                    isUngrouped = false,
                    sortOrder = entity.sortOrder,
                    createdAt = entity.createdAt,
                ),
            )
            oldToNew[entity.id] = newId
            // 复制该分组直接的书籍记录
            val paths = items.filter { it.groupId == entity.id }.map { it.path }
            if (paths.isNotEmpty()) addPaths(groupId = newId, paths = paths)
        }
        return oldToNew[groupId]
    }

    /**
     * 文件改名后同步书架：所有分组里同一路径的记录都换成新路径，
     * 保证每本书在各分组下显示的书名一致。
     */
    suspend fun renamePath(oldPath: String, newPath: String) {
        if (oldPath == newPath) return
        bookShelfDao.updateItemPath(oldPath = oldPath, newPath = newPath)
        // 目标分组已存在同路径时 UPDATE OR IGNORE 会跳过，清掉旧路径残留记录
        bookShelfDao.deleteItemsByPath(path = oldPath)
    }

    /** 移动条目到其他分组：已在目标分组里的记录保持不动，只搬其余的。 */
    suspend fun moveItems(ids: Collection<Long>, targetGroupId: Long) {
        if (ids.isEmpty()) return
        val items = bookShelfDao.getItems(ids.toList()).filter { it.groupId != targetGroupId }
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        bookShelfDao.insertItems(
            items.map { item ->
                BookShelfItemEntity(groupId = targetGroupId, path = item.path, addedAt = now)
            },
        )
        bookShelfDao.deleteItems(ids = items.map { it.id })
    }

    /** 复制条目到其他分组：只复制书架记录，磁盘文件不动。 */
    suspend fun copyItems(ids: Collection<Long>, targetGroupId: Long) {
        if (ids.isEmpty()) return
        val paths = bookShelfDao.getItems(ids.toList()).map { it.path }
        addPaths(groupId = targetGroupId, paths = paths)
    }

    override suspend fun exportBookShelf(): BookShelfBackupData {
        val groups = bookShelfDao.getGroups()
        val idToEntity = groups.associateBy { it.id }
        // 父级链：从根到父的名称序列，用于跨设备按名+层级匹配
        fun parentChain(id: Long?): List<String> {
            val chain = mutableListOf<String>()
            var current = id
            while (current != null) {
                val g = idToEntity[current] ?: break
                chain.add(g.name)
                current = g.parentId
            }
            return chain.asReversed()
        }
        val items = bookShelfDao.getAllItems().mapNotNull { item ->
            val group = idToEntity[item.groupId] ?: return@mapNotNull null
            BookShelfItemBackup(
                groupName = group.name,
                groupParentChain = parentChain(group.parentId),
                path = item.path,
                addedAt = item.addedAt,
            )
        }
        return BookShelfBackupData(
            groups = groups.map {
                BookShelfGroupBackup(
                    name = it.name,
                    parentChain = parentChain(it.parentId),
                    sortOrder = it.sortOrder,
                    createdAt = it.createdAt,
                    isUngrouped = it.isUngrouped,
                )
            },
            items = items,
        )
    }

    override suspend fun importBookShelf(data: BookShelfBackupData) {
        val existing = bookShelfDao.getGroups()
        // 按「父级链 + 分组名」唯一定位已有分组，区分不同父下的同名分组
        val keyToId = existing.associate { group ->
            (parentChainKey(existing, group.parentId) to group.name) to group.id
        }.toMutableMap()
        val localUngroupedId = existing.firstOrNull { it.isUngrouped }?.id
        // 按层级深度升序：父分组先于子分组被匹配/创建
        val sortedGroups = data.groups.sortedBy { it.parentChain.size }
        for (group in sortedGroups) {
            val key = group.parentChain to group.name
            if (group.isUngrouped) {
                // 「未分组」只复用本地的，不重复创建
                localUngroupedId?.let { keyToId[key] = it }
                continue
            }
            if (key in keyToId) continue
            val parentId: Long? = if (group.parentChain.isEmpty()) {
                null
            } else {
                val parentName = group.parentChain.last()
                val parentKey = group.parentChain.dropLast(1) to parentName
                keyToId[parentKey] // 找不到父级则落为顶级，避免丢数据
            }
            keyToId[key] = bookShelfDao.insertGroup(
                BookShelfGroupEntity(
                    name = group.name,
                    parentId = parentId,
                    sortOrder = group.sortOrder,
                    createdAt = group.createdAt,
                ),
            )
        }
        val items = data.items.mapNotNull { item ->
            val groupId = keyToId[item.groupParentChain to item.groupName] ?: return@mapNotNull null
            BookShelfItemEntity(groupId = groupId, path = item.path, addedAt = item.addedAt)
        }
        if (items.isNotEmpty()) bookShelfDao.insertItems(items)
    }

    /**
     * 按「父级链 + 分组名」批量确保分组存在：缺失的整个分组、或分组里缺失的子分组，
     * 都会按层级自动创建（祖先先于子孙）；已存在的复用，不同父下的同名分组互不干扰。
     * 返回每条请求路径对应的分组 id。
     */
    suspend fun ensureGroupsByPaths(
        paths: Collection<Pair<List<String>, String>>,
    ): Map<Pair<List<String>, String>, Long> {
        val existing = bookShelfDao.getGroups()
        val keyToId = existing.associate { group ->
            (parentChainKey(existing, group.parentId) to group.name) to group.id
        }.toMutableMap()
        // 把每条路径的祖先前缀也纳入，保证缺失的父分组一并创建；按深度升序处理
        val allKeys = LinkedHashSet<Pair<List<String>, String>>()
        for ((chain, name) in paths) {
            var curChain = chain
            var curName = name
            while (true) {
                allKeys += curChain to curName
                if (curChain.isEmpty()) break
                curName = curChain.last()
                curChain = curChain.dropLast(1)
            }
        }
        for ((chain, name) in allKeys.sortedBy { it.first.size }) {
            val key = chain to name
            if (key in keyToId) continue
            val parentId: Long? = if (chain.isEmpty()) {
                null
            } else {
                keyToId[chain.dropLast(1) to chain.last()]
            }
            keyToId[key] = bookShelfDao.insertGroup(
                BookShelfGroupEntity(
                    name = name,
                    parentId = parentId,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
        return paths.associateWith { keyToId[it]!! }
    }

    private fun parentChainKey(all: List<BookShelfGroupEntity>, parentId: Long?): List<String> {
        val byId = all.associateBy { it.id }
        val chain = mutableListOf<String>()
        var current = parentId
        while (current != null) {
            val g = byId[current] ?: break
            chain.add(g.name)
            current = g.parentId
        }
        return chain.asReversed()
    }

    private fun BookShelfGroupEntity.toDomain() = BookShelfGroup(
        id = id,
        name = name,
        parentId = parentId,
        isUngrouped = isUngrouped,
        sortOrder = sortOrder,
    )

    private fun descendantIds(
        all: List<BookShelfGroupEntity>,
        rootId: Long,
        includeSelf: Boolean,
    ): Set<Long> {
        val byParent = all.groupBy { it.parentId }
        val result = mutableSetOf<Long>()
        if (includeSelf) result += rootId
        val queue = ArrayDeque<Long>().apply { add(rootId) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (child in byParent[current].orEmpty()) {
                if (result.add(child.id)) queue.add(child.id)
            }
        }
        return result
    }

    companion object {
        /** 「未分组」的排序值，保证永远排在最前。 */
        const val UNGROUPED_SORT_ORDER = -1000
    }
}
