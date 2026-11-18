package dev.anilbeesetti.nextplayer.settings.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.ui.R
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
private const val UPDATES_DIR = "updates"
private const val PROGRESS_INTERVAL_MS = 200L

/** 一条下载线路：prefix 为空表示直连 GitHub。 */
data class DownloadMirror(val id: String, val prefix: String) {
    companion object {
        const val CUSTOM_ID = "custom"

        /** 直连 + 常用镜像，镜像失效时可以用自定义前缀自救。 */
        val BUILT_IN_MIRRORS = listOf(
            DownloadMirror(id = "direct", prefix = ""),
            DownloadMirror(id = "ghproxy", prefix = "https://gh-proxy.com/"),
            DownloadMirror(id = "ghfast", prefix = "https://ghfast.top/"),
            DownloadMirror(id = "ghproxyNet", prefix = "https://ghproxy.net/"),
        )
    }
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data object Checking : UpdateUiState

    data class UpToDate(val currentVersion: String) : UpdateUiState

    data class Available(val release: AppRelease) : UpdateUiState

    data class ChooseMirror(
        val release: AppRelease,
        val error: String? = null,
    ) : UpdateUiState

    data class Downloading(
        val release: AppRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateUiState

    data class Downloaded(val release: AppRelease, val apkPath: String) : UpdateUiState

    /** 只需要弹一个「标题 + 文案」的终态（检查失败、签名不一致等）。 */
    data class Message(val title: String, val text: String) : UpdateUiState
}

private class SignatureMismatchException : Exception()

/**
 * 检查更新与下载安装包。
 *
 * 放在 applicationScope 里而不是 ViewModel 里：离开设置页时下载不中断，
 * 回到设置页还能看到进度。
 */
@Single
class UpdateManager(
    private val context: Context,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var release: AppRelease? = null
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** 上次用过的线路，仅内存记忆，用于下载弹窗默认选中。 */
    var lastMirrorId: String? = null
        private set

    var lastCustomPrefix: String = ""
        private set

    fun checkForUpdates() {
        if (_state.value is UpdateUiState.Checking) return
        checkJob?.cancel()
        _state.value = UpdateUiState.Checking
        checkJob = applicationScope.launch {
            try {
                val latest = withContext(Dispatchers.IO) { fetchLatestRelease(client) }
                val current = context.currentVersionName()
                if (isNewerVersion(remote = latest.tagName, current = current)) {
                    release = latest
                    _state.value = UpdateUiState.Available(latest)
                } else {
                    _state.value = UpdateUiState.UpToDate(currentVersion = current)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUiState.Message(
                    title = context.getString(R.string.update_check_failed_title),
                    text = e.message ?: context.getString(R.string.update_unknown_error),
                )
            }
        }
    }

    /** 从「发现新版本」进入线路选择。 */
    fun showMirrorSelection() {
        val target = when (val current = _state.value) {
            is UpdateUiState.Available -> current.release
            is UpdateUiState.ChooseMirror -> current.release
            else -> release
        } ?: return
        _state.value = UpdateUiState.ChooseMirror(release = target)
    }

    fun dismiss() {
        when (val current = _state.value) {
            is UpdateUiState.Checking -> {
                checkJob?.cancel()
                _state.value = UpdateUiState.Idle
            }

            is UpdateUiState.ChooseMirror -> {
                _state.value = UpdateUiState.Available(release = current.release)
            }

            is UpdateUiState.Downloading -> cancelDownload()

            else -> _state.value = UpdateUiState.Idle
        }
    }

    fun download(mirror: DownloadMirror, customPrefix: String?) {
        val targetRelease = (_state.value as? UpdateUiState.ChooseMirror)?.release
            ?: release
            ?: return
        val asset = pickAsset(targetRelease)
        if (asset == null) {
            _state.value = UpdateUiState.Message(
                title = context.getString(R.string.update_failed_title),
                text = context.getString(R.string.update_unknown_error),
            )
            return
        }
        val prefix = if (mirror.id == DownloadMirror.CUSTOM_ID) {
            normalizePrefix(customPrefix.orEmpty())
        } else {
            mirror.prefix
        }
        if (mirror.id == DownloadMirror.CUSTOM_ID && prefix.isEmpty()) return

        lastMirrorId = mirror.id
        lastCustomPrefix = customPrefix.orEmpty()

        downloadJob?.cancel()
        _state.value = UpdateUiState.Downloading(
            release = targetRelease,
            downloadedBytes = 0,
            totalBytes = asset.size,
        )
        downloadJob = applicationScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    downloadToFile(release = targetRelease, asset = asset, prefix = prefix)
                }
                _state.value = UpdateUiState.Downloaded(
                    release = targetRelease,
                    apkPath = file.path,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SignatureMismatchException) {
                _state.value = UpdateUiState.Message(
                    title = context.getString(R.string.update_failed_title),
                    text = context.getString(R.string.update_signature_mismatch),
                )
            } catch (e: Exception) {
                _state.value = UpdateUiState.ChooseMirror(
                    release = targetRelease,
                    error = context.getString(
                        R.string.update_download_failed,
                        e.message ?: context.getString(R.string.update_unknown_error),
                    ),
                )
            }
        }
    }

