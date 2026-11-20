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

    // Comics tab
    /** 漫画库白名单目录（只扫描这些文件夹下的 zip/cbz）。 */
    val comicFolders: List<String> = emptyList(),
    /** 漫画查看器默认翻页方向：左→右（欧美）或右→左（日漫）。 */
    val comicReadingDirection: ComicReadingDirection = ComicReadingDirection.RIGHT_TO_LEFT,
    /** 最后一次在查看器中浏览的漫画文件路径，用于底部按钮「继续阅读」。 */
    val lastViewedComicPath: String? = null,
    /** 图片页顶部 tab 的顺序，可在 tab 上长按拖动调整。 */
    val imageLibraryTabOrder: List<MediaLibraryTab> = MediaLibraryTab.defaultOrder,
    /** 漫画库的显示模式/布局/排序（与图片库分开记忆）。 */
    val comicViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    val comicFolderLayoutMode: MediaLayoutMode = MediaLayoutMode.GRID,
    val comicLayoutMode: ImageLayoutMode = ImageLayoutMode.GRID,
    val comicSortBy: Sort.By = Sort.By.TITLE,
    val comicSortOrder: Sort.Order = Sort.Order.ASCENDING,

    // Update
    /** 启动时自动检查更新开关。 */
    val autoCheckUpdate: Boolean = true,
    /** 已手动忽略的版本号（tagName），自动检测到相同版本时静默跳过。 */
    val ignoredUpdateVersion: String? = null,

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

    /** 看完标记总开关：开启后视频/漫画达到各自阈值会自动打上标记。 */
    val enableWatchedMarks: Boolean = true,
    /** 视频看完阈值（百分比，0-100）。默认 90，范围 50-100。 */
    val videoWatchedThreshold: Int = 90,
    /** 漫画看完阈值（百分比，0-100）。默认 95，范围 50-100。 */
    val comicWatchedThreshold: Int = 95,

    // Thumbnail generation
    val thumbnailGenerationStrategy: ThumbnailGenerationStrategy =
        ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE,
    val thumbnailFramePosition: Float = DEFAULT_THUMBNAIL_FRAME_POSITION,
) {

    companion object {
        const val DEFAULT_THUMBNAIL_FRAME_POSITION = 0.33f
    }
}
