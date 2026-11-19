package dev.anilbeesetti.nextplayer.core.media.index

import android.content.Context
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.database.dao.ComicFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.dao.ComicPasswordDao
import dev.anilbeesetti.nextplayer.core.database.dao.ComicProgressDao
import dev.anilbeesetti.nextplayer.core.database.entities.ComicFileIndexEntity
import dev.anilbeesetti.nextplayer.core.database.entities.ComicPasswordEntity
import dev.anilbeesetti.nextplayer.core.database.entities.ComicProgressEntity
import dev.anilbeesetti.nextplayer.core.model.ComicFile
import dev.anilbeesetti.nextplayer.core.model.ComicProgress
import dev.anilbeesetti.nextplayer.core.model.ComicReadingDirection
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 漫画压缩包白名单索引。相比图片索引：
 * 1. 没有"分辨率异步补齐"阶段——扫描 zip 时一次性把内部条目读完；
 * 2. 封面图扫描时同步解到缓存，无需后台队列。
 */
@Single(binds = [ComicFileIndex::class])
class RoomComicFileIndex(
    private val dao: ComicFileIndexDao,
    private val progressDao: ComicProgressDao,
    private val passwordDao: ComicPasswordDao,
    private val context: Context,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val applicationScope: CoroutineScope,
) : ComicFileIndex {

    private val stateMutex = Mutex()
    private val scanMutex = Mutex()
    private val index = HashMap<String, ComicFile>()
    private var loaded = false
    private val lastRefreshAt = HashMap<String, Long>()

    /** 封面缓存路径（zipPath → coverCachePath），进程内缓存。 */
    private val coverCache = HashMap<String, String?>()

    /** 自动适配过密码但都没解开的文件，本进程内不再重试；用户输过新密码后清空，值得再试一轮。 */
    private val autoPasswordFailed = ConcurrentHashMap.newKeySet<String>()
    private var autoPasswordJob: Job? = null

    private val _entries = MutableStateFlow<List<ComicFile>?>(null)
    override val entries: StateFlow<List<ComicFile>?> = _entries.asStateFlow()

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

    override suspend fun find(path: String): ComicFile? = stateMutex.withLock {
        loadLocked()
        index[path]
    }

    override suspend fun getProgress(path: String): ComicProgress? = withContext(Dispatchers.IO) {
        progressDao.get(path)?.toModel()
    }

    override suspend fun saveProgress(progress: ComicProgress) {
        withContext(Dispatchers.IO) {
            progressDao.upsert(
                ComicProgressEntity(
                    path = progress.path,
                    lastPage = progress.lastPage,
                    readingDirection = progress.readingDirection.name,
                    updatedAt = System.currentTimeMillis(),
                    watched = progress.watched,
                ),
            )
        }
    }

    override fun observeProgress(): Flow<List<ComicProgress>> =
        progressDao.observeAll()
            // 应用外部删除文件后，清掉失效进度，避免历史记录里留下看不到内容的残行
            .onStart { pruneMissingProgress() }
            .map { entities -> entities.map { it.toModel() } }

    /** 磁盘上已经不存在的漫画，对应进度直接删除。 */
    private suspend fun pruneMissingProgress() = withContext(Dispatchers.IO) {
        val states = runCatching { progressDao.getAll() }.getOrNull().orEmpty()
        val missing = states
            .filterNot { File(it.path).exists() }
            .map { it.path }
        if (missing.isEmpty()) return@withContext
        runCatching { missing.forEach { path -> progressDao.delete(path) } }
    }

    override suspend fun deleteProgress(path: String) {
        withContext(Dispatchers.IO) { progressDao.delete(path) }
    }

    override suspend fun moveProgress(fromPath: String, toPath: String) {
        if (fromPath == toPath) return
        withContext(Dispatchers.IO) { progressDao.move(fromPath = fromPath, toPath = toPath) }
    }

    override suspend fun clearProgress() {
        withContext(Dispatchers.IO) { progressDao.clear() }
    }

    override suspend fun resolvePassword(path: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val direct = passwordDao.getEntity(path)
            if (direct != null) {
                // 老数据没有指纹，顺手补上，以后在 App 外移动/改名也能认出这个文件
                if (direct.fingerprint == null) {
                    val fp = ComicArchiveReader.fingerprint(path)
                    if (fp != null) passwordDao.upsert(direct.copy(fingerprint = fp))
                }
                // 老索引可能只有密码，页数和封面还空着；再次打开时也要补齐，而不是直接返回
                val needsMetadata = stateMutex.withLock {
                    loadLocked()
                    index[path]?.let { it.imageCount <= 0 || it.coverCachePath == null } == true
                }
                if (needsMetadata) persistPassword(path = path, password = direct.password)
                return@withContext direct.password
            }
            val saved = passwordDao.getAll()
            if (saved.isEmpty()) return@withContext null

            // 先确认这本确实要密码：未加密、损坏、低版本打不开的加密 PDF 都直接交回给 UI 处理，
            // 免得给未加密漫画白算指纹、甚至错记一个别人的密码。
            if (ComicArchiveReader.openComic(path, null) !is ComicOpenResult.PasswordRequired) {
                return@withContext null
            }

            // 在 App 外被移动/改名：路径变了但内容没变，用指纹认出同一文件，密码记录跟着迁过来
            val fingerprint = ComicArchiveReader.fingerprint(path)
            val matched = saved.firstOrNull { it.fingerprint != null && it.fingerprint == fingerprint }
            if (matched != null) {
                // 原路径还在就是复制：新旧两份都保留密码；原路径消失才按移动处理
                if (!File(matched.path).exists()) {
                    passwordDao.move(fromPath = matched.path, toPath = path)
                }
                persistPassword(path = path, password = matched.password)
                return@withContext matched.password
            }

            // 不是同一个文件：很多压缩包共用同一个密码，拿已保存的密码去重后逐个试
            for (candidate in saved.map { it.password }.distinct().take(MAX_REUSED_PASSWORDS)) {
                when (ComicArchiveReader.openComic(path, candidate)) {
                    is ComicOpenResult.Success -> {
                        persistPassword(path = path, password = candidate)
                        return@withContext candidate
                    }

                    // 低版本系统打不开的加密 PDF，再试也没意义
                    ComicOpenResult.UnsupportedEncrypted -> return@withContext null
                    else -> Unit
                }
            }
            null
        }.getOrNull()
    }

    override suspend fun savePassword(path: String, password: String) {
        // 用户输出了新密码：之前自动试失败的文件，值得再用新密码试一轮
        autoPasswordFailed.clear()
        persistPassword(path = path, password = password)
    }

    /** 存密码 + 立刻补页数和封面。自动适配密码也走这里，但不会清空失败记录。 */
    private suspend fun persistPassword(path: String, password: String) {
        if (password.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                passwordDao.upsert(
                    ComicPasswordEntity(
                        path = path,
                        password = password,
                        fingerprint = ComicArchiveReader.fingerprint(path),
                    ),
                )
            }
            // 之前没有密码时页数/封面解不出来，这里立刻补一次：
            // 无论从漫画库、搜索页还是历史页打开，都能马上看到完整信息
            coverCache.remove(path)
            val entries = if (isPdf(path)) {
                emptyList()
            } else {
                runCatching {
                    ComicArchiveReader(path).use { it.listImageEntries() }
                }.getOrDefault(emptyList())
            }
            val imageCount = if (isPdf(path)) {
                PdfComicReader.pageCount(path, password)
            } else {
                entries.size
            }
            val cover = extractCoverLocked(context, path, entries, password)
            if (cover != null) coverCache[path] = cover
            stateMutex.withLock {
                index[path]?.let { existing ->
                    val updated = existing.copy(
                        imageCount = imageCount.takeIf { it > 0 } ?: existing.imageCount,
                        coverCachePath = cover ?: existing.coverCachePath,
                    )
                    if (updated != existing) {
                        index[path] = updated
                        runCatching { dao.upsertAll(listOf(updated.toEntity())) }
                        publishLocked()
                    }
                }
            }
        }
    }

    override suspend fun deletePassword(path: String) {
        withContext(Dispatchers.IO) { runCatching { passwordDao.delete(path) } }
    }

    override suspend fun movePassword(fromPath: String, toPath: String) {
        if (fromPath == toPath) return
        withContext(Dispatchers.IO) {
            runCatching { passwordDao.move(fromPath = fromPath, toPath = toPath) }
        }
    }

    private fun ComicProgressEntity.toModel(): ComicProgress = ComicProgress(
        path = path,
        lastPage = lastPage,
        readingDirection = runCatching {
            ComicReadingDirection.valueOf(readingDirection)
        }.getOrDefault(ComicReadingDirection.RIGHT_TO_LEFT),
        updatedAt = updatedAt,
        watched = watched,
    )

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
                dao.clear()
                return@withLock
            }
            val key = folderPath ?: ALL_ROOTS_KEY
            val now = System.currentTimeMillis()
            stateMutex.withLock {
                loadLocked()
                val last = lastRefreshAt[key]
                if (last != null && minIntervalMillis > 0 && now - last < minIntervalMillis) return
                lastRefreshAt[key] = now
            }

            val targets = if (folderPath == null) {
                roots.map { it to it }
            } else {
                roots.filter { folderPath.isWithin(it) }.map { it to folderPath }
            }

            val seen = HashSet<String>()
            val upsertBatch = ArrayList<ComicFileIndexEntity>()
            // 一次性拉全量密码，扫描时用它给加密漫画数页数、出封面
            val passwordMap = runCatching { passwordDao.getAll() }
                .getOrDefault(emptyList())
                .associate { it.path to it.password }

            for ((root, target) in targets) {
                val found = collectComicsInFolder(
                    folderPath = target,
                    whitelistRoot = root,
                    passwordProvider = { path -> passwordMap[path] },
                )
                for (scanned in found) {
                    seen.add(scanned.path)
                    val current = index[scanned.path]
                    val changed = current == null ||
                        current.size != scanned.size ||
                        current.dateModified != scanned.dateModified ||
                        current.imageCount != scanned.imageCount ||
                        current.parentPath != scanned.parentPath ||
                        current.whitelistRoot != scanned.whitelistRoot ||
                        current.displayName != scanned.displayName ||
                        current.noMediaHidden != scanned.noMediaHidden

                    // 封面缺失也要重建：加密压缩包第一次扫描时还没密码、生不出封面，
                    // 之后文件本身没变化（changed=false），拿到密码重扫时也必须补上封面
                    val needsCover = scanned.imageCount > 0 && coverCache[scanned.path] == null
                    if (!changed && !needsCover) continue

                    // 生成封面缓存（zip 第一张图 / pdf 第 0 页）
                    val coverPath = if (scanned.imageCount > 0) {
                        coverCache[scanned.path] ?: run {
                            val entries = if (isPdf(scanned.path)) {
                                emptyList()
                            } else {
                                runCatching {
                                    ComicArchiveReader(scanned.path).use { it.listImageEntries() }
                                }.getOrDefault(emptyList())
                            }
                            val path = extractCoverLocked(
                                context,
                                scanned.path,
                                entries,
                                passwordMap[scanned.path],
                            )
                            coverCache[scanned.path] = path
                            path
                        }
                    } else {
                        null
                    }

                    val comic = ComicFile(
                        path = scanned.path,
                        parentPath = scanned.parentPath,
                        whitelistRoot = scanned.whitelistRoot,
                        displayName = scanned.displayName,
                        size = scanned.size,
                        dateModified = scanned.dateModified,
                        imageCount = scanned.imageCount,
                        coverCachePath = coverPath,
                        formattedFileSize = dev.anilbeesetti.nextplayer.core.common.Utils
                            .formatFileSize(scanned.size),
                        noMediaHidden = scanned.noMediaHidden,
                        lastPage = 0,
                        watched = false,
                    )
                    stateMutex.withLock {
                        index[scanned.path] = comic
                    }
                    upsertBatch += comic.toEntity()
                }
            }

            // 删除不再存在的（只清理本次扫描覆盖的白名单，避免误删其它根目录）
            val scannedRoots = targets.map { it.first }.toSet()
            stateMutex.withLock {
                val stale = index.values.filter {
                    it.path !in seen && (folderPath == null || it.whitelistRoot in scannedRoots)
                }
                if (stale.isNotEmpty()) {
                    stale.forEach { index.remove(it.path) }
                    dao.deleteByPaths(stale.map { it.path })
                }
                if (upsertBatch.isNotEmpty()) {
                    upsertBatch.chunked(UPSERT_CHUNK_SIZE).forEach { dao.upsertAll(it) }
                }
                publishLocked()
            }

            // 扫描完成后，后台给还没存过密码的加密漫画试密码、取出第一页当封面
            scheduleAutoPasswordResolve()
        }
    }

    /**
     * 进漫画库后，给还没存过密码的加密漫画自动适配密码：
     * 能按指纹认出同一文件、或其他漫画的密码能解开的，就顺手记住密码并取出第一页当封面，
     * 用户不用逐个点开输密码。串行后台跑，解不开的本进程内不再反复尝试。
     */
    private fun scheduleAutoPasswordResolve() {
        if (autoPasswordJob?.isActive == true) return
        autoPasswordJob = applicationScope.launch(Dispatchers.IO) {
            // 封面或页数还空着的基本就是没密码解不开的（未加密漫画扫描时已经补全）
            val candidates = stateMutex.withLock {
                index.values
                    .filter {
                        (it.coverCachePath == null || it.imageCount <= 0) &&
                            it.path !in autoPasswordFailed
                    }
                    .map { it.path }
            }
            for (path in candidates) {
                // resolvePassword 内部：已存密码 → 指纹匹配 → 复用其他漫画密码。
                // 试对会自己存密码并补页数/封面，所以前一本试出来的密码，后面几本也能跟着解开
                if (resolvePassword(path) == null) autoPasswordFailed += path
            }
        }
    }

    private suspend fun loadLocked() {
        if (loaded) return
        val rows = runCatching { dao.getAll() }.getOrElse { emptyList() }
        // 一次性把进度读进来做内存 join，给每条 ComicFile 带上 lastPage + watched
        val progressMap = runCatching { progressDao.getAll() }
            .getOrDefault(emptyList())
            .associateBy { it.path }
        rows.forEach { row ->
            val cover = coverCache[row.path]
                ?: ComicArchiveReader.extractCover(
                    context = context,
                    zipPath = row.path,
                    imageEntries = emptyList(),
                    password = runCatching { passwordDao.get(row.path) }.getOrNull(),
                ).also {
                    coverCache[row.path] = it
                }
            val base = row.toComicFile(cover)
            val p = progressMap[row.path]
            index[row.path] = base.copy(
                lastPage = p?.lastPage ?: 0,
                watched = p?.watched == true,
            )
        }
        loaded = true
        publishLocked()
    }

    private suspend fun publishLocked() {
        _entries.value = index.values.sortedWith(
            compareBy<ComicFile> { it.parentPath.lowercase() }
                .thenBy { it.displayName.lowercase() },
        )
    }

    private suspend fun removeRootsLocked(roots: Collection<String>) {
        val normalized = roots.map { normalizeRoot(it) }.toSet()
        val removed = index.values.filter {
            it.whitelistRoot in normalized || it.path.isWithinAny(normalized)
        }
        removed.forEach { index.remove(it.path) }
        if (removed.isNotEmpty()) {
            dao.deleteByPaths(removed.map { it.path })
        }
    }

    fun removeRoots(roots: Collection<String>) {
        if (roots.isEmpty()) return
        applicationScope.launch(Dispatchers.IO) {
            scanMutex.withLock {
                stateMutex.withLock { loadLocked() }
                removeRootsLocked(roots)
                stateMutex.withLock { publishLocked() }
            }
        }
    }

    private companion object {
        const val ALL_ROOTS_KEY = "/"
        const val FOLDER_REFRESH_INTERVAL_MS = 15_000L
        const val FULL_REFRESH_INTERVAL_MS = 60_000L
        const val UPSERT_CHUNK_SIZE = 90

        /** 复用其他漫画密码时最多试几个，避免密码记录很多时打开变慢。 */
        const val MAX_REUSED_PASSWORDS = 3
    }
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

private fun String.isWithinAny(roots: Collection<String>): Boolean = roots.any { isWithin(it) }
