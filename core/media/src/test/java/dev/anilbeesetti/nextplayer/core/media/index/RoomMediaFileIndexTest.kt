package dev.anilbeesetti.nextplayer.core.media.index

import dev.anilbeesetti.nextplayer.core.database.dao.MediaFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.entities.MediaFileIndexEntity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [30],
    manifest = Config.NONE,
)
class RoomMediaFileIndexTest {

    private val scope = CoroutineScope(context = SupervisorJob() + Dispatchers.IO)
    private val dao = FakeMediaFileIndexDao()
    private lateinit var root: File
    private lateinit var index: RoomMediaFileIndex

    @Before
    fun setUp() {
        root = File(RuntimeEnvironment.getApplication().cacheDir, "library").apply { mkdirs() }
        index = RoomMediaFileIndex(
            context = RuntimeEnvironment.getApplication(),
            mediaFileIndexDao = dao,
            applicationScope = scope,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        root.deleteRecursively()
    }

    @Test
    fun `refresh reflects added removed and renamed files`() = runBlocking {
        val first = video(name = "first.mp4")
        index.refresh(folderPath = root.path)
        assertEquals(listOf(first.path), currentPaths())

        val second = video(name = "second.mkv")
        index.refresh(folderPath = root.path)
        assertEquals(setOf(first.path, second.path), currentPaths().toSet())

        val renamed = File(root, "renamed.mkv")
        check(second.renameTo(renamed))
        index.refresh(folderPath = root.path)
        assertEquals(setOf(first.path, renamed.path), currentPaths().toSet())

        check(first.delete())
        index.refresh(folderPath = root.path)
        assertEquals(listOf(renamed.path), currentPaths())

        // 数据库里的缓存和内存索引保持一致
        assertEquals(setOf(renamed.path), dao.rows.keys)
    }

    @Test
    fun `refresh keeps cache when a folder no longer exists only removes its files`() = runBlocking {
        val nested = File(root, "nested").apply { mkdirs() }
        val outer = video(name = "outer.mp4")
        val inner = video(name = "nested/inner.mp4")
        index.refresh(folderPath = root.path)
        assertEquals(setOf(outer.path, inner.path), currentPaths().toSet())

        check(nested.deleteRecursively())
        index.refresh(folderPath = root.path)
        assertEquals(listOf(outer.path), currentPaths())
    }

    @Test
    fun `files under a no media marker are recorded as hidden`() = runBlocking {
        val hidden = File(root, "hidden").apply { mkdirs() }
        File(hidden, ".nomedia").writeText(text = "")
        val video = video(name = "hidden/video.mp4")

        index.refresh(folderPath = root.path)

        val entry = index.find(path = video.path)
        assertNotNull(entry)
        assertTrue(entry!!.noMediaHidden)
    }

    @Test
    fun `cached rows are served before a scan runs`() = runBlocking {
        val cached = File(root, "cached.mp4").apply { writeText(text = "x") }
        dao.rows[cached.path] = MediaFileIndexEntity(
            path = cached.path,
            parentPath = root.path,
            displayName = cached.name,
            size = cached.length(),
            dateModified = cached.lastModified() / 1000,
            duration = 42_000,
            width = 1920,
            height = 1080,
            metadataLoaded = true,
            noMediaHidden = false,
        )

        val freshIndex = RoomMediaFileIndex(
            context = RuntimeEnvironment.getApplication(),
            mediaFileIndexDao = dao,
            applicationScope = scope,
        )
        val entries = freshIndex.entries
        val deadline = System.currentTimeMillis() + 5_000
        while (entries.value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        val entry = entries.value?.firstOrNull { it.path == cached.path }
        assertNotNull(entry)
        assertEquals(42_000L, entry!!.duration)
    }

    private fun video(name: String): File {
        val file = File(root, name)
        file.parentFile?.mkdirs()
        file.writeText(text = "video")
        return file
    }

    private fun currentPaths(): List<String> =
        index.entries.value.orEmpty().map { entry -> entry.path }.sorted()

    private class FakeMediaFileIndexDao : MediaFileIndexDao {
        val rows = LinkedHashMap<String, MediaFileIndexEntity>()

        override suspend fun getAll(): List<MediaFileIndexEntity> = rows.values.toList()

        override suspend fun upsertAll(entries: List<MediaFileIndexEntity>) {
            entries.forEach { entry -> rows[entry.path] = entry }
        }

        override suspend fun deleteByPaths(paths: List<String>) {
            paths.forEach { path -> rows.remove(path) }
        }

        override suspend fun clear() {
            rows.clear()
        }
    }
}