    fun cancelDownload() {
        val targetRelease = (_state.value as? UpdateUiState.Downloading)?.release ?: release
        downloadJob?.cancel()
        downloadJob = null
        targetRelease?.let { current ->
            pickAsset(current)?.let { asset ->
                File(File(context.cacheDir, UPDATES_DIR), asset.name).delete()
            }
            _state.value = UpdateUiState.ChooseMirror(release = current)
        } ?: run {
            _state.value = UpdateUiState.Idle
        }
    }

    fun installDownloaded() {
        val current = _state.value as? UpdateUiState.Downloaded ?: return
        val file = File(current.apkPath)
        if (!file.exists()) {
            _state.value = UpdateUiState.Message(
                title = context.getString(R.string.update_failed_title),
                text = context.getString(R.string.update_file_missing),
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // 保留 Downloaded 状态：去设置里授权后回来可以直接再点安装，不用重新下载
            Toast.makeText(
                context,
                context.getString(R.string.update_install_permission),
                Toast.LENGTH_LONG,
            ).show()
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return
        }
        val apkUri = runCatching {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        }.getOrElse {
            _state.value = UpdateUiState.Message(
                title = context.getString(R.string.update_failed_title),
                text = context.getString(R.string.update_install_open_failed),
            )
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(apkUri, APK_MIME_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure {
                _state.value = UpdateUiState.Message(
                    title = context.getString(R.string.update_failed_title),
                    text = context.getString(R.string.update_install_open_failed),
                )
            }
    }

    private fun downloadToFile(
        release: AppRelease,
        asset: ReleaseAsset,
        prefix: String,
    ): File {
        val targetDir = File(context.cacheDir, UPDATES_DIR).apply { mkdirs() }
        val target = File(targetDir, asset.name)
        if (target.exists()) target.delete()
        try {
            val request = Request.Builder()
                .url(prefix + asset.downloadUrl)
                .header("User-Agent", "NoaHaven-Updater")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body ?: error("Empty response")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: asset.size
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastNotify = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastNotify >= PROGRESS_INTERVAL_MS) {
                                lastNotify = now
                                notifyProgress(release, downloaded, totalBytes)
                            }
                        }
                        notifyProgress(release, downloaded, totalBytes)
                    }
                }
            }
            asset.sha256?.let { expected ->
                if (!sha256(target).equals(expected, ignoreCase = true)) {
                    error(context.getString(R.string.update_checksum_mismatch))
                }
            }
            if (!hasSameSignature(apkPath = target.path)) {
                throw SignatureMismatchException()
            }
            return target
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    private fun notifyProgress(release: AppRelease, downloaded: Long, total: Long) {
        // 取消后状态已经切走，别让还在读流的线程把状态覆盖回来
        if (_state.value is UpdateUiState.Downloading) {
            _state.value = UpdateUiState.Downloading(
                release = release,
                downloadedBytes = downloaded,
                totalBytes = total,
            )
        }
    }

    /** 安装包签名必须和已安装版本一致，否则系统会拒绝覆盖安装。 */
    private fun hasSameSignature(apkPath: String): Boolean {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(apkPath, signatureFlags()) ?: return false
        if (archive.packageName != context.packageName) return false
        val apkSigners = archive.signers()
        val installedSigners = runCatching {
            pm.getPackageInfo(context.packageName, signatureFlags()).signers()
        }.getOrNull().orEmpty()
        if (apkSigners.isEmpty() || installedSigners.isEmpty()) return false
        if (apkSigners.size != installedSigners.size) return false
        return apkSigners.all { apkSigner ->
            installedSigners.any { installed -> installed.contentEquals(apkSigner) }
        }
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            signatures?.map { it.toByteArray() }.orEmpty()
        }

    /** 按设备 ABI 挑最小的安装包，没有匹配时退回 universal。 */
    private fun pickAsset(release: AppRelease): ReleaseAsset? {
        val supportedAbis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        val abiOrder = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        abiOrder.forEach { abi ->
            if (abi in supportedAbis) {
                release.assets.firstOrNull { it.name.contains(abi, ignoreCase = true) }
                    ?.let { return it }
            }
        }
        return release.assets.firstOrNull { it.name.contains("universal", ignoreCase = true) }
            ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
    }
}

/** 下载前缀统一成以 `/` 结尾，方便直接拼在 GitHub 资源地址前面。 */
internal fun normalizePrefix(prefix: String): String {
    val trimmed = prefix.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
}

/**
 * 只比较数字段（兼容 `v0.22.3`、`0.22.4-beta` 这类 tag）。
 * 远端比当前新才返回 true。
 */
internal fun isNewerVersion(remote: String, current: String): Boolean {
    fun numbers(value: String): List<Int> = value
        .trim()
        .removePrefix("v")
        .split('.', '-', '+', '_')
        .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }

    val remoteParts = numbers(remote)
    if (remoteParts.isEmpty()) return false
    val currentParts = numbers(current)
    val size = maxOf(remoteParts.size, currentParts.size)
    for (index in 0 until size) {
        val remotePart = remoteParts.getOrElse(index) { 0 }
        val currentPart = currentParts.getOrElse(index) { 0 }
        if (remotePart != currentPart) return remotePart > currentPart
    }
    return false
}

private fun Context.currentVersionName(): String = runCatching {
    packageManager.getPackageInfo(packageName, 0).versionName
}.getOrNull().orEmpty()

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString(separator = "") { byte ->
        String.format(Locale.ROOT, "%02x", byte)
    }
}
