package app.podiumpodcasts.podium.ui.dialog.bottomsheet.media

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.rounded.PlayArrow
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import app.podiumpodcasts.podium.ui.component.common.SheetOverscroll
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
    onProcessStage: (String) -> Unit = {},
    onCancelProcessing: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
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
    val bleepStyle by settingsRepository.hushCast.bleepStyle.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.bleepStyle
    )
    val muteCustomUri by settingsRepository.hushCast.muteCustomSoundUri.collectAsStateWithLifecycle(initialValue = null)
    val customSounds by settingsRepository.hushCast.customSounds.collectAsStateWithLifecycle(initialValue = emptyList())
    val skipSoundEnabled by settingsRepository.hushCast.sponsorSkipSoundEnabled.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.sponsorSkipSoundEnabled
    )
    val skipSound by settingsRepository.hushCast.skipSound.collectAsStateWithLifecycle(initialValue = HushCastEngineBridge.skipSound)
    val skipCustomUri by settingsRepository.hushCast.sponsorSkipSoundUri.collectAsStateWithLifecycle(initialValue = null)
    val allRules by remember { autoMuteDb.dao().observeRules() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val enabledRulesCount = remember(allRules) { allRules.count { it.enabled } }

    var showMoreOptions by remember { mutableStateOf(false) }

    val hasWordMutingOverride = podcastWordMuting != null
    val hasAdPolicyOverride = podcastAdPolicyEntity != null
    val hushLabels = app.podiumpodcasts.podium.ui.hushcast.HushCastLabels

    // M3: close with the sheet's slide-down, then run the action.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun hideThen(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion { after() }
    }

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = onDismiss
    ) {
        SheetOverscroll {
        PlayerSheetBlur()
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
                    androidx.compose.material3.LinearWavyProgressIndicator(
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
                        onClick = { hideThen(onProcessNow) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isDownloaded) "Process episode" else "Download & process")
                    }
                }
                ReadinessBadge.READY, ReadinessBadge.WORD_MUTE_READY -> {
                    // R4: processed episodes can be re-processed from here again.
                    val outdated = (analysisStatus?.pipelineVersion ?: 0) <
                        app.hushcast.automute.pipeline.AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
                    if (outdated) {
                        androidx.compose.material3.FilledTonalButton(
                            onClick = { hideThen(onProcessNow) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Update processing")
                        }
                        Text(
                            text = "Improved timing and transcripts are available for this episode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    } else {
                        androidx.compose.material3.TextButton(onClick = { hideThen(onProcessNow) }) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Re-process episode")
                        }
                    }

                    var showAdvancedReprocess by remember { mutableStateOf(false) }
                    val workerActive = remember(context) {
                        app.hushcast.automute.engine.HushCastPrefs.isWorkerActive(context)
                    }
                    TextButton(
                        onClick = { showAdvancedReprocess = !showAdvancedReprocess },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Advanced")
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = if (showAdvancedReprocess) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(
                        visible = showAdvancedReprocess,
                        enter = app.podiumpodcasts.podium.ui.theme.AppMotion.expandVerticallyIn(),
                        exit = app.podiumpodcasts.podium.ui.theme.AppMotion.shrinkVerticallyOut()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Re-run specific pipeline stages on the worker without re-transcribing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            listOf(
                                "SUMMARY_CHAPTERS" to "Re-run Summary & Chapters",
                                "ADS_AND_SUMMARY" to "Re-run Ads & Summary",
                                "ALIGN_WORDS" to "Re-align Words"
                            ).forEach { (stage, label) ->
                                OutlinedButton(
                                    onClick = { hideThen { onProcessStage(stage) } },
                                    enabled = workerActive,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(label)
                                }
                            }
                            if (!workerActive) {
                                Text(
                                    text = "Requires Desktop or Cloud worker to be enabled in Settings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            // I10: these two switches save an override for this podcast only; say so, and offer a
            // way back to the global setting.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "For this podcast",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                if (hasWordMutingOverride || hasAdPolicyOverride) {
                    TextButton(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                settingsRepository.hushCast.setPodcastWordMuting(origin, null)
                                autoMuteDb.dao().deletePodcastAdPolicy(origin)
                                if (HushCastEngineBridge.currentEpisodeId == episodeId) {
                                    HushCastEngineBridge.autoMuteProcessor.isEnabled = globalWordMuting
                                    HushCastEngineBridge.adSkipObserver.policyMode = globalAdPolicy
                                }
                            }
                        }
                    ) {
                        Text("Reset to default")
                    }
                }
            }

            // Word muting switch naming word list & count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = wordMutingActive,
                        role = Role.Switch,
                        onValueChange = { enabled ->
                            haptic.performHapticFeedback(
                                if (enabled) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                                else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
                            )
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
                        text = (if (hasWordMutingOverride) "Set for this podcast" else "Default (${if (globalWordMuting) "on" else "off"})") +
                            " · $enabledRulesCount active rules · ${muteSegments.count { it.enabled }} muted here",
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
                            haptic.performHapticFeedback(
                                if (enabled) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                                else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
                            )
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
                        text = (if (hasAdPolicyOverride) "Set for this podcast" else "Default (${if (globalAdPolicy == AdPolicyMode.AUTO_SKIP) "on" else "off"})") +
                            " · ${adSegments.size} ${if (adSegments.size == 1) "ad" else "ads"} detected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = sponsorSkippingActive, onCheckedChange = null)
            }

            Spacer(Modifier.height(12.dp))

            // I10: this choice is the auto-skip confidence (all podcasts), not a scope.  Say so.
            Text(
                text = "Auto-skip",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = when (autoSkipScope) {
                    AdAutoSkipScope.HIGH_CONFIDENCE_ONLY -> "Skips ads HushCast is sure about. All podcasts."
                    AdAutoSkipScope.ALL_DETECTED -> "Skips every detected ad, including likely ones. All podcasts."
                    AdAutoSkipScope.MODERATE_AND_HIGH -> "Balanced, set in Settings. All podcasts."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            val scopeChoices = listOf(
                AdAutoSkipScope.HIGH_CONFIDENCE_ONLY to "Confirmed sponsors",
                AdAutoSkipScope.ALL_DETECTED to "All detected ads"
            )
            app.podiumpodcasts.podium.ui.component.common.ConnectedSingleChoiceGroup(
                options = scopeChoices,
                selected = scopeChoices.firstOrNull { it.first == autoSkipScope } ?: scopeChoices.first(),
                onSelect = { (choice, _) ->
                    scope.launch {
                        settingsRepository.hushCast.setAdAutoSkipScope(choice)
                        HushCastEngineBridge.autoSkipScope = choice
                    }
                }
            ) { (_, label) ->
                Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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

            AnimatedVisibility(
                visible = showMoreOptions,
                enter = app.podiumpodcasts.podium.ui.theme.AppMotion.expandVerticallyIn(),
                exit = app.podiumpodcasts.podium.ui.theme.AppMotion.shrinkVerticallyOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Mute style",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    app.podiumpodcasts.podium.ui.component.common.ConnectedSingleChoiceGroup(
                        options = hushLabels.userModes,
                        selected = hushLabels.userModes.firstOrNull { hushLabels.sameUserChoice(it, muteActionMode) }
                            ?: hushLabels.userModes.first(),
                        onSelect = { mode ->
                            // "Sound" is the selected custom sound when there is one, else the tone (item 20).
                            val target = hushLabels.resolveUserChoice(mode, muteCustomUri)
                            HushCastEngineBridge.defaultActionMode = target
                            scope.launch(Dispatchers.IO) {
                                settingsRepository.hushCast.setMuteActionMode(target)
                            }
                        }
                    ) { mode ->
                        Text(
                            hushLabels.modeLabel(mode),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }

                    if (app.podiumpodcasts.podium.ui.hushcast.HushCastLabels.isSound(muteActionMode)) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Mute sound",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        app.hushcast.automute.dsp.BleepStyle.entries.forEach { style ->
                            SoundChoiceRow(
                                label = style.label + if (style == app.hushcast.automute.dsp.BleepStyle.DEFAULT) " (Default)" else "",
                                selected = muteActionMode == MuteActionMode.BEEP && bleepStyle == style,
                                onSelect = {
                                    HushCastEngineBridge.bleepStyle = style
                                    HushCastEngineBridge.defaultActionMode = MuteActionMode.BEEP
                                    HushCastEngineBridge.previewBleep(style)
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.hushCast.setBleepStyle(style)
                                        settingsRepository.hushCast.setMuteActionMode(MuteActionMode.BEEP)
                                        // A built-in tone is now the selection, so no custom sound is
                                        // selected (item 20; legacy sounds were migrated to the list).
                                        settingsRepository.hushCast.setMuteCustomSoundUri(null)
                                    }
                                },
                                onTest = { HushCastEngineBridge.previewBleep(style) }
                            )
                        }
                        customSounds.forEach { preset ->
                            SoundChoiceRow(
                                label = preset.name,
                                selected = muteActionMode == MuteActionMode.CUSTOM_SOUND && muteCustomUri == preset.uri,
                                onSelect = {
                                    HushCastEngineBridge.loadCustomMuteSound(context, preset.uri)
                                    HushCastEngineBridge.defaultActionMode = MuteActionMode.CUSTOM_SOUND
                                    HushCastEngineBridge.previewCustomSound(context, preset.uri)
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.hushCast.setMuteCustomSoundUri(preset.uri)
                                        settingsRepository.hushCast.setMuteActionMode(MuteActionMode.CUSTOM_SOUND)
                                    }
                                },
                                onTest = { HushCastEngineBridge.previewCustomSound(context, preset.uri) }
                            )
                        }
                    }

                    if (skipSoundEnabled) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = "Skip sound",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        app.podiumpodcasts.podium.SkipSound.entries.forEach { sound ->
                            SoundChoiceRow(
                                label = sound.label + if (sound == app.podiumpodcasts.podium.SkipSound.WHOOSH) " (Default)" else "",
                                selected = skipCustomUri == null && skipSound == sound,
                                onSelect = {
                                    HushCastEngineBridge.skipSound = sound
                                    HushCastEngineBridge.sponsorSkipSoundUri = null
                                    HushCastEngineBridge.previewSkipSound(context, sound)
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.hushCast.setSkipSound(sound)
                                        settingsRepository.hushCast.setSponsorSkipSoundUri(null)
                                    }
                                },
                                onTest = { HushCastEngineBridge.previewSkipSound(context, sound) }
                            )
                        }
                        customSounds.forEach { preset ->
                            SoundChoiceRow(
                                label = preset.name,
                                selected = skipCustomUri == preset.uri,
                                onSelect = {
                                    HushCastEngineBridge.sponsorSkipSoundUri = preset.uri
                                    HushCastEngineBridge.previewCustomSound(context, preset.uri)
                                    scope.launch(Dispatchers.IO) {
                                        settingsRepository.hushCast.setSponsorSkipSoundUri(preset.uri)
                                    }
                                },
                                onTest = { HushCastEngineBridge.previewCustomSound(context, preset.uri) }
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
                                    context.startActivity(Intent.createChooser(intent, "Export timeline data"))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Export timeline data (developer)")
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
        }
    }
}

/** One choosable sound: radio, name, and a Test button that plays it (R13). */
@Composable
private fun SoundChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectableRow(selected, onSelect),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        androidx.compose.material3.IconButton(onClick = onTest) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = "Play ${label.removeSuffix(" (Default)")} sample"
            )
        }
    }
}

private fun Modifier.selectableRow(selected: Boolean, onClick: () -> Unit): Modifier =
    this.then(
        Modifier.selectable(
            selected = selected,
            role = androidx.compose.ui.semantics.Role.RadioButton,
            onClick = onClick
        )
    )
