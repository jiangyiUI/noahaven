package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.ComicProgressEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ComicProgressDao {

    @Query("SELECT * FROM comic_progress WHERE path = :path")
    suspend fun get(path: String): ComicProgressEntity?

    /** 压缩包重命名/移动后，把整条阅读进度搬到新路径。 */
    @Transaction
    suspend fun move(fromPath: String, toPath: String) {
        if (fromPath == toPath) return
        val entity = get(path = fromPath) ?: return
        upsert(entity = entity.copy(path = toPath))
        delete(path = fromPath)
    }

    /** 全部阅读进度，按最近阅读时间倒序（「更多」页历史记录用）。 */
    @Query("SELECT * FROM comic_progress ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<ComicProgressEntity>>

    @Query("SELECT * FROM comic_progress")
    suspend fun getAll(): List<ComicProgressEntity>

    @Upsert
    suspend fun upsert(entity: ComicProgressEntity)

    @Query("DELETE FROM comic_progress WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM comic_progress")
    suspend fun clear()
}
