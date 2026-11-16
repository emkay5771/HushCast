package app.podiumpodcasts.podium.ui.vm.importing

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.podiumpodcasts.podium.api.db.AppDatabase
import app.podiumpodcasts.podium.api.opml.model.OpmlFile
import app.podiumpodcasts.podium.api.opml.model.OpmlOutline
import app.podiumpodcasts.podium.manager.PodcastManager
import app.podiumpodcasts.podium.utils.normalizeOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface State {
    object SelectFile : State
    data class InvalidFile(val reason: String) : State
    data class SelectOutlines(val file: OpmlFile, val outlines: List<OpmlOutline>) : State
    object Unpacking : State
    object Done : State
}

class OpmlImportingViewModel : ViewModel() {

    val state = mutableStateOf<State>(State.SelectFile)

    val selectedOrigins = mutableStateSetOf<String>()
    val existingOrigins = mutableStateSetOf<String>()
    val importProgress = mutableStateOf<Pair<Int, Int>?>(null)

    fun init() {
        state.value = State.SelectFile
        importProgress.value = null
    }

    fun selectFile(context: Context, db: AppDatabase, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val podcastOrigins = db.podcasts().allOrigins()
            val normalizedExisting = podcastOrigins.map { it.normalizeOrigin().lowercase() }.toSet()

            try {
                val xml = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { it.readText() }
                } ?: throw IllegalArgumentException("Unable to read OPML file")

                val file = OpmlFile.parse(xml)
                val outlines = file.body.flattenedOutlines()

                val foundExisting = HashSet<String>()
                val initialSelected = ArrayList<String>()

                outlines.forEach { outline ->
                    val url = outline.xmlUrl?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
                    val isExisting = podcastOrigins.contains(url) ||
                        normalizedExisting.contains(url.normalizeOrigin().lowercase())
                    if(isExisting) {
                        foundExisting.add(url)
                    } else {
                        initialSelected.add(url)
                    }
                }

                withContext(Dispatchers.Main) {
                    existingOrigins.clear()
                    existingOrigins.addAll(foundExisting)
                    selectedOrigins.clear()
                    selectedOrigins.addAll(initialSelected)
                    state.value = State.SelectOutlines(file, outlines)
                }
            } catch(e: Exception) {
                e.printStackTrace()

                withContext(Dispatchers.Main) {
                    state.value = State.InvalidFile(e.toString())
                }
            }
        }
    }

    fun selectAll(outlines: List<OpmlOutline>) {
        outlines.forEach { outline ->
            val url = outline.xmlUrl?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            if(!existingOrigins.contains(url)) {
                selectedOrigins.add(url)
            }
        }
    }

    fun deselectAll() {
        selectedOrigins.clear()
    }

    fun add(
        context: Context,
        podcastManager: PodcastManager
    ) {
        val toImport = selectedOrigins.toList()
        importProgress.value = 0 to toImport.size
        state.value = State.Unpacking

        viewModelScope.launch(Dispatchers.IO) {
            toImport.forEachIndexed { index, origin ->
                runCatching {
                    podcastManager.addPodcast(
                        origin = origin,
                        seedColor = null
                    )
                }.onFailure { it.printStackTrace() }

                withContext(Dispatchers.Main) {
                    importProgress.value = (index + 1) to toImport.size
                }
            }

            withContext(Dispatchers.Main) {
                importProgress.value = null
                state.value = State.Done
            }
        }
    }

}