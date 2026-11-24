package dev.anilbeesetti.nextplayer.feature.books.data

import dev.anilbeesetti.nextplayer.core.database.dao.BookShelfDao
import dev.anilbeesetti.nextplayer.core.database.entities.BookShelfGroupEntity
import dev.anilbeesetti.nextplayer.core.database.entities.BookShelfItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

/** 书架分组，UI 只关心 id 和名字。 */
data class BookShelfGroup(
    val id: Long,
    val name: String,
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
        .map { groups -> groups.map { BookShelfGroup(id = it.id, name = it.name) } }

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

    /** 没有分组时新建一个默认分组，已有分组则直接复用第一个。 */
    suspend fun ensureDefaultGroup(name: String): Long {
        bookShelfDao.getGroups().firstOrNull()?.let { return it.id }
        return createGroup(name = name)
    }

    suspend fun createGroup(name: String): Long {
        val groups = bookShelfDao.getGroups()
        return bookShelfDao.insertGroup(
            BookShelfGroupEntity(
                name = name.trim(),
                sortOrder = (groups.maxOfOrNull { it.sortOrder } ?: -1) + 1,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

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

    /** 删除分组：外键 CASCADE 会一并清掉组内记录，磁盘文件不受影响。 */
    suspend fun deleteGroup(groupId: Long) {
        bookShelfDao.deleteGroup(id = groupId)
    }

    /** 把分组挪到这一行的第 [targetIndex] 个（从 0 开始），其余分组顺序整体重排。 */
    suspend fun moveGroupTo(groupId: Long, targetIndex: Int) {
        val groups = bookShelfDao.getGroups().toMutableList()
        val currentIndex = groups.indexOfFirst { it.id == groupId }
        if (currentIndex == -1) return
        val group = groups.removeAt(currentIndex)
        val destination = targetIndex.coerceIn(0, groups.size)
        if (destination == currentIndex) return
        groups.add(destination, group)
        bookShelfDao.updateGroups(
            groups.mapIndexed { index, entity ->
                entity.copy(sortOrder = index)
            },
        )
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
        val idToName = groups.associate { it.id to it.name }
        val items = bookShelfDao.getAllItems().mapNotNull { item ->
            BookShelfItemBackup(
                groupName = idToName[item.groupId] ?: return@mapNotNull null,
                path = item.path,
                addedAt = item.addedAt,
            )
        }
        return BookShelfBackupData(
            groups = groups.map {
                BookShelfGroupBackup(name = it.name, sortOrder = it.sortOrder, createdAt = it.createdAt)
            },
            items = items,
        )
    }

    override suspend fun importBookShelf(data: BookShelfBackupData) {
        val existing = bookShelfDao.getGroups().associateBy { it.name }
        val nameToId = mutableMapOf<String, Long>()
        for (group in data.groups) {
            nameToId[group.name] = existing[group.name]?.id
                ?: bookShelfDao.insertGroup(
                    BookShelfGroupEntity(
                        name = group.name,
                        sortOrder = group.sortOrder,
                        createdAt = group.createdAt,
                    ),
                )
        }
        val items = data.items.mapNotNull { item ->
            val groupId = nameToId[item.groupName] ?: return@mapNotNull null
            BookShelfItemEntity(groupId = groupId, path = item.path, addedAt = item.addedAt)
        }
        if (items.isNotEmpty()) bookShelfDao.insertItems(items)
    }
}
