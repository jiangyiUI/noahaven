package dev.anilbeesetti.nextplayer.core.media.index

import android.content.Context
import dev.anilbeesetti.nextplayer.core.common.Utils
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.database.dao.BookDao
import dev.anilbeesetti.nextplayer.core.database.entities.BookEntity
import dev.anilbeesetti.nextplayer.core.model.BookFile
import dev.anilbeesetti.nextplayer.core.model.BookFormat
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 书籍白名单索引，照漫画库实现：
 * 1. 扫描白名单目录下的 txt/epub，索引持久化到 Room；
 * 2. EPUB 扫描时只读 OPF 元数据（标题/作者/封面），不解正文；
 * 3. 刷新手动保留阅读进度，只有文件本身变化才重读元数据。
 */
@Single(binds = [BookFileIndex::class])
class RoomBookFileIndex(
    private val dao: BookDao,
    private val context: Context,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
) : BookFileIndex {

    private val stateMutex = Mutex()
    private val scanMutex = Mutex()
    private val index = HashMap<String, BookFile>()
    private var loaded = false
    private val lastRefreshAt = HashMap<String, Long>()

    private val _entries = MutableStateFlow<List<BookFile>?>(null)
    override val entries: StateFlow<List<BookFile>?> = _entries.asStateFlow()

    private val coversDir: File get() = File(context.cacheDir, "books/covers").apply { mkdirs() }

    init {
        applicationScope.launch(Dispatchers.IO) {
            stateMutex.withLock { loadLocked() }
        }
    }

    override fun requestRefresh(
        whitelistRoots: Collection<String>,
        folderPath: String?,
        force: Boolean,
    ) {
        applicationScope.launch(Dispatchers.IO) {
            refresh(
                whitelistRoots = whitelistRoots,
                folderPath = folderPath,
                minIntervalMillis = when {
                    force -> 0L
                    folderPath == null -> FULL_REFRESH_INTERVAL_MS
                    else -> FOLDER_REFRESH_INTERVAL_MS
                },
            )
        }
    }

    override suspend fun refreshNow(whitelistRoots: Collection<String>, folderPath: String?) {
        withContext(Dispatchers.IO) {
            refresh(
                whitelistRoots = whitelistRoots,
                folderPath = folderPath,
                minIntervalMillis = 0L,
            )
        }
    }

    override suspend fun find(path: String): BookFile? = stateMutex.withLock {
        loadLocked()
        index[path]
    }

    override suspend fun saveProgress(
        path: String,
        chapterIndex: Int,
        charOffset: Int,
        percent: Float,
        finished: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            runCatching {
                dao.updateProgress(
                    path = path,
                    chapterIndex = chapterIndex,
                    charOffset = charOffset,
                    percent = percent,
                    openedAt = now,
                    finished = finished,
                )
            }
            stateMutex.withLock {
                val existing = index[path] ?: return@withLock
                val updated = existing.copy(
                    chapterIndex = chapterIndex,
                    charOffset = charOffset,
                    percent = percent,
                    lastOpenedAt = now,
                    finished = finished,
                )
                if (updated != existing) {
                    index[path] = updated
                    publishLocked()
                }
            }
        }
    }

    override suspend fun touch(path: String) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            runCatching { dao.touch(path = path, openedAt = now) }
            stateMutex.withLock {
                val existing = index[path] ?: return@withLock
                val updated = existing.copy(lastOpenedAt = now)
                if (updated != existing) {
                    index[path] = updated
                    publishLocked()
                }
            }
        }
    }

    override suspend fun setFinished(path: String, finished: Boolean) {
        withContext(Dispatchers.IO) {
            runCatching { dao.setFinished(path = path, finished = finished) }
            stateMutex.withLock {
                val existing = index[path] ?: return@withLock
                val updated = existing.copy(finished = finished)
                if (updated != existing) {
                    index[path] = updated
                    publishLocked()
                }
            }
        }
    }

    override suspend fun moveProgress(fromPath: String, toPath: String) {
        if (fromPath == toPath) return
        withContext(Dispatchers.IO) {
            stateMutex.withLock {
                loadLocked()
                val existing = index[fromPath] ?: return@withLock
                val file = File(toPath)
                // title 若是从旧文件名派生的（txt 或未取到元数据标题的 epub），随文件名更新；
                // 否则是 epub 元数据标题，不随文件名变化，保留。
                // 注意：刷新时 reconcile 优先保留现有 title，这里不改就永远是旧名。
                val oldDerivedTitle = existing.displayName.substringBeforeLast('.')
                val newTitle = if (existing.title.isBlank() || existing.title == oldDerivedTitle) {
                    file.name.substringBeforeLast('.')
                } else {
                    existing.title
                }
                val moved = existing.copy(
                    path = toPath,
                    parentPath = file.parentFile?.absolutePath.orEmpty(),
                    displayName = file.name,
                    title = newTitle,
                )
                index.remove(fromPath)
                index[toPath] = moved
                publishLocked()
                runCatching {
                    dao.deleteByPaths(listOf(fromPath))
                    dao.upsertAll(listOf(moved.toEntity()))
                }
            }
        }
    }

    override suspend fun deleteProgress(path: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                dao.updateProgress(
                    path = path,
                    chapterIndex = 0,
                    charOffset = 0,
                    percent = 0f,
                    openedAt = 0L,
                    finished = false,
                )
            }
            stateMutex.withLock {
                val existing = index[path] ?: return@withLock
                val updated = existing.copy(
                    chapterIndex = 0,
                    charOffset = 0,
                    percent = 0f,
                    lastOpenedAt = 0L,
                    finished = false,
                )
                if (updated != existing) {
                    index[path] = updated
                    publishLocked()
                }
            }
        }
    }

    private suspend fun refresh(
        whitelistRoots: Collection<String>,
        folderPath: String?,
        minIntervalMillis: Long,
    ) {
        scanMutex.withLock {
            val roots = outermostRoots(whitelistRoots)
            if (roots.isEmpty()) {
                stateMutex.withLock {
                    index.clear()
                    publishLocked()
                }
                runCatching { dao.clear() }
                return@withLock
            }
            val key = folderPath ?: ALL_ROOTS_KEY
            val now = System.currentTimeMillis()
            val snapshot = stateMutex.withLock {
                loadLocked()
                val last = lastRefreshAt[key]
                if (last != null && minIntervalMillis > 0 && now - last < minIntervalMillis) {
                    return@withLock null
                }
                lastRefreshAt[key] = now
                // 扫描期间阅读页可能更新进度，先取快照，避免并发读 HashMap
                HashMap(index)
            } ?: return

            val targets = if (folderPath == null) {
                roots.map { it to it }
            } else {
                roots.filter { folderPath.isWithin(it) }.map { it to folderPath }
            }

            val seen = HashSet<String>()
            val upsertBatch = ArrayList<BookEntity>()

            for ((root, target) in targets) {
                val found = collectBooksInFolder(folderPath = target, whitelistRoot = root)
                for (scanned in found) {
                    seen.add(scanned.path)
                    val current = snapshot[scanned.path]
                    val changed = current == null ||
                        current.size != scanned.size ||
                        current.dateModified != scanned.dateModified ||
                        current.parentPath != scanned.parentPath ||
                        current.whitelistRoot != scanned.whitelistRoot ||
                        current.displayName != scanned.displayName ||
                        current.noMediaHidden != scanned.noMediaHidden

                    // 文件没变但还没封面（EPUB 上次解析失败/尚无封面缓存），再试一次
                    val needsCover = scanned.format == BookFormat.EPUB &&
                        current?.coverCachePath == null

                    var title = current?.title.orEmpty()
                    var author = current?.author.orEmpty()
                    var coverPath = current?.coverCachePath

                    if (changed || needsCover) {
                        val metadata = readMetadata(scanned)
                        if (changed || title.isBlank()) title = metadata.title
                        if (changed || author.isBlank()) author = metadata.author
                        if (changed || coverPath == null) coverPath = metadata.coverPath
                    }
                    // txt 等标题就是文件名，必须始终跟随 displayName：
                    // app 内改名后 size/mtime 不变（changed=false），不能因此保留旧 title。
                    // EPUB 保留元数据标题，取不到时才回退文件名。
                    if (scanned.format != BookFormat.EPUB || title.isBlank()) {
                        title = scanned.displayName.substringBeforeLast('.')
                    }

                    val book = BookFile(
                        path = scanned.path,
                        parentPath = scanned.parentPath,
                        whitelistRoot = scanned.whitelistRoot,
                        displayName = scanned.displayName,
                        title = title,
                        author = author,
                        size = scanned.size,
                        dateModified = scanned.dateModified,
                        coverCachePath = coverPath,
                        formattedFileSize = Utils.formatFileSize(scanned.size),
                        noMediaHidden = scanned.noMediaHidden,
                        // 重扫时保留已读进度，否则书架进度条会被清掉
                        chapterIndex = current?.chapterIndex ?: 0,
                        charOffset = current?.charOffset ?: 0,
                        percent = current?.percent ?: 0f,
                        lastOpenedAt = current?.lastOpenedAt ?: 0L,
                        finished = current?.finished == true,
                    )
                    stateMutex.withLock { index[scanned.path] = book }
                    upsertBatch += book.toEntity()
                }
            }

            stateMutex.withLock {
                // 全量刷新以本次扫描结果为准（白名单里已删除/被移除根目录的书都清掉）；
                // 单目录刷新只清理该目录下已删除的书，否则会误删同根目录其他子目录的书。
                val stale = index.values.filter {
                    it.path !in seen && (folderPath == null || it.path.isWithin(folderPath))
                }
                if (stale.isNotEmpty()) {
                    stale.forEach { index.remove(it.path) }
                    runCatching { dao.deleteByPaths(stale.map { it.path }) }
                }
                if (upsertBatch.isNotEmpty()) {
                    upsertBatch.chunked(UPSERT_CHUNK_SIZE).forEach { runCatching { dao.upsertAll(it) } }
                }
                publishLocked()
            }
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        val rows = runCatching { dao.getAll() }.getOrElse { emptyList() }
        rows.forEach { row ->
            index[row.path] = row.toModel()
        }
        loaded = true
        publishLocked()
    }

    private suspend fun publishLocked() {
        _entries.value = index.values.sortedWith(
            compareBy<BookFile> { it.parentPath.lowercase() }
                .thenBy { it.title.lowercase() },
        )
    }

    private data class ScannedMetadata(
        val title: String,
        val author: String,
        val coverPath: String?,
    )

    private fun readMetadata(scanned: ScannedBook): ScannedMetadata {
        if (scanned.format == BookFormat.TXT) {
            return ScannedMetadata(
                title = scanned.displayName.substringBeforeLast('.'),
                author = "",
                coverPath = null,
            )
        }
        val metadata = runCatching { BookMetadataReader.readEpub(File(scanned.path)) }.getOrNull()
            ?: return ScannedMetadata(
                title = scanned.displayName.substringBeforeLast('.'),
                author = "",
                coverPath = null,
            )
        val coverPath = metadata.coverBytes?.let { bytes ->
            runCatching {
                val file = File(coversDir, sha1(scanned.path) + (metadata.coverExtension ?: ".jpg"))
                file.writeBytes(bytes)
                file.absolutePath
            }.getOrNull()
        }
        return ScannedMetadata(
            title = metadata.title.ifBlank { scanned.displayName.substringBeforeLast('.') },
            author = metadata.author,
            coverPath = coverPath,
        )
    }

    private companion object {
        const val ALL_ROOTS_KEY = "/"
        const val FOLDER_REFRESH_INTERVAL_MS = 15_000L
        const val FULL_REFRESH_INTERVAL_MS = 60_000L
        const val UPSERT_CHUNK_SIZE = 90
    }
}

