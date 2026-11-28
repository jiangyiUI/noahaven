package dev.anilbeesetti.nextplayer.feature.videopicker.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.anilbeesetti.nextplayer.core.model.Folder
import dev.anilbeesetti.nextplayer.core.model.Video
import java.io.Serializable

@Composable
fun rememberSelectionManager(): SelectionManager =
    rememberSaveable(saver = SelectionManager.Saver) { SelectionManager() }

@Stable
class SelectionManager(
    initialSelectionItems: Set<SelectionItem> = emptySet(),
    initialIsInSelectionMode: Boolean = false,
) {
    var selectionItems: Set<SelectionItem> by mutableStateOf(value = initialSelectionItems)
        private set

    var isInSelectionMode: Boolean by mutableStateOf(value = initialIsInSelectionMode)
        private set

    val isSingleVideoSelected: Boolean by derivedStateOf {
        selectionItems.size == 1 &&
            selectionItems.first() is SelectionItem.Video
    }

    val isSingleFolderSelected: Boolean by derivedStateOf {
        selectionItems.size == 1 &&
            selectionItems.first() is SelectionItem.Folder
    }

    /** 单选时取出那一个文件夹，用于重命名等只对单个文件夹生效的操作。 */
    val singleSelectedFolder: SelectionItem.Folder? by derivedStateOf {
        selectionItems.singleOrNull() as? SelectionItem.Folder
    }

    fun toggleFolderSelection(folder: Folder) {
        val selectedFolder = selectionItems.find { it.id == folder.path }
        selectionItems = if (selectedFolder != null) {
            selectionItems - selectedFolder
        } else {
            selectionItems + folder.toSelectedFolder()
        }
        if (selectionItems.isNotEmpty()) {
            enterSelectionMode()
        } else {
            exitSelectionMode()
        }
    }

    fun toggleVideoSelection(video: Video) {
        val selectedVideo = selectionItems.find { it.id == video.uriString }
        selectionItems = if (selectedVideo != null) {
            selectionItems - selectedVideo
        } else {
            selectionItems + video.toSelectedVideo()
        }
        if (selectionItems.isNotEmpty()) {
            enterSelectionMode()
        } else {
            exitSelectionMode()
        }
    }

    fun toggleSelection(item: SelectionItem) {
        val selectedItem = selectionItems.find { it.id == item.id }
        selectionItems = if (selectedItem != null) {
            selectionItems - selectedItem
        } else {
            selectionItems + item
        }
        if (selectionItems.isNotEmpty()) {
            enterSelectionMode()
        } else {
            exitSelectionMode()
        }
    }

    fun isSelected(id: String): Boolean = selectionItems.any { it.id == id }

    fun selectAll(items: Collection<SelectionItem>) {
        if (items.isEmpty()) return
        enterSelectionMode()
        selectionItems = items.toSet()
    }

    fun selectFolder(folder: Folder) {
        enterSelectionMode()
        selectionItems = selectionItems + folder.toSelectedFolder()
    }

    fun selectVideo(video: Video) {
        enterSelectionMode()
        selectionItems = selectionItems + video.toSelectedVideo()
    }

    fun clearSelection() {
        selectionItems = emptySet()
    }

    fun enterSelectionMode() {
        isInSelectionMode = true
    }

    fun exitSelectionMode() {
        isInSelectionMode = false
        selectionItems = emptySet()
    }

    fun isFolderSelected(folder: Folder): Boolean = selectionItems.find {
        it.id == folder.path
    } != null

    fun isVideoSelected(video: Video): Boolean = selectionItems.find {
        it.id == video.uriString
    } != null

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Saver = Saver<SelectionManager, Map<String, Any>>(
            save = {
                mapOf(
                    "selectionItems" to it.selectionItems,
                    "isInSelectionMode" to it.isInSelectionMode,
                )
            },
            restore = {
                SelectionManager(
                    initialSelectionItems =
                        (it["selectionItems"] as? Set<SelectionItem>) ?: emptySet(),
                    initialIsInSelectionMode = it["isInSelectionMode"] as? Boolean ?: false,
                )
            },
        )
    }
}

sealed interface SelectionItem : Serializable {
    @Stable
    data class Folder(override val name: String, val path: String) : SelectionItem {
        override val id: String = path
    }

    @Stable
    data class Video(override val name: String, val uriString: String, val path: String) :
        SelectionItem {
        override val id: String = uriString
    }

    val id: String
    val name: String
}

/** 磁盘扫描出来的文件（`.nomedia` 目录里的）没有 MediaStore 记录，系统回收站不支持。 */
val SelectionItem.isFileSystemItem: Boolean
    get() = this is SelectionItem.Video && uriString.startsWith(prefix = "file://")

fun Folder.toSelectedFolder() = SelectionItem.Folder(
    name = name,
    path = path,
)

fun Video.toSelectedVideo() = SelectionItem.Video(
    name = displayName,
    uriString = uriString,
    path = path,
)
