package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ApplicationPreferences(
    val sortBy: Sort.By = Sort.By.TITLE,
    val sortOrder: Sort.Order = Sort.Order.ASCENDING,
    val themeConfig: ThemeConfig = ThemeConfig.SYSTEM,
    val useHighContrastDarkTheme: Boolean = false,
    val useDynamicColors: Boolean = true,
    /** 首页底栏按钮顺序，保存目标枚举名称；旧数据的空列表回退到默认顺序。 */
    val topLevelDestinationOrder: List<String> = emptyList(),
    /** 首页底栏中被隐藏的按钮（存目标枚举名称，不含「更多」）；默认空 = 全部显示。 */
    val hiddenTopLevelDestinations: List<String> = emptyList(),
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

    // Books tab
    /** 书籍库白名单目录（只扫描这些文件夹下的 txt/epub）。 */
    val bookFolders: List<String> = emptyList(),
    /** 浏览 tab 的显示模式/布局/排序（与书架 tab 分开记忆）。 */
    val bookViewMode: MediaViewMode = MediaViewMode.FOLDERS,
    val bookFolderLayoutMode: MediaLayoutMode = MediaLayoutMode.GRID,
    val bookLayoutMode: ImageLayoutMode = ImageLayoutMode.GRID,
    val bookSortBy: Sort.By = Sort.By.TITLE,
    val bookSortOrder: Sort.Order = Sort.Order.ASCENDING,
    /** 浏览 tab 的缩略图开关（独立字段，不写全局 showThumbnailField，避免关掉视频/图片/漫画的缩略图）。 */
    val bookShowThumbnailField: Boolean = true,
    /** 浏览 tab 的字段显示开关（独立字段，不写全局字段，避免影响视频/图片/漫画库）。 */
    val bookShowSizeField: Boolean = false,
    val bookShowExtensionField: Boolean = false,
    val bookShowResolutionField: Boolean = false,
    /** 书架 tab 的分组文件夹布局（列表/网格，与浏览 tab 分开记忆）。 */
    val shelfGroupLayoutMode: MediaLayoutMode = MediaLayoutMode.LIST,
    /** 书架 tab 的布局/排序（与浏览 tab 分开记忆）。 */
    val shelfLayoutMode: ImageLayoutMode = ImageLayoutMode.NINE_GRID,
    val shelfSortBy: Sort.By = Sort.By.TITLE,
    val shelfSortOrder: Sort.Order = Sort.Order.ASCENDING,
    /** 书架 tab 的字段显示开关（独立于浏览 tab）。 */
    val shelfShowSizeField: Boolean = false,
    val shelfShowExtensionField: Boolean = false,
    val shelfShowPlayedProgressField: Boolean = true,
    val shelfShowThumbnailField: Boolean = true,
    /** 最后一次阅读的书籍路径，用于底部按钮「继续阅读」。 */
    val lastViewedBookPath: String? = null,
    /** 每本书的最近阅读时间戳（System.currentTimeMillis），用于书架按最近阅读排序。 */
    val bookLastViewedAt: Map<String, Long> = emptyMap(),
    /** 每本书的书签列表，key = 书籍绝对路径。 */
    val bookBookmarks: Map<String, List<Bookmark>> = emptyMap(),
    /** 每本书的阅读器全文搜索历史，key = 书籍绝对路径，列表按最近使用排序（最新在前）。 */
    val bookSearchHistory: Map<String, List<String>> = emptyMap(),
    /**
     * 外部备份同步来的待定位进度，key = 书籍绝对路径，value = 0..1 百分比。
     * 首次打开对应书时按百分比换算位置并移除该条目；定位失败（内容缺失）则保留待下次重试。
     */
    val bookPendingPercent: Map<String, Float> = emptyMap(),
    /** 书籍页顶部 tab（书架/浏览）的顺序，可在 tab 上长按拖动调整。 */
    val bookLibraryTabOrder: List<BookLibraryTab> = BookLibraryTab.defaultOrder,
    /**
     * 用户自建的 TXT 分章规则（JSON 字符串，List<ChapterRule> 序列化结果）。
     * 存成字符串是为了让设置导入/导出能无损带上它（备份合并器只透传基本类型字段）。
     * 空字符串表示没有自建规则。
     */
    val bookChapterRules: String = "",
    /** 默认分章规则 id（可指向内置 builtin_2 或自建规则）；null 表示未设置。 */
    val bookDefaultChapterRuleId: String? = null,
    /** 是否已播种过初始自建规则（避免用户删除后又被自动加回）。 */
    val bookChapterRulesSeeded: Boolean = false,
    /**
     * 是否显示书架导入相关入口：默认隐藏，关于页连点应用名 5 下后永久开启。
     * 同时控制常规设置里的「导入书架（临时）」和书架顶栏的 txt 清单导入按钮。
     */
    val showLegacyShelfImport: Boolean = false,

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
    /** 书籍看完阈值（百分比，0-100）。默认 95，范围 50-100。 */
    val bookWatchedThreshold: Int = 95,

    // Thumbnail generation
    val thumbnailGenerationStrategy: ThumbnailGenerationStrategy =
        ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE,
    val thumbnailFramePosition: Float = DEFAULT_THUMBNAIL_FRAME_POSITION,
) {

    companion object {
        const val DEFAULT_THUMBNAIL_FRAME_POSITION = 0.33f
    }
}
