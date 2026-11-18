package dev.anilbeesetti.nextplayer.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 加密漫画（zip/cbz/pdf）已保存的密码。
 *
 * 独立成表的原因：漫画索引在每次扫描时会整条 upsert，密码若放在索引表里会被扫描结果覆盖。
 */
@Entity(tableName = "comic_password")
data class ComicPasswordEntity(
    @PrimaryKey
    @ColumnInfo(name = "path")
    val path: String,
    @ColumnInfo(name = "password")
    val password: String,
)
