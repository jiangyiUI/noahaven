package dev.anilbeesetti.nextplayer.core.media.index

import android.content.Context
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.database.dao.MediaFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.entities.MediaFileIndexEntity
import dev.anilbeesetti.nextplayer.core.media.services.FileSystemVideoScanner
import dev.anilbeesetti.nextplayer.core.media.services.readVideoMetadata
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 基于 Room 的 [MediaFileIndex] 实现。
 *
 * - 启动：从数据库整表读进内存（一次性几十毫秒），立即可用；磁盘扫描在后台进行。
 * - 刷新：对目标子树做 stat-only 遍历，按路径做 diff（新增/更新/删除），只写变化的行。
 * - 元数据：新发现的文件先把时长/分辨率留 0，后台用有界队列慢慢补齐并写回数据库。
 */
@Single(binds = [MediaFileIndex::class])
class RoomMediaFileIndex(
    private val context: Context,
    private val mediaFileIndexDao: MediaFileIndexDao,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
) : MediaFileIndex {

    private val mutex = Mutex()
    private val scanner = FileSystemVideoScanner(context = context)

    private val index = HashMap<String, MediaIndexEntry>()
    private var loaded = false

    private val lastRefreshAt = HashMap<String, Long>()
    private val metadataRequested = HashSet<String>()
    private val metadataQueue = Channel<String>(capacity = Channel.UNLIMITED)

    private val _entries = MutableStateFlow<List<MediaIndexEntry>?>(value = null)
    override val entries: StateFlow<List<MediaIndexEntry>?> = _entries.asStateFlow()

    init {
        applicationScope.launch(Dispatchers.IO) { mutex.withLock { loadLocked() } }
        applicationScope.launch(Dispatchers.IO) { drainMetadataQueue() }
    }

    override fun requestRefresh(folderPath: String?) {
        applicationScope.launch(Dispatchers.IO) {
            refresh(
                folderPath = folderPath,
                minIntervalMillis = if (folderPath == null) {
                    FULL_REFRESH_INTERVAL_MS
                } else {
                    FOLDER_REFRESH_INTERVAL_MS
                },
            )
        }
    }

    override fun removePaths(paths: Collection<String>) {
        if (paths.isEmpty()) return
        applicationScope.launch(Dispatchers.IO) {
            mutex.withLock {
                loadLocked()
                val removed = paths.filter { path -> index.remove(path) != null }
                if (removed.isEmpty()) return@withLock
                runCatching { mediaFileIndexDao.deleteByPaths(removed) }
                publishLocked()
            }
        }
    }

    override suspend fun find(path: String): MediaIndexEntry? = mutex.withLock {
        loadLocked()
        index[path]
    }

    /**
     * 立即刷新（跳过节流），供测试和需要同步结果的调用方使用。
     */
    internal suspend fun refresh(folderPath: String?, minIntervalMillis: Long = 0) {
        mutex.withLock {
            loadLocked()
            val key = folderPath ?: ALL_VOLUMES_KEY
            val now = System.currentTimeMillis()
            val last = lastRefreshAt[key]
            if (last != null && minIntervalMillis > 0 &&
                now - last < minIntervalMillis
            ) {
                return@withLock
            }
            lastRefreshAt[key] = now

            // 读不到就放弃本次差异更新，避免把「没有权限」误判成「文件被删除」
            val scanned = scanner.scan(folderPath = folderPath) ?: return@withLock
            applyScanLocked(
                folderPath = folderPath,
                scanned = scanned,
            )
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        val rows = runCatching { mediaFileIndexDao.getAll() }.getOrElse { emptyList() }
        for (row in rows) {
            index[row.path] = row.toEntry()
        }
        loaded = true
        publishLocked()
        rows.forEach { row ->
            if (!row.metadataLoaded) requestMetadataLocked(path = row.path)
        }
    }

    private suspend fun applyScanLocked(
        folderPath: String?,
        scanned: List<FileSystemVideoScanner.ScannedVideo>,
    ) {
        val prefix = folderPath
            ?.trimEnd(File.separatorChar)
            ?.let { "$it${File.separator}" }
        val scannedByPath = HashMap<String, FileSystemVideoScanner.ScannedVideo>(scanned.size * 2)
        scanned.forEach { file -> scannedByPath[file.path] = file }

        // 删掉的 / 被移走的
        val removed = ArrayList<MediaIndexEntry>()
        for (entry in index.values) {
            if (prefix != null && !entry.path.startsWith(prefix)) continue
            if (!scannedByPath.containsKey(entry.path)) removed.add(entry)
        }

        // 新增的 / 有变化的（含重命名：旧路径删掉、新路径新增）
        val added = ArrayList<MediaFileIndexEntity>()
        for (file in scanned) {
            val current = index[file.path]
            if (current == null) {
                val entry = MediaIndexEntry(
                    path = file.path,
                    parentPath = file.parentPath,
                    displayName = file.displayName,
                    size = file.size,
                    dateModified = file.dateModified,
                    duration = 0,
                    width = 0,
                    height = 0,
                    metadataLoaded = false,
                    noMediaHidden = file.noMediaHidden,
                )
                index[file.path] = entry
                added.add(entry.toEntity())
                requestMetadataLocked(path = file.path)
                continue
            }

            val contentChanged = current.size != file.size ||
                current.dateModified != file.dateModified
            val changed = contentChanged ||
                current.displayName != file.displayName ||
                current.parentPath != file.parentPath ||
                current.noMediaHidden != file.noMediaHidden
            if (!changed) continue

            val entry = current.copy(
                parentPath = file.parentPath,
                displayName = file.displayName,
                size = file.size,
                dateModified = file.dateModified,
                noMediaHidden = file.noMediaHidden,
                metadataLoaded = if (contentChanged) false else current.metadataLoaded,
            )
            index[file.path] = entry
            added.add(entry.toEntity())
            if (contentChanged) requestMetadataLocked(path = file.path)
        }

        if (removed.isEmpty() && added.isEmpty()) return

        if (removed.isNotEmpty()) {
            removed.forEach { entry ->
                index.remove(entry.path)
                // 允许同一个路径以后重新出现时再次补元数据
                metadataRequested.remove(entry.path)
            }
            deleteChunked(paths = removed.map { entry -> entry.path })
        }
        if (added.isNotEmpty()) {
            upsertChunked(entities = added)
        }
        publishLocked()
    }

    private suspend fun drainMetadataQueue() {
        while (currentCoroutineContext().isActive) {
            val first = metadataQueue.receive()
            val batch = ArrayList<String>(METADATA_BATCH_SIZE)
            batch.add(first)
            while (batch.size < METADATA_BATCH_SIZE) {
                val next = metadataQueue.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            // 读元数据很慢，放在锁外做
            val readings = batch.map { path -> path to readVideoMetadata(path = path) }

            mutex.withLock {
                val updated = ArrayList<MediaFileIndexEntity>(readings.size)
                for ((path, metadata) in readings) {
                    metadataRequested.remove(path)
                    val current = index[path] ?: continue
                    if (metadata == null) continue
                    if (current.metadataLoaded &&
                        current.duration == metadata.duration &&
                        current.width == metadata.width &&
                        current.height == metadata.height
                    ) {
                        continue
                    }
                    val entry = current.copy(
                        duration = metadata.duration,
                        width = metadata.width,
                        height = metadata.height,
                        metadataLoaded = true,
                    )
                    index[path] = entry
                    updated.add(entry.toEntity())
                }
                if (updated.isNotEmpty()) {
                    upsertChunked(entities = updated)
                    publishLocked()
                }
            }
        }
    }

    private fun requestMetadataLocked(path: String) {
        if (metadataRequested.add(path)) {
            metadataQueue.trySend(element = path)
        }
    }

    private fun publishLocked() {
        _entries.value = index.values.toList()
    }

    private suspend fun upsertChunked(entities: List<MediaFileIndexEntity>) {
        entities.chunked(size = UPSERT_CHUNK_SIZE).forEach { chunk ->
            mediaFileIndexDao.upsertAll(entries = chunk)
        }
    }

    private suspend fun deleteChunked(paths: List<String>) {
        paths.chunked(size = DELETE_CHUNK_SIZE).forEach { chunk ->
            mediaFileIndexDao.deleteByPaths(paths = chunk)
        }
    }

    private companion object {
        const val ALL_VOLUMES_KEY = "/"
        const val FOLDER_REFRESH_INTERVAL_MS = 15_000L
        const val FULL_REFRESH_INTERVAL_MS = 60_000L
        const val METADATA_BATCH_SIZE = 64
        const val UPSERT_CHUNK_SIZE = 90
        const val DELETE_CHUNK_SIZE = 900
    }
}

private fun MediaFileIndexEntity.toEntry(): MediaIndexEntry = MediaIndexEntry(
    path = path,
    parentPath = parentPath,
    displayName = displayName,
    size = size,
    dateModified = dateModified,
    duration = duration,
    width = width,
    height = height,
    metadataLoaded = metadataLoaded,
    noMediaHidden = noMediaHidden,
)

private fun MediaIndexEntry.toEntity(): MediaFileIndexEntity = MediaFileIndexEntity(
    path = path,
    parentPath = parentPath,
    displayName = displayName,
    size = size,
    dateModified = dateModified,
    duration = duration,
    width = width,
    height = height,
    metadataLoaded = metadataLoaded,
    noMediaHidden = noMediaHidden,
)
