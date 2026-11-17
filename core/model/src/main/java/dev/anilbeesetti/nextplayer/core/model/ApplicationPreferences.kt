package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ApplicationPreferences(
    val sortBy: Sort.By = Sort.By.TITLE,
    val sortOrder: Sort.Order = Sort.Order.ASCENDING,
    val themeConfig: ThemeConfig = ThemeConfig.SYSTEM,
    val useHighContrastDarkTheme: Boolean = false,
    val useDynamicColors: Boolean = true,
    val markLastPlayedMedia: Boolean = true,
    val isHistoryPaused: Boolean = false,
    val includeNetworkWatchHistory: Boolean = true,
    val excludeFolders: List<String> = emptyList(),
    /** 音乐页要排除的音频文件夹（与视频的 excludeFolders 分开记忆） */
    val excludeAudioFolders: List<String> = emptyList(),
    /** 图片页只扫描这些白名单目录，默认递归包含子目录。 */
    val imageFolders: List<String> = emptyList(),
    /** 扫描时忽略文件夹里的 .nomedia 标记（仍然扫描被隐藏的文件夹） */
    val ignoreNoMedia: Boolean = false,
    val mediaViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    val mediaLayoutMode: MediaLayoutMode = MediaLayoutMode.LIST,

    // Network browser
    val networkSortBy: Sort.By = Sort.By.TITLE,
    val networkSortOrder: Sort.Order = Sort.Order.ASCENDING,
    val networkMediaLayoutMode: MediaLayoutMode = MediaLayoutMode.LIST,

    // Music tab (与视频页分别记忆)
    val musicSortBy: Sort.By = Sort.By.TITLE,
    val musicSortOrder: Sort.Order = Sort.Order.ASCENDING,
    val musicViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    val musicLayoutMode: MediaLayoutMode = MediaLayoutMode.LIST,

    // Images tab
    val imageSortBy: Sort.By = Sort.By.TITLE,
    val imageSortOrder: Sort.Order = Sort.Order.ASCENDING,
    val imageViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    /** 图片库中文件夹的布局：列表 / 网格 */
    val imageFolderLayoutMode: MediaLayoutMode = MediaLayoutMode.GRID,
    /** 图片库中图片的布局：列表 / 网格 / 九宫格 */
    val imageLayoutMode: ImageLayoutMode = ImageLayoutMode.GRID,
    /** 最后一次在查看器中浏览的图片路径，用于底部按钮「继续查看」 */
    val lastViewedImagePath: String? = null,

    // Fields
    val showDurationField: Boolean = true,
    val showFolderDurationField: Boolean = true,
    val showExtensionField: Boolean = false,
    val showPathField: Boolean = true,
    val showResolutionField: Boolean = false,
    val showSizeField: Boolean = false,
    val showThumbnailField: Boolean = true,
    val showPlayedProgress: Boolean = true,

    val newVideoThresholdDays: Int = 7,

    // Thumbnail generation
    val thumbnailGenerationStrategy: ThumbnailGenerationStrategy =
        ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE,
    val thumbnailFramePosition: Float = DEFAULT_THUMBNAIL_FRAME_POSITION,
) {

    companion object {
        const val DEFAULT_THUMBNAIL_FRAME_POSITION = 0.33f
    }
}
