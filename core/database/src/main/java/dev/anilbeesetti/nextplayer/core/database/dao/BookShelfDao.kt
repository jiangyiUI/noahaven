package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.anilbeesetti.nextplayer.core.database.entities.BookShelfGroupEntity
import dev.anilbeesetti.nextplayer.core.database.entities.BookShelfItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookShelfDao {

    @Query("SELECT * FROM book_shelf_group ORDER BY sort_order, created_at, id")
    fun observeGroups(): Flow<List<BookShelfGroupEntity>>

    @Query("SELECT * FROM book_shelf_group ORDER BY sort_order, created_at, id")
    suspend fun getGroups(): List<BookShelfGroupEntity>

    @Query("SELECT * FROM book_shelf_item ORDER BY added_at, id")
    fun observeItems(): Flow<List<BookShelfItemEntity>>

    @Query("SELECT * FROM book_shelf_item WHERE group_id = :groupId ORDER BY added_at, id")
    fun observeItems(groupId: Long): Flow<List<BookShelfItemEntity>>

    @Query("SELECT * FROM book_shelf_item WHERE id IN (:ids)")
    suspend fun getItems(ids: List<Long>): List<BookShelfItemEntity>

    @Query("SELECT * FROM book_shelf_item ORDER BY added_at, id")
    suspend fun getAllItems(): List<BookShelfItemEntity>

    @Insert
    suspend fun insertGroup(group: BookShelfGroupEntity): Long

    @Query("UPDATE book_shelf_group SET name = :name WHERE id = :id")
    suspend fun updateGroupName(id: Long, name: String)

    @Query("DELETE FROM book_shelf_group WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Update
    suspend fun updateGroups(groups: List<BookShelfGroupEntity>)

    @Query("SELECT * FROM book_shelf_group WHERE is_ungrouped = 1 LIMIT 1")
    suspend fun getUngroupedGroup(): BookShelfGroupEntity?

    @Query("UPDATE book_shelf_group SET parent_id = :parentId WHERE id = :id")
    suspend fun updateGroupParent(id: Long, parentId: Long?)

    @Query("DELETE FROM book_shelf_group WHERE id IN (:ids)")
    suspend fun deleteGroups(ids: List<Long>)

    @Query("DELETE FROM book_shelf_item WHERE group_id IN (:groupIds)")
    suspend fun deleteItemsByGroups(groupIds: List<Long>)

    /** 同组同路径重复时直接忽略，返回值中 -1 表示已存在。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItems(items: List<BookShelfItemEntity>): List<Long>

    @Query("DELETE FROM book_shelf_item WHERE id = :id")
    suspend fun deleteItem(id: Long)

    @Query("DELETE FROM book_shelf_item WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    @Query("DELETE FROM book_shelf_item WHERE group_id = :groupId")
    suspend fun deleteItemsByGroup(groupId: Long)

    /** 文件改名后同步书架记录；目标分组已有同路径时保留已有记录。 */
    @Query("UPDATE OR IGNORE book_shelf_item SET path = :newPath WHERE path = :oldPath")
    suspend fun updateItemPath(oldPath: String, newPath: String)

    @Query("DELETE FROM book_shelf_item WHERE path = :path")
    suspend fun deleteItemsByPath(path: String)

    @Query("SELECT COUNT(*) FROM book_shelf_item")
    suspend fun countAll(): Int
}
