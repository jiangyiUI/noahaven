package dev.anilbeesetti.nextplayer.settings.backup

import dev.anilbeesetti.nextplayer.core.model.ApplicationPreferences
import dev.anilbeesetti.nextplayer.core.model.NetworkConnection
import dev.anilbeesetti.nextplayer.core.model.NetworkProtocol
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.feature.books.data.BookShelfBackupData
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderPageTurn
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderProgressScope
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderSettings
import dev.anilbeesetti.nextplayer.feature.books.reader.ReaderTheme
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

internal data class ImportedSettings(
    val applicationPreferences: ApplicationPreferences,
    val playerPreferences: PlayerPreferences,
    val networkConnections: List<NetworkConnection>? = null,
    val sshKeys: Map<String, String> = emptyMap(),
    // 只备份颜色数据；背景图不打包，导入后 backgroundImagePath 为 null，降级为纯色背景。
    val customThemes: List<ReaderTheme.Custom> = emptyList(),
    // 阅读设置：主题只存 storageKey（避免序列化 BuiltIn 里不稳定的 labelRes），不含 customFontPath（字体不备份）。
    val readerSettings: ReaderSettingsBackup? = null,
    // 书架：分组 + 条目。
    val bookShelf: BookShelfBackupData = BookShelfBackupData(),
)

