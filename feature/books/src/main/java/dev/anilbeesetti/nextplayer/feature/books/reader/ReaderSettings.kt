package dev.anilbeesetti.nextplayer.feature.books.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * 阅读页 5 套皮肤，色值全部照抄 shuangdu 的 SkinPalette，不跟随应用主题。
 * 阅读区用 readBg/readText/readSub，设置面板用 surface/text/surfaceVariant/primary 一组。
 */
enum class ReaderTheme(
    val storageKey: String,
    val labelRes: Int,
    /** 阅读页背景（readBg）。 */
    val background: Color,
    /** 阅读页正文（readText）。 */
    val text: Color,
    /** 阅读页次要文字（readSub）。 */
    val secondaryText: Color,
    /** 强调色，等同 primary。 */
    val accent: Color,
    /** 设置面板底色（surface）。 */
    val surface: Color,
    /** 设置面板次级底色，用于滑轨与未选中 chip（surfaceVariant）。 */
    val surfaceVariant: Color,
    /** 设置面板正文（text）。 */
    val panelText: Color,
    /** 设置面板次要文字，用于分组标题（sub）。 */
    val panelSub: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val outline: Color,
    val isDark: Boolean,
) {
    PAPER(
        storageKey = "paper",
        labelRes = R.string.reader_theme_paper,
        background = Color(0xFFF6EEDD),
        text = Color(0xFF241F19),
        secondaryText = Color(0xFF6E6252),
        accent = Color(0xFF2A6459),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEFEAE0),
        panelText = Color(0xFF191A17),
        panelSub = Color(0xFF57534B),
        onAccent = Color(0xFFFFFFFF),
        accentContainer = Color(0xFFE1EEE8),
        onAccentContainer = Color(0xFF10332C),
        outline = Color(0xFFD9D2C4),
        isDark = false,
    ),
    INK_GREEN(
        storageKey = "ink_green",
        labelRes = R.string.reader_theme_ink_green,
        background = Color(0xFF27301F),
        text = Color(0xFFDFE5D3),
        secondaryText = Color(0xFFB9C4A8),
        accent = Color(0xFF86B06A),
        surface = Color(0xFF1C2219),
        surfaceVariant = Color(0xFF222A1E),
        panelText = Color(0xFFE6EADF),
        panelSub = Color(0xFFB4BDA9),
        onAccent = Color(0xFF10200C),
        accentContainer = Color(0xFF26321E),
        onAccentContainer = Color(0xFFD6E6C8),
        outline = Color(0xFF2B3327),
        isDark = true,
    ),
    DARK_PINK(
        storageKey = "dark_pink",
        labelRes = R.string.reader_theme_dark_pink,
        background = Color(0xFF2F2126),
        text = Color(0xFFEEDCE0),
        secondaryText = Color(0xFFD2B4BD),
        accent = Color(0xFFD98BA0),
        surface = Color(0xFF34242A),
        surfaceVariant = Color(0xFF3E2C33),
        panelText = Color(0xFFF1E1E5),
        panelSub = Color(0xFFCDB0B8),
        onAccent = Color(0xFF2A1016),
        accentContainer = Color(0xFF3E282D),
        onAccentContainer = Color(0xFFF6DBE2),
        outline = Color(0xFF382B2F),
        isDark = true,
    ),
    INK_BLUE(
        storageKey = "ink_blue",
        labelRes = R.string.reader_theme_ink_blue,
        background = Color(0xFF212B3B),
        text = Color(0xFFD9E2EC),
        secondaryText = Color(0xFFB6C4D8),
        accent = Color(0xFF7CA0CF),
        surface = Color(0xFF222B3B),
        surfaceVariant = Color(0xFF2A3546),
        panelText = Color(0xFFDFE6EF),
        panelSub = Color(0xFFAFBDCE),
        onAccent = Color(0xFF0C1622),
        accentContainer = Color(0xFF243248),
        onAccentContainer = Color(0xFFD3E1F2),
        outline = Color(0xFF2A3543),
        isDark = true,
    ),
    BLACK(
        storageKey = "black",
        labelRes = R.string.reader_theme_black,
        background = Color(0xFF000000),
        text = Color(0xFFD2D2D2),
        secondaryText = Color(0xFFADADAD),
        accent = Color(0xFF9DB4C4),
        surface = Color(0xFF0B0B0C),
        surfaceVariant = Color(0xFF161617),
        panelText = Color(0xFFEAEAEA),
        panelSub = Color(0xFFA8A8A8),
        onAccent = Color(0xFF08141A),
        accentContainer = Color(0xFF1C2529),
        onAccentContainer = Color(0xFFD5E3EA),
        outline = Color(0xFF262626),
        isDark = true,
    ),
    ;

    companion object {
        fun fromKey(key: String?): ReaderTheme =
            entries.firstOrNull { it.storageKey == key } ?: PAPER
    }
}

