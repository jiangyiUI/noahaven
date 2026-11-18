package dev.anilbeesetti.nextplayer.core.media.index

import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 漫画压缩包白名单索引接口。比图片简单：内部图片列表随 zip 一次扫描读入并缓存。 */
interface ComicFileIndex {
    val entries: StateFlow<List<ComicFile>?>

    fun requestRefresh(
        whitelistRoots: Collection<String>,
        folderPath: String?,
        force: Boolean = false,
    )

    suspend fun find(path: String): ComicFile?

    /** 读取单本漫画的阅读进度；没有记录时返回 null。 */
    suspend fun getProgress(path: String): ComicProgress?

    /** 保存单本漫画的阅读进度（页码 + 翻页方向）。 */
    suspend fun saveProgress(progress: ComicProgress)

    /** 所有漫画的阅读进度，按最近阅读时间倒序。 */
    fun observeProgress(): Flow<List<ComicProgress>>

    /** 删除单本漫画的阅读进度。 */
    suspend fun deleteProgress(path: String)

    /** 压缩包重命名/移动后，把阅读进度挪到新路径。 */
    suspend fun moveProgress(fromPath: String, toPath: String)

    /** 清空全部漫画阅读进度。 */
    suspend fun clearProgress()

    /** 读取已保存的解压/解密密码；没有记录时返回 null。 */
    suspend fun getPassword(path: String): String?

    /** 保存密码（打开成功后调用）。 */
    suspend fun savePassword(path: String, password: String)

    /** 删除单本漫画的密码记录。 */
    suspend fun deletePassword(path: String)

    /** 重命名/移动后，把密码记录挪到新路径。 */
    suspend fun movePassword(fromPath: String, toPath: String)
}
