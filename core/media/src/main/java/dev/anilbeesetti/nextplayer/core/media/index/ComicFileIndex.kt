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

    /** 立即刷新并挂起直到本次扫描完成：下拉刷新用，不受自动刷新的节流间隔限制。 */
    suspend fun refreshNow(whitelistRoots: Collection<String>, folderPath: String?)

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

    /**
     * 打开前解析这本漫画可用的密码，找不到时返回 null（由 UI 弹密码框）：
     * 1. 路径直接命中已保存的记录；
     * 2. 文件在 App 外被移动/改名：按文件指纹认出同一文件，把密码记录迁到新路径；
     * 3. 复用其他加密漫画的密码逐个试（很多压缩包用的是同一个密码），试对就记住。
     */
    suspend fun resolvePassword(path: String): String?

    /** 保存密码（打开成功后调用）。 */
    suspend fun savePassword(path: String, password: String)

    /** 删除单本漫画的密码记录。 */
    suspend fun deletePassword(path: String)

    /** 重命名/移动后，把密码记录挪到新路径。 */
    suspend fun movePassword(fromPath: String, toPath: String)
}
