package app.podiumpodcasts.podium

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class PodcastFolder(
    val id: String,
    val name: String,
    val colorIndex: Int = 0,
    val origins: List<String> = emptyList()
)

@Serializable
data class PodcastGridState(
    val folders: List<PodcastFolder> = emptyList(),
    val customOrder: List<String> = emptyList()
)

/**
 * Persists Pocket Casts-style podcast folders and custom drag-and-drop grid order.
 */
object PodcastGridOrganization {
    private const val PREFS_NAME = "hushcast_podcast_grid"
    private const val KEY_STATE = "grid_state_json"

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(PodcastGridState())
    val state: StateFlow<PodcastGridState> = _state.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val raw = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_STATE, null)
            if (raw != null) {
                runCatching { json.decodeFromString<PodcastGridState>(raw) }
                    .getOrNull()
                    ?.let { _state.value = it }
            }
            initialized = true
        }
    }

    private fun save(context: Context, next: PodcastGridState) {
        _state.value = next
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, json.encodeToString(next))
            .apply()
    }

    fun folderKey(folderId: String) = "folder:$folderId"
    fun podcastKey(origin: String) = "podcast:$origin"

    fun createFolder(
        context: Context,
        name: String,
        colorIndex: Int = 0,
        initialOrigins: List<String> = emptyList()
    ): PodcastFolder {
        init(context)
        val cur = _state.value
        val id = UUID.randomUUID().toString().take(8)
        val cleanedFolders = cur.folders.map { f ->
            f.copy(origins = f.origins.filterNot { it in initialOrigins })
        }
        val newFolder = PodcastFolder(id = id, name = name.trim().ifEmpty { "Folder" }, colorIndex = colorIndex, origins = initialOrigins.distinct())
        val newOrder = (listOf(folderKey(id)) + cur.customOrder.filterNot { k ->
            initialOrigins.any { k == podcastKey(it) }
        }).distinct()
        save(context, cur.copy(folders = cleanedFolders + newFolder, customOrder = newOrder))
        return newFolder
    }

    fun renameFolder(context: Context, folderId: String, newName: String) {
        init(context)
        val cur = _state.value
        val updated = cur.folders.map {
            if (it.id == folderId) it.copy(name = newName.trim().ifEmpty { it.name }) else it
        }
        save(context, cur.copy(folders = updated))
    }

    fun deleteFolder(context: Context, folderId: String) {
        init(context)
        val cur = _state.value
        val target = cur.folders.find { it.id == folderId } ?: return
        val restoredKeys = target.origins.map { podcastKey(it) }
        val newOrder = cur.customOrder.flatMap { k ->
            if (k == folderKey(folderId)) restoredKeys else listOf(k)
        }.distinct()
        save(context, cur.copy(folders = cur.folders.filterNot { it.id == folderId }, customOrder = newOrder))
    }

    fun addPodcastToFolder(context: Context, origin: String, folderId: String) {
        init(context)
        val cur = _state.value
        val updatedFolders = cur.folders.map { f ->
            when {
                f.id == folderId -> f.copy(origins = (f.origins + origin).distinct())
                origin in f.origins -> f.copy(origins = f.origins - origin)
                else -> f
            }
        }
        val newOrder = cur.customOrder.filterNot { it == podcastKey(origin) }
        save(context, cur.copy(folders = updatedFolders, customOrder = newOrder))
    }

    fun removePodcastFromFolder(context: Context, origin: String, folderId: String) {
        init(context)
        val cur = _state.value
        val updatedFolders = cur.folders.map { f ->
            if (f.id == folderId) f.copy(origins = f.origins - origin) else f
        }
        val newOrder = (cur.customOrder + podcastKey(origin)).distinct()
        save(context, cur.copy(folders = updatedFolders, customOrder = newOrder))
    }

    fun reorderVisibleKeys(context: Context, visibleKeys: List<String>, fromIndex: Int, toIndex: Int) {
        init(context)
        if (fromIndex !in visibleKeys.indices || toIndex !in visibleKeys.indices || fromIndex == toIndex) return
        val mutable = visibleKeys.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val cur = _state.value
        val remaining = cur.customOrder.filterNot { it in mutable }
        save(context, cur.copy(customOrder = mutable + remaining))
    }
}
