package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.ComicPasswordEntity

/** 加密漫画密码表。按文件路径存取，重命名/移动时整条搬走。 */
@Dao
interface ComicPasswordDao {

    @Query("SELECT password FROM comic_password WHERE path = :path")
    suspend fun get(path: String): String?

    @Query("SELECT * FROM comic_password WHERE path = :path")
    suspend fun getEntity(path: String): ComicPasswordEntity?

    @Query("SELECT * FROM comic_password")
    suspend fun getAll(): List<ComicPasswordEntity>

    @Upsert
    suspend fun upsert(entity: ComicPasswordEntity)

    @Query("DELETE FROM comic_password WHERE path = :path")
    suspend fun delete(path: String)

    @Query("DELETE FROM comic_password WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    /** 重命名/移动后，把密码记录搬到新路径。 */
    @Transaction
    suspend fun move(fromPath: String, toPath: String) {
        if (fromPath == toPath) return
        val entity = getEntity(path = fromPath) ?: return
        upsert(entity = entity.copy(path = toPath))
        delete(path = fromPath)
    }
}