internal data class ScannedBook(
    val path: String,
    val parentPath: String,
    val whitelistRoot: String,
    val displayName: String,
    val size: Long,
    val dateModified: Long,
    val noMediaHidden: Boolean,
) {
    val format: BookFormat get() = BookFormat.fromPath(path)
}

internal fun collectBooksInFolder(folderPath: String, whitelistRoot: String): List<ScannedBook> {
    val folder = File(folderPath)
    if (!folder.exists() || !folder.isDirectory) return emptyList()
    val results = ArrayList<ScannedBook>()
    walkBooksRecursive(folder, whitelistRoot, noMediaHidden = false, out = results)
    return results
}

private fun walkBooksRecursive(
    dir: File,
    whitelistRoot: String,
    noMediaHidden: Boolean,
    out: MutableList<ScannedBook>,
) {
    val children = dir.listFiles() ?: return
    val hidden = noMediaHidden || children.any {
        it.name.equals(".nomedia", ignoreCase = true)
    }
    for (f in children) {
        when {
            f.isDirectory -> walkBooksRecursive(f, whitelistRoot, hidden, out)

            f.isFile && isBookFile(f.name) -> out += ScannedBook(
                path = f.absolutePath,
                parentPath = f.parentFile?.absolutePath ?: dir.absolutePath,
                whitelistRoot = whitelistRoot,
                displayName = f.name,
                size = f.length(),
                dateModified = f.lastModified(),
                noMediaHidden = hidden,
            )
        }
    }
}

