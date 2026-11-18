package dev.anilbeesetti.nextplayer.settings.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

private const val LATEST_RELEASE_URL =
    "https://api.github.com/repos/jiangyiUI/noahaven/releases/latest"

private val json = Json { ignoreUnknownKeys = true }

/** 一次新版本发布：版本号、更新说明和可下载的安装包。 */
data class AppRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val assets: List<ReleaseAsset>,
)

data class ReleaseAsset(
    val name: String,
    val size: Long,
    /** GitHub 返回的 sha256（去掉 `sha256:` 前缀），用于校验下载结果。 */
    val sha256: String?,
    val downloadUrl: String,
)

/** GitHub Releases API 里用到的字段（只取展示和下载需要的部分）。 */
@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("name") val name: String = "",
    @SerialName("body") val body: String = "",
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    val digest: String? = null,
    @SerialName("browser_download_url") val downloadUrl: String,
)

/**
 * 拉取 latest release。 IO 阻塞调用，调用方需放到 Dispatchers.IO；
 * 网络或协议错误直接抛出，由 UpdateManager 统一转成提示。
 */
internal fun fetchLatestRelease(client: OkHttpClient): AppRelease {
    val request = Request.Builder()
        .url(LATEST_RELEASE_URL)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "NoaHaven-Updater")
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}")
        val body = response.body?.string() ?: error("Empty response")
        val release = json.decodeFromString<GitHubRelease>(body)
        return AppRelease(
            tagName = release.tagName,
            title = release.name.ifBlank { release.tagName },
            notes = release.body.trim(),
            assets = release.assets.map { asset ->
                ReleaseAsset(
                    name = asset.name,
                    size = asset.size,
                    sha256 = asset.digest
                        ?.removePrefix("sha256:")
                        ?.takeIf { it.isNotBlank() },
                    downloadUrl = asset.downloadUrl,
                )
            },
        )
    }
}
