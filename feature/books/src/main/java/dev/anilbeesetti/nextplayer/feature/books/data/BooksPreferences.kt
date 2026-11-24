package dev.anilbeesetti.nextplayer.feature.books.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderCustomFont
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderPageTurn
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderProgressScope
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderSettings
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/** 阅读外观与书架排序偏好。数据量很小，直接用 SharedPreferences，同步读写避免 DataStore 首帧闪烁。 */
@Single
class BooksPreferences(context: Context) {

    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("book_preferences", Context.MODE_PRIVATE)

    private val settingsInternal = MutableStateFlow(readSettings())
    val settings: StateFlow<ReaderSettings> = settingsInternal.asStateFlow()

    private val customFontsInternal = MutableStateFlow(readCustomFonts())
    /** 已导入的字体，按导入顺序展示。 */
    val customFonts: StateFlow<List<ReaderCustomFont>> = customFontsInternal.asStateFlow()

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) {
        val current = settingsInternal.value
        val next = transform(current)
        val updated = next.copy(
            fontSizeSp = next.fontSizeSp.coerceIn(current.fontSizeMin, current.fontSizeMax),
            lineSpacing = next.lineSpacing.coerceIn(current.lineSpacingMin, current.lineSpacingMax),
            paragraphSpacing = next.paragraphSpacing.coerceIn(
                current.paragraphSpacingMin,
                current.paragraphSpacingMax,
            ),
            brightness = next.brightness.coerceIn(0f, 1f),
            horizontalPaddingDp = next.horizontalPaddingDp.coerceIn(
                current.horizontalPaddingMin,
                current.horizontalPaddingMax,
            ),
            fontWeight = next.fontWeight.coerceIn(1, 9),
        )
        settingsInternal.value = updated
        preferences.edit()
            .putFloat(KEY_FONT_SIZE, updated.fontSizeSp)
            .putFloat(KEY_LINE_SPACING, updated.lineSpacing)
            .putFloat(KEY_PARAGRAPH_SPACING, updated.paragraphSpacing)
            .putFloat(KEY_BRIGHTNESS, updated.brightness)
            .putFloat(KEY_HORIZONTAL_PADDING, updated.horizontalPaddingDp)
            .putString(KEY_LIGHT_THEME, updated.lightTheme.storageKey)
            .putString(KEY_DARK_THEME, updated.darkTheme.storageKey)
            .putString(KEY_CUSTOM_FONT, updated.customFontPath)
            .putInt(KEY_FONT_WEIGHT, updated.fontWeight)
            .putString(KEY_PAGE_TURN, updated.pageTurn.storageKey)
            .putString(KEY_PROGRESS_SCOPE, updated.progressScope.storageKey)
            .putBoolean(KEY_FIRST_LINE_INDENT, updated.firstLineIndent)
            .putBoolean(KEY_SHOW_CHAPTER_TITLE, updated.showChapterTitle)
            .putBoolean(KEY_SHOW_TIME_BATTERY, updated.showTimeBattery)
            .apply()
    }

    private fun readSettings(): ReaderSettings {
        // 旧版本只存一套主题，按深浅拆到浅色/深色两行。
        val legacyTheme = preferences.getString(KEY_THEME, null)?.let(ReaderTheme::fromKey)
        val lightTheme = preferences.getString(KEY_LIGHT_THEME, null)
            ?.let(ReaderTheme::fromKey)
            ?: legacyTheme?.takeUnless { it.isDark }
            ?: ReaderTheme.PAPER
        val darkTheme = preferences.getString(KEY_DARK_THEME, null)
            ?.let(ReaderTheme::fromKey)
            ?: legacyTheme?.takeIf { it.isDark }
            ?: ReaderTheme.INK_GREEN
        return ReaderSettings(
            fontSizeSp = preferences.getFloat(KEY_FONT_SIZE, 19f)
                .coerceIn(14f, 30f),
            lineSpacing = preferences.getFloat(KEY_LINE_SPACING, 1.6f)
                .coerceIn(1.2f, 2.2f),
            paragraphSpacing = preferences.getFloat(KEY_PARAGRAPH_SPACING, 0.45f)
                .coerceIn(0f, 1f),
            brightness = preferences.getFloat(KEY_BRIGHTNESS, 0f)
                .coerceIn(0f, 1f),
            horizontalPaddingDp = preferences.getFloat(KEY_HORIZONTAL_PADDING, 22f)
                .coerceIn(10f, 40f),
            lightTheme = lightTheme,
            darkTheme = darkTheme,
            customFontPath = preferences.getString(KEY_CUSTOM_FONT, null)
                ?.takeIf { File(it).exists() },
            fontWeight = preferences.getInt(KEY_FONT_WEIGHT, 4).coerceIn(1, 9),
            pageTurn = ReaderPageTurn.fromKey(preferences.getString(KEY_PAGE_TURN, null)),
            progressScope = ReaderProgressScope.fromKey(
                preferences.getString(KEY_PROGRESS_SCOPE, null),
            ),
            firstLineIndent = preferences.getBoolean(KEY_FIRST_LINE_INDENT, true),
            showChapterTitle = preferences.getBoolean(KEY_SHOW_CHAPTER_TITLE, true),
            showTimeBattery = preferences.getBoolean(KEY_SHOW_TIME_BATTERY, true),
        )
    }

    private fun readCustomFonts(): List<ReaderCustomFont> =
        preferences.getStringSet(KEY_CUSTOM_FONTS, emptySet())
            .orEmpty()
            .map(::ReaderCustomFont)
            .filter { File(it.path).exists() }
            .sortedBy { File(it.path).lastModified() }

    private fun persistCustomFonts(fonts: List<ReaderCustomFont>) {
        preferences.edit()
            .putStringSet(KEY_CUSTOM_FONTS, fonts.map { it.path }.toSet())
            .apply()
        customFontsInternal.value = fonts
    }

    /** 把用户选中的字体文件复制到应用私有目录，成功返回新文件绝对路径。 */
    suspend fun importFont(uri: Uri): String? = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, FONT_DIR_NAME).apply { mkdirs() }
        val originalName = queryDisplayName(uri) ?: "font_${System.currentTimeMillis()}.ttf"
        val safeName = originalName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val base = safeName.substringBeforeLast('.', safeName)
        val extension = safeName.substringAfterLast('.', "")
        var target = File(dir, safeName)
        var index = 1
        while (target.exists()) {
            val uniqueName = if (extension.isEmpty()) {
                "${base}_$index"
            } else {
                "${base}_$index.$extension"
            }
            target = File(dir, uniqueName)
            index++
        }
        runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return@runCatching false
            // 导入后立刻尝试解析，非字体文件直接丢弃，避免出现打不开的条目。
            Typeface.createFromFile(target)
            true
        }.getOrDefault(false).let { success ->
            if (!success) {
                target.delete()
                return@withContext null
            }
        }
        val updated = customFontsInternal.value + ReaderCustomFont(target.absolutePath)
        persistCustomFonts(updated)
        target.absolutePath
    }

    /** 删除导入的字体文件；如果正被使用则回落到默认字体。 */
    fun deleteFont(path: String) {
        File(path).delete()
        val remaining = customFontsInternal.value.filterNot { it.path == path }
        persistCustomFonts(remaining)
        if (settingsInternal.value.customFontPath == path) {
            updateSettings { it.copy(customFontPath = null) }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        appContext.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    private companion object {
        const val FONT_DIR_NAME = "reader_fonts"
        const val KEY_FONT_SIZE = "font_size"
        const val KEY_LINE_SPACING = "line_spacing"
        const val KEY_PARAGRAPH_SPACING = "paragraph_spacing"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_HORIZONTAL_PADDING = "horizontal_padding"
        const val KEY_THEME = "theme"
        const val KEY_LIGHT_THEME = "light_theme"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_CUSTOM_FONT = "custom_font_path"
        const val KEY_CUSTOM_FONTS = "custom_fonts"
        const val KEY_FONT_WEIGHT = "font_weight"
        const val KEY_PAGE_TURN = "page_turn"
        const val KEY_PROGRESS_SCOPE = "progress_scope"
        const val KEY_FIRST_LINE_INDENT = "first_line_indent"
        const val KEY_SHOW_CHAPTER_TITLE = "show_chapter_title"
        const val KEY_SHOW_TIME_BATTERY = "show_time_battery"
    }
}
