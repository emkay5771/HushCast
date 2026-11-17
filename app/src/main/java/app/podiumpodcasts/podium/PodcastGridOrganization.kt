package app.podiumpodcasts.podium

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
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
    private const val LEGACY_PREFS_NAME = "hushcast_podcast_grid"
    private const val LEGACY_KEY_STATE = "grid_state_json"

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(PodcastGridState())
    val state: StateFlow<PodcastGridState> = _state.asStateFlow()
    private val ioScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    @Volatile
    private var initialized = false

    suspend fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        val repo = SettingsRepository(appContext)
        val dsRaw = repo.appearance.podcastGridStateRaw.firstOrNull()?.takeIf { it.isNotBlank() }
        if (dsRaw != null) {
            runCatching { json.decodeFromString<PodcastGridState>(dsRaw) }
                .getOrNull()
                ?.let { _state.value = it }
        } else {
            // Migrate legacy SharedPreferences if present
            val legacyPrefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val legacyRaw = legacyPrefs.getString(LEGACY_KEY_STATE, null)
            if (legacyRaw != null) {
                runCatching { json.decodeFromString<PodcastGridState>(legacyRaw) }
                    .getOrNull()
                    ?.let {
                        _state.value = it
                        repo.appearance.setPodcastGridStateRaw(legacyRaw)
                    }
                legacyPrefs.edit().clear().apply()
            }
        }
        initialized = true
    }

    private fun ensureInitSync(context: Context) {
        if (!initialized) {
            // Synchronous fallback if called from a non-suspend UI callback before init completes
            val legacyRaw = context.applicationContext
                .getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
                .getString(LEGACY_KEY_STATE, null)
            if (legacyRaw != null && _state.value == PodcastGridState()) {
                runCatching { json.decodeFromString<PodcastGridState>(legacyRaw) }
                    .getOrNull()
                    ?.let { _state.value = it }
            }
        }
    }

    private fun save(context: Context, next: PodcastGridState) {
        _state.value = next
        val encoded = json.encodeToString(next)
        val appContext = context.applicationContext
        ioScope.launch {
            SettingsRepository(appContext).appearance.setPodcastGridStateRaw(encoded)
        }
    }

    fun folderKey(folderId: String) = "folder:$folderId"
    fun podcastKey(origin: String) = "podcast:$origin"

    fun createFolder(
        context: Context,
        name: String,
        colorIndex: Int = 0,
        initialOrigins: List<String> = emptyList()
    ): PodcastFolder {
        ensureInitSync(context)
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
        ensureInitSync(context)
        val cur = _state.value
        val updated = cur.folders.map {
            if (it.id == folderId) it.copy(name = newName.trim().ifEmpty { it.name }) else it
        }
        save(context, cur.copy(folders = updated))
    }

    fun deleteFolder(context: Context, folderId: String) {
        ensureInitSync(context)
        val cur = _state.value
        val target = cur.folders.find { it.id == folderId } ?: return
        val restoredKeys = target.origins.map { podcastKey(it) }
        val newOrder = cur.customOrder.flatMap { k ->
            if (k == folderKey(folderId)) restoredKeys else listOf(k)
        }.distinct()
        save(context, cur.copy(folders = cur.folders.filterNot { it.id == folderId }, customOrder = newOrder))
    }

    fun addPodcastToFolder(context: Context, origin: String, folderId: String) {
        ensureInitSync(context)
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
        ensureInitSync(context)
        val cur = _state.value
        val updatedFolders = cur.folders.map { f ->
            if (f.id == folderId) f.copy(origins = f.origins - origin) else f
        }
        val newOrder = (cur.customOrder + podcastKey(origin)).distinct()
        save(context, cur.copy(folders = updatedFolders, customOrder = newOrder))
    }

    fun reorderVisibleKeys(context: Context, visibleKeys: List<String>, fromIndex: Int, toIndex: Int) {
        ensureInitSync(context)
        if (fromIndex !in visibleKeys.indices || toIndex !in visibleKeys.indices || fromIndex == toIndex) return
        val mutable = visibleKeys.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        val cur = _state.value
        val remaining = cur.customOrder.filterNot { it in mutable }
        save(context, cur.copy(customOrder = mutable + remaining))
    }

    fun moveFolderPodcast(context: Context, folderId: String, origin: String, delta: Int) {
        ensureInitSync(context)
        val cur = _state.value
        val updatedFolders = cur.folders.map { f ->
            if (f.id != folderId) f
            else {
                val idx = f.origins.indexOf(origin)
                val target = (idx + delta).coerceIn(0, (f.origins.size - 1).coerceAtLeast(0))
                if (idx < 0 || idx == target) f
                else {
                    val mutable = f.origins.toMutableList()
                    val moved = mutable.removeAt(idx)
                    mutable.add(target, moved)
                    f.copy(origins = mutable)
                }
            }
        }
        save(context, cur.copy(folders = updatedFolders))
    }

    fun moveFolder(context: Context, folderId: String, delta: Int) {
        ensureInitSync(context)
        val cur = _state.value
        val idx = cur.folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, (cur.folders.size - 1).coerceAtLeast(0))
        if (idx == target) return

        val mutableFolders = cur.folders.toMutableList()
        val movedFolder = mutableFolders.removeAt(idx)
        mutableFolders.add(target, movedFolder)

        val key = folderKey(folderId)
        val targetKey = folderKey(mutableFolders[if (delta > 0) target - 1 else target + 1].id)
        val order = cur.customOrder.toMutableList()
        if (key !in order) order.add(key)
        if (targetKey !in order) order.add(targetKey)
        val orderFrom = order.indexOf(key)
        val orderTo = order.indexOf(targetKey)
        if (orderFrom >= 0 && orderTo >= 0 && orderFrom != orderTo) {
            val movedKey = order.removeAt(orderFrom)
            order.add(orderTo, movedKey)
        }

        save(context, cur.copy(folders = mutableFolders, customOrder = order))
    }
}