/** 用户导入的字体：path 是应用私有目录下的绝对路径。 */
data class ReaderCustomFont(val path: String) {
    /** 展示用名称，取文件名并去掉扩展名。 */
    val displayName: String
        get() = path.substringAfterLast('/').substringBeforeLast('.')
}

/** 翻页方式：平移 / 覆盖 / 上下滑动 / 无动画。 */
enum class ReaderPageTurn(
    val storageKey: String,
    val labelRes: Int,
) {
    SLIDE(storageKey = "slide", labelRes = R.string.reader_page_turn_slide),
    COVER(storageKey = "cover", labelRes = R.string.reader_page_turn_cover),
    VERTICAL(storageKey = "vertical", labelRes = R.string.reader_page_turn_vertical),
    NONE(storageKey = "none", labelRes = R.string.reader_page_turn_none),
    ;

    companion object {
        fun fromKey(key: String?): ReaderPageTurn =
            entries.firstOrNull { it.storageKey == key } ?: NONE
    }
}

/** 进度条作用范围：全文页码 / 本章页码。 */
enum class ReaderProgressScope(
    val storageKey: String,
    val labelRes: Int,
) {
    BOOK(storageKey = "book", labelRes = R.string.reader_progress_book),
    CHAPTER(storageKey = "chapter", labelRes = R.string.reader_progress_chapter),
    ;

    companion object {
        fun fromKey(key: String?): ReaderProgressScope =
            entries.firstOrNull { it.storageKey == key } ?: BOOK
    }
}

data class ReaderSettings(
    val fontSizeSp: Float = 19f,
    val lineSpacing: Float = 1.6f,
    /** 段间距，按字号的倍数存，界面显示成百分比。 */
    val paragraphSpacing: Float = 0.45f,
    /** 屏幕亮度，0 表示跟随系统。 */
    val brightness: Float = 0f,
    val horizontalPaddingDp: Float = 22f,
    /** 浅色主题，应用未开启深色模式时生效。 */
    val lightTheme: ReaderTheme = ReaderTheme.PAPER,
    /** 深色主题，应用开启深色模式时生效。 */
    val darkTheme: ReaderTheme = ReaderTheme.INK_GREEN,
    /** 自定义字体绝对路径，null 表示使用系统默认字体。 */
    val customFontPath: String? = null,
    /** 正文字重，1–9 对应 100–900，默认 4（常规）。 */
    val fontWeight: Int = 4,
    val pageTurn: ReaderPageTurn = ReaderPageTurn.NONE,
    val progressScope: ReaderProgressScope = ReaderProgressScope.BOOK,
    val firstLineIndent: Boolean = true,
    val showChapterTitle: Boolean = true,
    val showTimeBattery: Boolean = true,
) {
    val fontSizeMin: Float get() = 14f
    val fontSizeMax: Float get() = 30f
    val fontSizeSteps: Int get() = 15
    val lineSpacingMin: Float get() = 1.2f
    val lineSpacingMax: Float get() = 2.2f
    val lineSpacingSteps: Int get() = 9
    val paragraphSpacingMin: Float get() = 0f
    val paragraphSpacingMax: Float get() = 1f
    val paragraphSpacingSteps: Int get() = 9
    val brightnessSteps: Int get() = 19
    val horizontalPaddingMin: Float get() = 10f
    val horizontalPaddingMax: Float get() = 40f
    val horizontalPaddingSteps: Int get() = 14
    val fontWeightMin: Float get() = 1f
    val fontWeightMax: Float get() = 9f
    val fontWeightSteps: Int get() = 7

    /** 把 1–9 的字重映射成 Compose 的 FontWeight。 */
    val fontWeightValue: FontWeight
        get() = FontWeight(fontWeight.coerceIn(1, 9) * 100)
}