@OptIn(ExperimentalSerializationApi::class)
internal class SettingsBackupCodec(private val subtitleEncodings: Set<String>) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun encode(
        applicationPreferences: ApplicationPreferences,
        playerPreferences: PlayerPreferences,
        networkConnections: List<NetworkConnection>? = null,
        sshKeys: Map<String, String> = emptyMap(),
        customThemes: List<ReaderTheme.Custom> = emptyList(),
        readerSettings: ReaderSettingsBackup? = null,
        bookShelf: BookShelfBackupData = BookShelfBackupData(),
    ): String = buildJsonObject {
        put(
            "version",
            1,
        )
        put(
            "applicationPreferences",
            // 不备份最后阅读书籍 / 最近阅读时间
            JsonObject(
                json.encodeToJsonElement(applicationPreferences).jsonObject -
                    "lastViewedBookPath" - "bookLastViewedAt",
            ),
        )
        put(
            "playerPreferences",
            JsonObject(json.encodeToJsonElement(playerPreferences).jsonObject - "useSwipeControls"),
        )
        put(
            "customThemes",
            JsonArray(
                customThemes.map { theme ->
                    JsonObject(json.encodeToJsonElement(theme).jsonObject - "backgroundImagePath")
                },
            ),
        )
        if (readerSettings != null) {
            put("readerSettings", json.encodeToJsonElement(readerSettings))
        }
        put("bookShelf", json.encodeToJsonElement(bookShelf))
        if (networkConnections != null) {
            put(
                "networkConnections",
                JsonArray(
                    networkConnections.map { connection ->
                        JsonObject(json.encodeToJsonElement(connection).jsonObject - "id")
                    },
                ),
            )
            put(
                "sshKeys",
                json.encodeToJsonElement(sshKeys),
            )
        }
    }.let {
        json.encodeToString(
            JsonObject.serializer(),
            it,
        )
    }

    fun parse(content: String): JsonObject {
        val root = json.parseToJsonElement(content) as? JsonObject
            ?: throw IllegalArgumentException("Settings must be a JSON object")
        require(
            root["applicationPreferences"] is JsonObject ||
                root["playerPreferences"] is JsonObject ||
                root["networkConnections"] is JsonArray,
        ) { "No supported settings found" }
        return root
    }

    fun decode(
        content: JsonObject,
        applicationPreferences: ApplicationPreferences,
        playerPreferences: PlayerPreferences,
        networkConnections: List<NetworkConnection>,
    ): ImportedSettings = ImportedSettings(
        applicationPreferences = merge(
            applicationPreferences,
            content["applicationPreferences"],
        ),
        playerPreferences = merge(
            playerPreferences,
            content["playerPreferences"],
        ),
        networkConnections = mergeConnections(
            content["networkConnections"],
            networkConnections,
        ),
        sshKeys = (content["sshKeys"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { name to it }
        }.toMap(),
        customThemes = decodeCustomThemes(content["customThemes"]),
        readerSettings = decodeReaderSettings(content["readerSettings"]),
        bookShelf = decodeBookShelf(content["bookShelf"]),
    )

    private fun decodeCustomThemes(element: JsonElement?): List<ReaderTheme.Custom> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapNotNull { entry ->
            runCatching { json.decodeFromJsonElement<ReaderTheme.Custom>(entry) }.getOrNull()
        }
    }

    private fun decodeReaderSettings(element: JsonElement?): ReaderSettingsBackup? {
        val obj = element as? JsonObject ?: return null
        return runCatching { json.decodeFromJsonElement<ReaderSettingsBackup>(obj) }.getOrNull()
    }

    private fun decodeBookShelf(element: JsonElement?): BookShelfBackupData {
        val obj = element as? JsonObject ?: return BookShelfBackupData()
        return runCatching { json.decodeFromJsonElement<BookShelfBackupData>(obj) }
            .getOrDefault(BookShelfBackupData())
    }

    private fun mergeConnections(
        fields: JsonElement?,
        current: List<NetworkConnection>,
    ): List<NetworkConnection>? {
        if (fields !is JsonArray) return null
        val connections = current.toMutableList()
        val updated = linkedSetOf<Int>()
        fields.forEach { element ->
            val entry = element as? JsonObject ?: return@forEach
            val name = entry.string("name")?.takeIf(String::isNotBlank) ?: return@forEach
            val host = entry.string("host")?.takeIf(String::isNotBlank) ?: return@forEach
            val protocol = entry.string("protocol")?.let { value ->
                NetworkProtocol.entries.firstOrNull { it.name == value }
            } ?: return@forEach
            val candidates = connections.indices.filter { index ->
                connections[index].let {
                    it.name == name && it.host == host &&
                        it.protocol == protocol
                }
            }
            val port = if (entry["port"] == JsonNull) {
                protocol.defaultPort
            } else {
                (entry["port"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                    ?.takeIf { it in 1..65535 }
            }
            val path = entry.string("path")
            val username = entry.string("username")
            val matching = candidates.filter { index ->
                val connection = connections[index]
                (port == null || connection.effectivePort == port) &&
                    (path == null || connection.path == path) &&
                    (username == null || connection.username == username)
            }
            if (matching.size > 1) return@forEach
            val index = matching.singleOrNull()
            val base = index?.let(connections::get) ?: NetworkConnection(
                name = name,
                host = host,
                protocol = protocol,
            )
            val connection = merge(
                base,
                JsonObject(entry - "id"),
            )
            if (index == null) {
                updated.add(connections.size)
                connections.add(connection)
            } else {
                updated.add(index)
                connections[index] = connection
            }
        }
        return updated.map(connections::get)
    }

    private inline fun <reified T> merge(current: T, fields: JsonElement?): T {
        if (fields !is JsonObject) return current
        val serializer = serializer<T>()
        var result = current
        fields.forEach { (name, value) ->
            val index = serializer.descriptor.getElementIndex(name)
            if (index < 0 || name == "useSwipeControls") return@forEach
            if (!isValidValue(
                    value,
                    serializer.descriptor.getElementDescriptor(index),
                ) ||
                !isValidSetting(
                    name,
                    value,
                )
            ) {
                return@forEach
            }
            val updated = json.encodeToJsonElement(
                serializer,
                result,
            ).jsonObject.toMutableMap()
            updated[name] = value
            try {
                result = json.decodeFromJsonElement(
                    serializer,
                    JsonObject(updated),
                )
            } catch (_: SerializationException) {
                // A single unsupported field must not discard the other imported preferences.
            }
        }
        return result
    }

    private fun isValidValue(value: JsonElement, descriptor: SerialDescriptor): Boolean {
        if (value == JsonNull) return descriptor.isNullable
        val primitive = value as? JsonPrimitive
        return when (descriptor.kind) {
            PrimitiveKind.BOOLEAN -> primitive?.let { !it.isString && it.booleanOrNull != null } ==
                true

            PrimitiveKind.STRING, PrimitiveKind.CHAR -> primitive?.isString == true

            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG ->
                primitive?.let { !it.isString && it.longOrNull != null } == true

            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> primitive?.let {
                !it.isString && it.doubleOrNull?.isFinite() == true &&
                    (
                        descriptor.kind != PrimitiveKind.FLOAT ||
                            it.doubleOrNull!!.toFloat().isFinite()
                        )
            } == true

            SerialKind.ENUM -> primitive?.let {
                it.isString && descriptor.getElementIndex(it.content) >= 0
            } == true

            StructureKind.LIST ->
                value is JsonArray &&
                    value.all {
                        isValidValue(
                            it,
                            descriptor.getElementDescriptor(0),
                        )
                    }

            // 书签 Map<String, List<Bookmark>>、最近阅读时间 Map<String, Long> 等都走这里；
            // 嵌套 data class（如 Bookmark）递归校验字段，非法值由外层 decode 的 try/catch 兜底。
            StructureKind.MAP ->
                value is JsonObject &&
                    value.entries.all { (key, v) ->
                        isValidValue(JsonPrimitive(key), descriptor.getElementDescriptor(0)) &&
                            isValidValue(v, descriptor.getElementDescriptor(1))
                    }

            StructureKind.CLASS ->
                value is JsonObject &&
                    (0 until descriptor.elementsCount).all { index ->
                        val v = value[descriptor.getElementName(index)]
                        if (v == null) {
                            descriptor.isElementOptional(index)
                        } else {
                            isValidValue(v, descriptor.getElementDescriptor(index))
                        }
                    }

            StructureKind.OBJECT -> true

            else -> false
        }
    }

    private fun isValidSetting(name: String, value: JsonElement): Boolean {
        val number = (value as? JsonPrimitive)?.doubleOrNull
        return when (name) {
            // 不恢复最后阅读书籍 / 最近阅读时间（即便旧备份里带了也跳过）
            "lastViewedBookPath", "bookLastViewedAt" -> false

            "playerBrightness", "thumbnailFramePosition" -> number != null && number in 0.0..1.0

            "defaultPlaybackSpeed", "longPressControlsSpeed" -> number != null && number in 0.2..4.0

            "seekSensitivity", "volumeGestureSensitivity", "brightnessGestureSensitivity" ->
                number != null && number in 0.1..2.0

            "seekIncrement" -> number != null && number in 1.0..60.0

            "controllerAutoHideTimeout" -> number != null && number in 0.0..600.0

            "subtitleTextSize" -> number != null && number in 10.0..60.0

            "newVideoThresholdDays" -> number != null && number in 0.0..30.0

            "minDurationForFastSeek" -> number != null && number >= 0

            "port" -> value == JsonNull || (number != null && number in 1.0..65535.0)

            "subtitleTextEncoding" -> (value as? JsonPrimitive)?.content in subtitleEncodings

            else -> true
        }
    }

    private fun JsonObject.string(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * 阅读设置的备份模型。主题只存 storageKey，导入时用 [ReaderTheme.fromKey] 还原，
 * 避免序列化 [ReaderTheme.BuiltIn] 里跨版本不稳定的 labelRes；不含 customFontPath（字体不备份）。
 */
@Serializable
internal data class ReaderSettingsBackup(
    val fontSizeSp: Float,
    val lineSpacing: Float,
    val paragraphSpacing: Float,
    val brightness: Float,
    val horizontalPaddingDp: Float,
    val lightThemeKey: String,
    val darkThemeKey: String,
    val fontWeight: Int,
    val pageTurnKey: String,
    val progressScopeKey: String,
    val oneHandMode: Boolean,
    val firstLineIndent: Boolean,
    val showChapterTitle: Boolean,
    val showTimeBattery: Boolean,
    val justifyText: Boolean = true,
)

internal fun ReaderSettings.toBackup(): ReaderSettingsBackup = ReaderSettingsBackup(
    fontSizeSp = fontSizeSp,
    lineSpacing = lineSpacing,
    paragraphSpacing = paragraphSpacing,
    brightness = brightness,
    horizontalPaddingDp = horizontalPaddingDp,
    lightThemeKey = lightTheme.storageKey,
    darkThemeKey = darkTheme.storageKey,
    fontWeight = fontWeight,
    pageTurnKey = pageTurn.storageKey,
    progressScopeKey = progressScope.storageKey,
    oneHandMode = oneHandMode,
    firstLineIndent = firstLineIndent,
    showChapterTitle = showChapterTitle,
    showTimeBattery = showTimeBattery,
    justifyText = justifyText,
)

/**
 * 还原阅读设置。主题需要在自定义主题导入之后解析，所以由调用方传入最新的 customThemes；
 * [customFontPath] 保留当前设备的值（字体不随备份迁移）。
 */
internal fun ReaderSettingsBackup.toReaderSettings(
    customThemes: List<ReaderTheme.Custom>,
    customFontPath: String?,
): ReaderSettings = ReaderSettings(
    fontSizeSp = fontSizeSp,
    lineSpacing = lineSpacing,
    paragraphSpacing = paragraphSpacing,
    brightness = brightness,
    horizontalPaddingDp = horizontalPaddingDp,
    lightTheme = ReaderTheme.fromKey(lightThemeKey, customThemes),
    darkTheme = ReaderTheme.fromKey(darkThemeKey, customThemes),
    customFontPath = customFontPath,
    fontWeight = fontWeight,
    pageTurn = ReaderPageTurn.fromKey(pageTurnKey),
    oneHandMode = oneHandMode,
    progressScope = ReaderProgressScope.fromKey(progressScopeKey),
    firstLineIndent = firstLineIndent,
    showChapterTitle = showChapterTitle,
    showTimeBattery = showTimeBattery,
    justifyText = justifyText,
)
