package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.MediaFileIndexEntity

@Dao
interface MediaFileIndexDao {

    @Query("SELECT * FROM media_file_index")
    suspend fun getAll(): List<MediaFileIndexEntity>

    @Upsert
    suspend fun upsertAll(entries: List<MediaFileIndexEntity>)

    @Query("DELETE FROM media_file_index WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM media_file_index")
    suspend fun clear()
}
