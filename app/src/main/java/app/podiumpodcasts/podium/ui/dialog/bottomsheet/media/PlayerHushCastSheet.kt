package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hushcast.automute.dsp.AdAutoSkipScope
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.storage.AdSegmentEntity
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.EpisodeAnalysisStatusEntity
import app.hushcast.automute.storage.MuteSegmentEntity
import app.hushcast.automute.storage.WordIndexBlob
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.chapters.ResolvedChapter
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.component.media.EpisodeReadinessBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun PlayerHushCastSheet(
    episodeId: String,
    episodeTitle: String,
    origin: String,
    isDownloaded: Boolean,
    analysisStatus: EpisodeAnalysisStatusEntity?,
    muteSegments: List<MuteSegmentEntity>,
    adSegments: List<AdSegmentEntity>,
    chapters: List<ResolvedChapter>,
    transcriptBlob: WordIndexBlob?,
    settingsRepository: SettingsRepository,
    devMode: Boolean,
    onProcessNow: () -> Unit,
    onCancelProcessing: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }

    val badge = remember(analysisStatus?.badge) {
        runCatching { analysisStatus?.badge?.let { ReadinessBadge.valueOf(it) } }.getOrNull()
            ?: ReadinessBadge.NOT_PROCESSED
    }

    val globalWordMuting by settingsRepository.hushCast.wordMutingEnabled.collectAsStateWithLifecycle(initialValue = true)
    val podcastWordMuting by remember(origin) {
        settingsRepository.hushCast.podcastWordMuting(origin)
    }.collectAsStateWithLifecycle(initialValue = null)
    val wordMutingActive = podcastWordMuting ?: globalWordMuting

    val globalAdPolicy by settingsRepository.hushCast.adPolicyMode.collectAsStateWithLifecycle(initialValue = AdPolicyMode.AUTO_SKIP)
    val podcastAdPolicyEntity by remember(origin) {
        autoMuteDb.dao().observePodcastAdPolicy(origin)
    }.collectAsStateWithLifecycle(initialValue = null)
    val adPolicy = remember(podcastAdPolicyEntity?.policyMode, globalAdPolicy) {
        podcastAdPolicyEntity?.policyMode?.let { runCatching { AdPolicyMode.valueOf(it) }.getOrNull() }
            ?: globalAdPolicy
    }
    val sponsorSkippingActive = adPolicy == AdPolicyMode.AUTO_SKIP

    val autoSkipScope by settingsRepository.hushCast.adAutoSkipScope.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.autoSkipScope
    )
    val autoSkipClasses by settingsRepository.hushCast.adAutoSkipClasses.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.autoSkipClasses
    )
    val muteActionMode by settingsRepository.hushCast.muteActionMode.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.defaultActionMode
    )
    val allRules by remember { autoMuteDb.dao().observeRules() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledRulesCount = remember(allRules) { allRules.count { it.enabled } }

    var showMoreOptions by remember { mutableStateOf(false) }

    ModalBottomSheet(
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HushCast",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                EpisodeReadinessBadge(
                    badge = badge,
                    progressPercent = analysisStatus?.progressPercent ?: 0
                )
            }

            Spacer(Modifier.height(12.dp))

            when (badge) {
                ReadinessBadge.ANALYZING -> {
                    val pct = (analysisStatus?.progressPercent ?: 0).coerceIn(0, 100)
                    Text(
                        text = analysisStatus?.stageLabel?.ifBlank { "Processing… $pct%" } ?: "Processing… $pct%",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onCancelProcessing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancel processing")
                    }
                }
                ReadinessBadge.NOT_PROCESSED -> {
                    Button(
                        onClick = onProcessNow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isDownloaded) "Process episode" else "Download & process")
                    }
                }
                else -> Unit
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // Word muting switch naming word list & count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = wordMutingActive,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            scope.launch {
                                settingsRepository.hushCast.setPodcastWordMuting(origin, enabled)
                                if (HushCastEngineBridge.currentEpisodeId == episodeId) {
                                    HushCastEngineBridge.autoMuteProcessor.isEnabled = enabled
                                }
                            }
                        }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Word muting", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "$enabledRulesCount active rules · ${muteSegments.count { it.enabled }} muted here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = wordMutingActive, onCheckedChange = null)
            }

            // Sponsor skipping switch naming sponsor count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = sponsorSkippingActive,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            val nextPolicy = if (enabled) AdPolicyMode.AUTO_SKIP else AdPolicyMode.MARK_ONLY
                            scope.launch(Dispatchers.IO) {
                                autoMuteDb.dao().upsertPodcastAdPolicy(
                                    app.hushcast.automute.storage.PodcastAdPolicyEntity(
                                        podcastOrigin = origin,
                                        policyMode = nextPolicy.name
                                    )
                                )
                                if (HushCastEngineBridge.currentEpisodeId == episodeId) {
                                    HushCastEngineBridge.adSkipObserver.policyMode = nextPolicy
                                }
                            }
                        }
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sponsor skipping", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${adSegments.size} sponsors detected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = sponsorSkippingActive, onCheckedChange = null)
            }

            Spacer(Modifier.height(8.dp))

            // Scope selector
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
            ) {
                ToggleButton(
                    checked = autoSkipScope == AdAutoSkipScope.HIGH_CONFIDENCE_ONLY,
                    onCheckedChange = {
                        if (it) {
                            scope.launch {
                                settingsRepository.hushCast.setAdAutoSkipScope(AdAutoSkipScope.HIGH_CONFIDENCE_ONLY)
                                HushCastEngineBridge.autoSkipScope = AdAutoSkipScope.HIGH_CONFIDENCE_ONLY
                            }
                        }
                    },
                    shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("High confidence")
                }
                ToggleButton(
                    checked = autoSkipScope == AdAutoSkipScope.ALL_DETECTED,
                    onCheckedChange = {
                        if (it) {
                            scope.launch {
                                settingsRepository.hushCast.setAdAutoSkipScope(AdAutoSkipScope.ALL_DETECTED)
                                HushCastEngineBridge.autoSkipScope = AdAutoSkipScope.ALL_DETECTED
                            }
                        }
                    },
                    shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("All sponsors")
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = { showMoreOptions = !showMoreOptions },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showMoreOptions) "Fewer options" else "More options")
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = if (showMoreOptions) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = null
                )
            }

            AnimatedVisibility(visible = showMoreOptions) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Mute style",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            MuteActionMode.HARD_SILENCE to "Silence",
                            MuteActionMode.BEEP to "Bleep",
                            MuteActionMode.CUSTOM_SOUND to "Custom"
                        ).forEach { (mode, label) ->
                            FilterChip(
                                selected = muteActionMode == mode,
                                onClick = {
                                    scope.launch {
                                        settingsRepository.hushCast.setMuteActionMode(mode)
                                        HushCastEngineBridge.defaultActionMode = mode
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Skip categories",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "sponsor_ad" to "Sponsors",
                            "self_promo" to "Self-promo",
                            "intro_outro" to "Intro / Outro"
                        ).forEach { (cls, label) ->
                            val selected = cls in autoSkipClasses
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val updated = if (selected) autoSkipClasses - cls else autoSkipClasses + cls
                                    scope.launch {
                                        settingsRepository.hushCast.setAdAutoSkipClasses(updated)
                                        HushCastEngineBridge.autoSkipClasses = updated
                                    }
                                },
                                label = { Text(label) }
                            )
                        }
                    }

                    if (devMode) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val uri = withContext(Dispatchers.IO) {
                                        val root = JSONObject().apply {
                                            put("episodeId", episodeId)
                                            put("title", episodeTitle)
                                            put("mutes", JSONArray().apply {
                                                muteSegments.forEach { m ->
                                                    put(JSONObject().apply {
                                                        put("startMs", m.startMs)
                                                        put("endMs", m.endMs)
                                                        put("word", m.word)
                                                        put("enabled", m.enabled)
                                                    })
                                                }
                                            })
                                            put("ads", JSONArray().apply {
                                                adSegments.forEach { a ->
                                                    put(JSONObject().apply {
                                                        put("startMs", a.startMs)
                                                        put("endMs", a.endMs)
                                                        put("confidence", a.confidence)
                                                        put("adClass", a.adClass)
                                                    })
                                                }
                                            })
                                            put("chapters", JSONArray().apply {
                                                chapters.forEach { ch ->
                                                    put(JSONObject().apply {
                                                        put("startMs", ch.startMs)
                                                        put("publishedStartMs", ch.publishedStartMs)
                                                        put("title", ch.title)
                                                        put("source", ch.source)
                                                    })
                                                }
                                            })
                                        }
                                        val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
                                        val outFile = File(exportDir, "hushcast_timeline_${episodeId.hashCode()}.json")
                                        outFile.writeText(root.toString())
                                        FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            outFile
                                        )
                                    }
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Export Timeline Data"))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Export Timeline Data (Developer)")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
