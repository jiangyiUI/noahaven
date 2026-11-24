package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 收藏书架的分组，只用于组织书架条目，与磁盘目录无关。 */
@Entity(tableName = "book_shelf_group")
data class BookShelfGroupEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "name")
    val name: String,
    /** 父分组 id，null 表示一级分组。 */
    @ColumnInfo(name = "parent_id")
    val parentId: Long? = null,
    /** 固定的「未分组」分组标记，不可删除/重命名/加子分组/移动。 */
    @ColumnInfo(name = "is_ungrouped", defaultValue = "0")
    val isUngrouped: Boolean = false,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)
