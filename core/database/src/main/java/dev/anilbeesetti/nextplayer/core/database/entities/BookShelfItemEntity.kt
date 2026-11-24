package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 收藏书架的条目：只记录文件绝对路径与分组，删除条目不会动磁盘文件。
 * 同一分组下同一个 path 只保留一条。
 */
@Entity(
    tableName = "book_shelf_item",
    foreignKeys = [
        ForeignKey(
            entity = BookShelfGroupEntity::class,
            parentColumns = ["id"],
            childColumns = ["group_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("path"),
        Index(
            value = ["group_id", "path"],
            unique = true,
        ),
    ],
)
data class BookShelfItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "group_id")
    val groupId: Long,
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "added_at")
    val addedAt: Long,
)
