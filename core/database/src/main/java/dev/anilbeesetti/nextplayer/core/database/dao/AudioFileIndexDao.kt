package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.AudioFileIndexEntity

@Dao
interface AudioFileIndexDao {

    @Query("SELECT * FROM audio_file_index")
    suspend fun getAll(): List<AudioFileIndexEntity>

    @Upsert
    suspend fun upsertAll(entries: List<AudioFileIndexEntity>)

    @Query("DELETE FROM audio_file_index WHERE path IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("DELETE FROM audio_file_index")
    suspend fun clear()
}