private fun isBookFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext == "txt" || ext == "epub"
}

private fun normalizeRoot(path: String): String =
    File(path).absolutePath.trimEnd(File.separatorChar).ifEmpty { File.separator }

private fun outermostRoots(roots: Collection<String>): List<String> = roots
    .filter { it.isNotBlank() }
    .map(::normalizeRoot)
    .distinct()
    .filter { root ->
        roots.map(::normalizeRoot).none { other ->
            other != root && root.isWithin(other)
        }
    }

private fun String.isWithin(root: String): Boolean =
    this == root || startsWith(root.trimEnd(File.separatorChar) + File.separator)

private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun BookEntity.toModel(): BookFile = BookFile(
    path = path,
    parentPath = parentPath,
    whitelistRoot = whitelistRoot,
    displayName = displayName,
    title = title,
    author = author,
    size = fileSize,
    dateModified = dateModified,
    coverCachePath = coverPath,
    formattedFileSize = Utils.formatFileSize(fileSize),
    noMediaHidden = noMediaHidden,
    chapterIndex = chapterIndex,
    charOffset = charOffset,
    percent = percent,
    lastOpenedAt = lastOpenedAt,
    finished = finished,
)

private fun BookFile.toEntity(): BookEntity = BookEntity(
    path = path,
    parentPath = parentPath,
    whitelistRoot = whitelistRoot,
    displayName = displayName,
    title = title,
    author = author,
    format = format.name,
    coverPath = coverCachePath,
    fileSize = size,
    dateModified = dateModified,
    noMediaHidden = noMediaHidden,
    chapterIndex = chapterIndex,
    charOffset = charOffset,
    percent = percent,
    lastOpenedAt = lastOpenedAt,
    finished = finished,
)
