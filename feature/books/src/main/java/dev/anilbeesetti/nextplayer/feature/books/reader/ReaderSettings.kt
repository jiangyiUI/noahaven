package dev.anilbeesetti.nextplayer.feature.books.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.anilbeesetti.nextplayer.core.ui.R
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * 阅读页皮肤。内置 5 套照抄 shuangdu 的 SkinPalette，不跟随应用主题；自定义主题由用户在编辑器里创建。
 * 自定义主题只需填 5 个核心色（background/text/secondaryText/accent/surface），
 * 其余 8 个（panelText/panelSub/surfaceVariant/outline/accentContainer/onAccent/onAccentContainer）由核心色派生。
 */
sealed class ReaderTheme {
    abstract val storageKey: String
    abstract val isDark: Boolean
    abstract val background: Color
    abstract val text: Color
    abstract val secondaryText: Color
    abstract val accent: Color
    abstract val surface: Color
    abstract val surfaceVariant: Color
    abstract val panelText: Color
    abstract val panelSub: Color
    abstract val onAccent: Color
    abstract val accentContainer: Color
    abstract val onAccentContainer: Color
    abstract val outline: Color
    /** 自定义主题背景图绝对路径，内置为 null。导入导出时不备份此字段。 */
    abstract val backgroundImagePath: String?
    /** 背景图遮罩透明度 0f~1f：0 = 完全显示图片，1 = 完全盖住图片（等于纯色背景）。 */
    abstract val maskAlpha: Float

    /** 内置主题，所有色值硬编码，不可编辑/删除。 */
    data class BuiltIn(
        override val storageKey: String,
        val labelRes: Int,
        override val background: Color,
        override val text: Color,
        override val secondaryText: Color,
        override val accent: Color,
        override val surface: Color,
        override val surfaceVariant: Color,
        override val panelText: Color,
        override val panelSub: Color,
        override val onAccent: Color,
        override val accentContainer: Color,
        override val onAccentContainer: Color,
        override val outline: Color,
        override val isDark: Boolean,
    ) : ReaderTheme() {
        override val backgroundImagePath: String? = null
        override val maskAlpha: Float = 0f
    }

    /**
     * 用户自定义主题。只持久化 5 个核心色 + 名称 + 背景图 + 遮罩，其余 8 色由核心色派生。
     * 颜色以 Long(ARGB) 序列化，便于存进 SharedPreferences / 备份 JSON。
     */
    @Serializable
    data class Custom(
        override val storageKey: String,
        val name: String,
        override val isDark: Boolean,
        @Serializable(with = ColorAsLongSerializer::class) override val background: Color,
        @Serializable(with = ColorAsLongSerializer::class) override val text: Color,
        @Serializable(with = ColorAsLongSerializer::class) override val secondaryText: Color,
        @Serializable(with = ColorAsLongSerializer::class) override val accent: Color,
        @Serializable(with = ColorAsLongSerializer::class) override val surface: Color,
        override val backgroundImagePath: String? = null,
        override val maskAlpha: Float = 0f,
    ) : ReaderTheme() {
        override val panelText: Color get() = text
        override val panelSub: Color get() = secondaryText
        override val surfaceVariant: Color get() =
            surface.blend(if (isDark) Color.White else Color.Black, if (isDark) 0.08f else 0.05f)
        override val outline: Color get() =
            surface.blend(if (isDark) Color.White else Color.Black, if (isDark) 0.12f else 0.10f)
        override val accentContainer: Color get() =
            accent.blend(if (isDark) Color.Black else Color.White, 0.7f)
        override val onAccent: Color get() = accent.contrastTextColor()
        override val onAccentContainer: Color get() = accentContainer.contrastTextColor()
    }

    companion object {
        val PAPER: BuiltIn = BuiltIn(
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
        )
        val INK_GREEN: BuiltIn = BuiltIn(
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
        )
        val DARK_PINK: BuiltIn = BuiltIn(
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
        )
        val INK_BLUE: BuiltIn = BuiltIn(
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
        )
        val BLACK: BuiltIn = BuiltIn(
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
        )

        /** 全部内置主题，按定义顺序。 */
        val builtIns: List<ReaderTheme> = listOf(PAPER, INK_GREEN, DARK_PINK, INK_BLUE, BLACK)

        fun fromKey(key: String?, customThemes: List<Custom> = emptyList()): ReaderTheme =
            builtIns.firstOrNull { it.storageKey == key }
                ?: customThemes.firstOrNull { it.storageKey == key }
                ?: PAPER
    }
}

/** 主题显示名：内置取字符串资源，自定义取用户填的名称。 */
@Composable
fun ReaderTheme.displayName(): String = when (this) {
    is ReaderTheme.BuiltIn -> stringResource(labelRes)
    is ReaderTheme.Custom -> name
}

/** Compose Color 序列化为无符号 Long(ARGB)。 */
object ColorAsLongSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Color", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Color) =
        encoder.encodeLong(value.toArgb().toLong() and 0xFFFFFFFFL)

    override fun deserialize(decoder: Decoder): Color = Color(decoder.decodeLong().toInt())
}

private fun Color.blend(other: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return Color(
        red = red * (1f - r) + other.red * r,
        green = green * (1f - r) + other.green * r,
        blue = blue * (1f - r) + other.blue * r,
        alpha = alpha,
    )
}

private fun Color.contrastTextColor(): Color =
    if (0.299f * red + 0.587f * green + 0.114f * blue > 0.5f) Color.Black else Color.White

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
    /** 单手模式：非上下滑动时，单击屏幕左右两侧都翻到下一页。上下滑动模式下不生效。 */
    val oneHandMode: Boolean = false,
    val progressScope: ReaderProgressScope = ReaderProgressScope.BOOK,
    val firstLineIndent: Boolean = true,
    val showChapterTitle: Boolean = true,
    val showTimeBattery: Boolean = true,
    /** 正文两端对齐：拉伸行内字距让左右都贴齐，段落最后一行不拉伸。 */
    val justifyText: Boolean = true,
) {
    val fontSizeMin: Float get() = 14f
    val fontSizeMax: Float get() = 30f
    val fontSizeSteps: Int get() = 15
    val lineSpacingMin: Float get() = 1.2f
    val lineSpacingMax: Float get() = 2.2f
    val lineSpacingSteps: Int get() = 9
    val paragraphSpacingMin: Float get() = 0f
    val paragraphSpacingMax: Float get() = 2f
    val paragraphSpacingSteps: Int get() = 19
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
