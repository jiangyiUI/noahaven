package dev.anilbeesetti.nextplayer.core.media.index

import android.content.Context
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.database.dao.AudioFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.entities.AudioFileIndexEntity
import dev.anilbeesetti.nextplayer.core.media.services.AudioMetadata
import dev.anilbeesetti.nextplayer.core.media.services.FileSystemAudioScanner
import dev.anilbeesetti.nextplayer.core.media.services.readAudioMetadata
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
 * 基于 Room 的 [AudioFileIndex] 实现，结构对齐视频的 [RoomMediaFileIndex]：
 *
 * - 启动：从数据库整表读进内存，立即可用；磁盘扫描在后台进行。
 * - 刷新：对目标子树做 stat-only 遍历，按路径 diff，只写变化的行。
 * - 差异点：扫描结果分块（约 200 个文件或 300ms）到达，每批都写库并发布，
 *   所以首次建索引时列表是「边扫边出现」。
 * - 元数据：新发现的文件先把时长/标签留空，后台用有界队列逐个补齐并发布。
 */
@Single(binds = [AudioFileIndex::class])
class RoomAudioFileIndex(
    private val context: Context,
    private val audioFileIndexDao: AudioFileIndexDao,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
) : AudioFileIndex {

    private val scanner = FileSystemAudioScanner(context = context)

    /** 保护索引数据；扫描期间不长期持有，删除可以随时插进来。 */
    private val stateMutex = Mutex()

    /** 串行化扫描，避免同一时间多轮遍历同时写索引。 */
    private val scanMutex = Mutex()

    private val index = HashMap<String, AudioIndexEntry>()
    private var loaded = false

    private val lastRefreshAt = HashMap<String, Long>()
    private val metadataRequested = HashSet<String>()
    private val metadataQueue = Channel<String>(capacity = Channel.UNLIMITED)

    private val _entries = MutableStateFlow<List<AudioIndexEntry>?>(value = null)
    override val entries: StateFlow<List<AudioIndexEntry>?> = _entries.asStateFlow()

    init {
        applicationScope.launch(Dispatchers.IO) { stateMutex.withLock { loadLocked() } }
        applicationScope.launch(Dispatchers.IO) { drainMetadataQueue() }
    }

    override fun requestRefresh(folderPath: String?, force: Boolean) {
        applicationScope.launch(Dispatchers.IO) {
            refresh(
                folderPath = folderPath,
                minIntervalMillis = when {
                    force -> 0L
                    folderPath == null -> FULL_REFRESH_INTERVAL_MS
                    else -> FOLDER_REFRESH_INTERVAL_MS
                },
            )
        }
    }

    override fun removePaths(paths: Collection<String>) {
        if (paths.isEmpty()) return
        applicationScope.launch(Dispatchers.IO) {
            stateMutex.withLock {
                loadLocked()
                val removed = paths.filter { path -> index.remove(path) != null }
                if (removed.isEmpty()) return@withLock
                runCatching { audioFileIndexDao.deleteByPaths(removed) }
                publishLocked()
            }
        }
    }

    override suspend fun find(path: String): AudioIndexEntry? = stateMutex.withLock {
        loadLocked()
        index[path]
    }

    /**
     * 立即刷新（跳过节流），供测试和需要同步结果的调用方使用。
     */
    internal suspend fun refresh(folderPath: String?, minIntervalMillis: Long = 0) {
        scanMutex.withLock {
            val key = folderPath ?: ALL_VOLUMES_KEY
            val now = System.currentTimeMillis()
            stateMutex.withLock {
                loadLocked()
                val last = lastRefreshAt[key]
                if (last != null && minIntervalMillis > 0 && now - last < minIntervalMillis) {
                    return
                }
                lastRefreshAt[key] = now
            }

            // 边扫边发布：每批到达就 diff + 写库 + publish，列表一批批变长
            val seen = HashSet<String>()
            val complete = scanner.scan(folderPath = folderPath) { batch ->
                stateMutex.withLock {
                    applyBatchLocked(batch = batch, seen = seen)
                }
            }
            // 读不到目录时不能把「没有权限」误判成「文件被删除」
            if (!complete) return

            stateMutex.withLock {
                removeMissingLocked(folderPath = folderPath, seen = seen)
            }
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        val rows = runCatching { audioFileIndexDao.getAll() }.getOrElse { emptyList() }
        for (row in rows) {
            index[row.path] = row.toEntry()
        }
        loaded = true
        publishLocked()
        rows.forEach { row ->
            if (!row.metadataLoaded) requestMetadataLocked(path = row.path)
        }
    }

    private suspend fun applyBatchLocked(
        batch: List<FileSystemAudioScanner.ScannedAudio>,
        seen: MutableSet<String>,
    ) {
        val changed = ArrayList<AudioFileIndexEntity>(batch.size)
        for (file in batch) {
            seen.add(file.path)
            val current = index[file.path]
            if (current == null) {
                val entry = AudioIndexEntry(
                    path = file.path,
                    parentPath = file.parentPath,
                    displayName = file.displayName,
                    size = file.size,
                    dateModified = file.dateModified,
                    duration = 0,
                    artist = "",
                    album = "",
                    title = "",
                    metadataLoaded = false,
                    noMediaHidden = file.noMediaHidden,
                )
                index[file.path] = entry
                changed.add(entry.toEntity())
                requestMetadataLocked(path = file.path)
                continue
            }

            val contentChanged = current.size != file.size ||
                current.dateModified != file.dateModified
            val changedOther = current.displayName != file.displayName ||
                current.parentPath != file.parentPath ||
                current.noMediaHidden != file.noMediaHidden
            if (!contentChanged && !changedOther) continue

            val entry = current.copy(
                parentPath = file.parentPath,
                displayName = file.displayName,
                size = file.size,
                dateModified = file.dateModified,
                noMediaHidden = file.noMediaHidden,
                metadataLoaded = if (contentChanged) false else current.metadataLoaded,
            )
            index[file.path] = entry
            changed.add(entry.toEntity())
            if (contentChanged) requestMetadataLocked(path = file.path)
        }

        if (changed.isEmpty()) return
        upsertChunked(entities = changed)
        publishLocked()
    }

    /** 扫描完整结束后，把本次没扫到的旧条目删掉（删除/重命名/移走的文件）。 */
    private suspend fun removeMissingLocked(folderPath: String?, seen: Set<String>) {
        val prefix = folderPath
            ?.trimEnd(File.separatorChar)
            ?.let { "$it${File.separator}" }
        val removed = index.values.filter { entry ->
            (prefix == null || entry.path.startsWith(prefix)) && entry.path !in seen
        }
        if (removed.isEmpty()) return

        removed.forEach { entry ->
            index.remove(entry.path)
            metadataRequested.remove(entry.path)
        }
        deleteChunked(paths = removed.map { entry -> entry.path })
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
            // 读标签很慢，放在锁外做
            val readings = batch.map { path -> path to readAudioMetadata(path = path) }

            stateMutex.withLock {
                val updated = ArrayList<AudioFileIndexEntity>(readings.size)
                for ((path, metadata) in readings) {
                    metadataRequested.remove(path)
                    val current = index[path] ?: continue
                    if (metadata == null) continue
                    if (current.metadataLoaded && current.metadata == metadata) continue
                    val entry = current.copy(
                        duration = metadata.duration,
                        artist = metadata.artist,
                        album = metadata.album,
                        title = metadata.title,
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

    private val AudioIndexEntry.metadata: AudioMetadata
        get() = AudioMetadata(
            duration = duration,
            title = title,
            artist = artist,
            album = album,
        )

    private fun requestMetadataLocked(path: String) {
        if (metadataRequested.add(path)) {
            metadataQueue.trySend(element = path)
        }
    }

    private fun publishLocked() {
        _entries.value = index.values.toList()
    }

    private suspend fun upsertChunked(entities: List<AudioFileIndexEntity>) {
        entities.chunked(size = UPSERT_CHUNK_SIZE).forEach { chunk ->
            audioFileIndexDao.upsertAll(entries = chunk)
        }
    }

    private suspend fun deleteChunked(paths: List<String>) {
        paths.chunked(size = DELETE_CHUNK_SIZE).forEach { chunk ->
            audioFileIndexDao.deleteByPaths(paths = chunk)
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

private fun AudioFileIndexEntity.toEntry(): AudioIndexEntry = AudioIndexEntry(
    path = path,
    parentPath = parentPath,
    displayName = displayName,
    size = size,
    dateModified = dateModified,
    duration = duration,
    artist = artist,
    album = album,
    title = title,
    metadataLoaded = metadataLoaded,
    noMediaHidden = noMediaHidden,
)

private fun AudioIndexEntry.toEntity(): AudioFileIndexEntity = AudioFileIndexEntity(
    path = path,
    parentPath = parentPath,
    displayName = displayName,
    size = size,
    dateModified = dateModified,
    duration = duration,
    artist = artist,
    album = album,
    title = title,
    metadataLoaded = metadataLoaded,
    noMediaHidden = noMediaHidden,
)
