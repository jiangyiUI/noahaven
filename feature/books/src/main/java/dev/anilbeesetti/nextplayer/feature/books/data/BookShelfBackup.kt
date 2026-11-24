package dev.anilbeesetti.nextplayer.feature.books.data

import kotlinx.serialization.Serializable

/**
 * 书架备份数据。
 *
 * 分组用 [name] 关联而不是 id：id 是自增主键，跨设备/恢复时会变。
 * core/database 模块没有 kotlinx-serialization 插件，所以备份模型放在 books 模块，
 * 由 Repository 负责与 Room entity 互转。
 */
@Serializable
data class BookShelfGroupBackup(
    val name: String,
    val sortOrder: Int,
    val createdAt: Long,
)

@Serializable
data class BookShelfItemBackup(
    val groupName: String,
    val path: String,
    val addedAt: Long,
)

@Serializable
data class BookShelfBackupData(
    val groups: List<BookShelfGroupBackup> = emptyList(),
    val items: List<BookShelfItemBackup> = emptyList(),
)

/**
 * 书架备份数据源。settings 模块通过此接口导出/导入书架数据，
 * 避免直接依赖 Room 实现，方便测试注入 fake。
 */
interface BookShelfBackupDataSource {
    suspend fun exportBookShelf(): BookShelfBackupData
    suspend fun importBookShelf(data: BookShelfBackupData)
}
