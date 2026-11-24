package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM book ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM book")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM book WHERE path = :path")
    suspend fun get(path: String): BookEntity?

    @Upsert
    suspend fun upsertAll(entities: List<BookEntity>)

    @Query("DELETE FROM book WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM book WHERE whitelist_root IN (:roots)")
    suspend fun deleteByRoots(roots: List<String>)

    @Query("DELETE FROM book")
    suspend fun clear()

    @Query(
        """
        UPDATE book SET
            chapter_index = :chapterIndex,
            char_offset = :charOffset,
            percent = :percent,
            last_opened_at = :openedAt,
            finished = :finished
        WHERE path = :path
        """,
    )
    suspend fun updateProgress(
        path: String,
        chapterIndex: Int,
        charOffset: Int,
        percent: Float,
        openedAt: Long,
        finished: Boolean,
    )

    @Query("UPDATE book SET last_opened_at = :openedAt WHERE path = :path")
    suspend fun touch(path: String, openedAt: Long)

    @Query("UPDATE book SET finished = :finished WHERE path = :path")
    suspend fun setFinished(path: String, finished: Boolean)
}
