package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.ComicFileIndexEntity

@Dao
interface ComicFileIndexDao {

    @Query("SELECT * FROM comic_file_index")
    suspend fun getAll(): List<ComicFileIndexEntity>

    @Query("SELECT * FROM comic_file_index WHERE whitelist_root IN (:roots)")
    suspend fun getByRoots(roots: List<String>): List<ComicFileIndexEntity>

    @Upsert
    suspend fun upsertAll(entries: List<ComicFileIndexEntity>)

    @Query("DELETE FROM comic_file_index WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM comic_file_index WHERE whitelist_root IN (:roots)")
    suspend fun deleteByRoots(roots: List<String>)

    @Query("DELETE FROM comic_file_index")
    suspend fun clear()
}
