package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.ImageFileIndexEntity

@Dao
interface ImageFileIndexDao {

    @Query("SELECT * FROM image_file_index")
    suspend fun getAll(): List<ImageFileIndexEntity>

    @Upsert
    suspend fun upsertAll(entries: List<ImageFileIndexEntity>)

    @Query("DELETE FROM image_file_index WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM image_file_index")
    suspend fun clear()
}
