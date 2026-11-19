package app.podiumpodcasts.podium.ui.route.settings.pane

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Power
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import app.podiumpodcasts.podium.ui.component.settings.settingsGroupShape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hushcast.automute.dsp.AdAutoSkipScope
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.BleepStyle
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.engine.CpuTopology
import app.hushcast.automute.engine.DownloadAnalysisMethod
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.engine.ModelDownloadProgress
import app.hushcast.automute.engine.ModelManager
import app.hushcast.automute.engine.MuteTimingPrecision
import app.hushcast.automute.matcher.PresetDictionaries
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.MuteRuleEntity
import app.hushcast.automute.worker.EpisodeAnalysisWorker
import app.hushcast.automute.worker.RuleReevaluationWorker
import app.podiumpodcasts.podium.CustomSoundPreset
import app.podiumpodcasts.podium.SkipSound
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.component.PodiumSnackbarHost
import app.podiumpodcasts.podium.ui.component.settings.SegmentedButtonLabel
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupBuilder
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupPosition
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupSurface
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSwitchListItem
import app.podiumpodcasts.podium.ui.component.settings.settingsGroup
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.hushcast.HushCastLabels
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

enum class HushCastSettingsSection(val title: String) {
    HUB("HushCast"),
    MUTING("Muting"),
    SPONSORS("Sponsor skipping"),
    WORD_LIST("Word list"),
    PROCESSING("Processing"),
    ADVANCED("Advanced")
}

@kotlinx.parcelize.Parcelize
@Serializable
data class SettingsHushCastKey(
    val section: HushCastSettingsSection = HushCastSettingsSection.HUB
) : SettingsPaneKey()

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class
)
@Composable
fun SettingsHushCastPane(
    section: HushCastSettingsSection = HushCastSettingsSection.HUB,
    navigationIcon: @Composable () -> Unit,
    onNavigate: (SettingsPaneKey) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = LocalSettingsRepository.current
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }
    val modelManager = remember(context) { ModelManager(context) }

    val rules by autoMuteDb.dao().observeRules().collectAsStateWithLifecycle(initialValue = emptyList())
    var modelsReady by remember { mutableStateOf(modelManager.areAllModelsReady()) }
    var downloadedMb by remember { mutableStateOf((modelManager.totalDownloadedBytes() / (1024L * 1024L)).toInt()) }
    var downloadProgress by remember { mutableStateOf<ModelDownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val wordMutingEnabled by settingsRepository.hushCast.wordMutingEnabled.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.wordMutingEnabled
    )
    val selectedActionMode by settingsRepository.hushCast.muteActionMode.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.defaultActionMode
    )
    val selectedAdPolicy by settingsRepository.hushCast.adPolicyMode.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.defaultAdPolicy
    )
    val selectedAutoSkipScope by settingsRepository.hushCast.adAutoSkipScope.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.autoSkipScope
    )
    val selectedClasses by settingsRepository.hushCast.adAutoSkipClasses.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.autoSkipClasses
    )
    val muteCustomSoundUri by settingsRepository.hushCast.muteCustomSoundUri.collectAsStateWithLifecycle(initialValue = null)
    val sponsorSkipSoundEnabled by settingsRepository.hushCast.sponsorSkipSoundEnabled.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.sponsorSkipSoundEnabled
    )
    val sponsorSkipSoundUri by settingsRepository.hushCast.sponsorSkipSoundUri.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.sponsorSkipSoundUri
    )
    val bleepStyle by settingsRepository.hushCast.bleepStyle.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.bleepStyle
    )
    val skipSound by settingsRepository.hushCast.skipSound.collectAsStateWithLifecycle(
        initialValue = HushCastEngineBridge.skipSound
    )
    val devMode by settingsRepository.hushCast.devMode.collectAsStateWithLifecycle(initialValue = false)
    val customSounds by settingsRepository.hushCast.customSounds.collectAsStateWithLifecycle(initialValue = emptyList())

    var pendingSoundUri by remember { mutableStateOf<String?>(null) }
    var pendingSoundName by remember { mutableStateOf("") }
    var pendingSoundTarget by remember { mutableStateOf("MUTE") }
    var renamingSound by remember { mutableStateOf<CustomSoundPreset?>(null) }
    var renameText by remember { mutableStateOf("") }

    fun resolveDisplayName(uri: android.net.Uri): String {
        val raw = runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Custom Sound"
        return raw.substringBeforeLast('.').ifBlank { "Custom Sound" }
    }

    // ---- Mute and skip sound choices (R13) ----------------------------------------------------

    /**
     * Built-in mute tone: BEEP mode with [style].  This also clears the custom-sound selection, so
     * "Sound" means the tone from now on.  The custom sound stays in the list: sounds saved before
     * 1.0.26 were copied there at startup, and the repository keeps them when the key is cleared.
     */
    fun chooseBleep(style: BleepStyle) {
        HushCastEngineBridge.bleepStyle = style
        HushCastEngineBridge.defaultActionMode = MuteActionMode.BEEP
        HushCastEngineBridge.loadCustomMuteSound(context, null)
        HushCastEngineBridge.previewBleep(style)
        scope.launch {
            settingsRepository.hushCast.setBleepStyle(style)
            settingsRepository.hushCast.setMuteActionMode(MuteActionMode.BEEP)
            settingsRepository.hushCast.setMuteCustomSoundUri(null)
        }
    }

    /** Custom mute sound: CUSTOM_SOUND mode with [preset]. */
    fun chooseCustomMuteSound(preset: CustomSoundPreset) {
        HushCastEngineBridge.loadCustomMuteSound(context, preset.uri)
        HushCastEngineBridge.defaultActionMode = MuteActionMode.CUSTOM_SOUND
        HushCastEngineBridge.previewCustomSound(context, preset.uri)
        scope.launch {
            settingsRepository.hushCast.setMuteCustomSoundUri(preset.uri)
            settingsRepository.hushCast.setMuteActionMode(MuteActionMode.CUSTOM_SOUND)
        }
    }

    /**
     * One of the four user-facing modes.  "Sound" plays the selected custom sound when there is
     * one, otherwise the built-in tone: picking a tone clears the custom selection, so a saved
     * URI always means the last sound chosen was custom.  Same rule as the player sheet.
     */
    fun chooseMode(mode: MuteActionMode) {
        val resolved = if (HushCastLabels.isSound(mode)) {
            if (muteCustomSoundUri != null) MuteActionMode.CUSTOM_SOUND else MuteActionMode.BEEP
        } else {
            mode
        }
        HushCastEngineBridge.defaultActionMode = resolved
        scope.launch { settingsRepository.hushCast.setMuteActionMode(resolved) }
    }

    fun chooseBuiltInSkipSound(sound: SkipSound) {
        HushCastEngineBridge.skipSound = sound
        HushCastEngineBridge.sponsorSkipSoundUri = null
        HushCastEngineBridge.previewSkipSound(context, sound)
        scope.launch {
            settingsRepository.hushCast.setSkipSound(sound)
            settingsRepository.hushCast.setSponsorSkipSoundUri(null)
        }
    }

    fun chooseCustomSkipSound(preset: CustomSoundPreset) {
        HushCastEngineBridge.sponsorSkipSoundUri = preset.uri
        HushCastEngineBridge.previewCustomSound(context, preset.uri)
        scope.launch { settingsRepository.hushCast.setSponsorSkipSoundUri(preset.uri) }
    }

    fun deleteCustomSound(preset: CustomSoundPreset) {
        val wasMuteSound = muteCustomSoundUri == preset.uri
        val wasSkipSound = sponsorSkipSoundUri == preset.uri
        val fallBackToTone = wasMuteSound && selectedActionMode == MuteActionMode.CUSTOM_SOUND
        if (wasMuteSound) HushCastEngineBridge.loadCustomMuteSound(context, null)
        if (wasSkipSound) HushCastEngineBridge.sponsorSkipSoundUri = null
        if (fallBackToTone) HushCastEngineBridge.defaultActionMode = MuteActionMode.BEEP
        scope.launch {
            // The repository also clears the mute/skip selection that pointed at this sound.
            settingsRepository.hushCast.removeCustomSound(preset.id)
            // Without its sound, CUSTOM_SOUND has nothing to play: fall back to the built-in tone.
            if (fallBackToTone) settingsRepository.hushCast.setMuteActionMode(MuteActionMode.BEEP)
        }
    }

    fun startRename(preset: CustomSoundPreset) {
        renameText = preset.name
        renamingSound = preset
    }

    val muteSoundPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingSoundTarget = "MUTE"
            pendingSoundName = resolveDisplayName(uri)
            pendingSoundUri = uri.toString()
        }
    }

    val skipSoundPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingSoundTarget = "SKIP"
            pendingSoundName = resolveDisplayName(uri)
            pendingSoundUri = uri.toString()
        }
    }

    if (pendingSoundUri != null) {
        AlertDialog(
            onDismissRequest = { pendingSoundUri = null },
            title = { Text("Name custom sound") },
            text = {
                OutlinedTextField(
                    value = pendingSoundName,
                    onValueChange = { pendingSoundName = it },
                    label = { Text("Sound name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val uriStr = pendingSoundUri ?: return@Button
                    val name = pendingSoundName.trim().ifEmpty { "Custom Sound" }
                    val target = pendingSoundTarget
                    pendingSoundUri = null
                    scope.launch {
                        settingsRepository.hushCast.addCustomSound(name, uriStr)
                        if (target == "MUTE") {
                            // Added from the Mute sound list, so it becomes the mute sound.
                            HushCastEngineBridge.loadCustomMuteSound(context, uriStr)
                            HushCastEngineBridge.defaultActionMode = MuteActionMode.CUSTOM_SOUND
                            settingsRepository.hushCast.setMuteCustomSoundUri(uriStr)
                            settingsRepository.hushCast.setMuteActionMode(MuteActionMode.CUSTOM_SOUND)
                        } else {
                            HushCastEngineBridge.sponsorSkipSoundUri = uriStr
                            settingsRepository.hushCast.setSponsorSkipSoundUri(uriStr)
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pendingSoundUri = null }) { Text("Cancel") }
            }
        )
    }

    if (renamingSound != null) {
        AlertDialog(
            onDismissRequest = { renamingSound = null },
            title = { Text("Rename custom sound") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Sound name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    val target = renamingSound ?: return@Button
                    val nextName = renameText.trim()
                    renamingSound = null
                    if (nextName.isNotEmpty()) {
                        scope.launch { settingsRepository.hushCast.renameCustomSound(target.id, nextName) }
                    }
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renamingSound = null }) { Text("Cancel") }
            }
        )
    }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(section) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = { PodiumSnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = navigationIcon,
                title = { Text(section.title) }
            )
        }
    ) { padding ->
        when (section) {
            HushCastSettingsSection.HUB -> {
                val workerConfigured = HushCastPrefs.isWorkerActive(context)
                val whereLabel = when {
                    workerConfigured -> "Cloud / desktop"
                    modelsReady -> "This phone"
                    else -> null
                }
                val statusText = if (whereLabel == null) {
                    "Set up processing to start muting words & skipping sponsors"
                } else {
                    val mutingStr = if (wordMutingEnabled) "Muting on" else "Muting off"
                    val skipStr = when (selectedAdPolicy) {
                        AdPolicyMode.AUTO_SKIP -> "Skipping on"
                        AdPolicyMode.MARK_ONLY -> "Marking only"
                        AdPolicyMode.OFF -> "Skipping off"
                    }
                    "$mutingStr · $skipStr · $whereLabel"
                }

                SettingsLazyColumn(padding) {
                    settingsGroup(key = "status", spaceAbove = 0.dp) {
                        row { pos ->
                            SettingsGroupSurface(
                                position = pos,
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentPadding = PaddingValues(16.dp)
                            ) {
                                Column(Modifier.semantics(mergeDescendants = true) { }) {
                                    Text(
                                        text = "Status",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }

                    settingsGroup(key = "sections") {
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.AutoMirrored.Rounded.VolumeOff, null) },
                                label = "Muting",
                                description = if (wordMutingEnabled) {
                                    "On · ${HushCastLabels.modeLabel(selectedActionMode)}"
                                } else {
                                    "Off"
                                },
                                index = pos.index,
                                count = pos.count,
                                onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.MUTING)) }
                            )
                        }
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.Rounded.Shield, null) },
                                label = "Sponsor skipping",
                                description = when (selectedAdPolicy) {
                                    AdPolicyMode.AUTO_SKIP -> "Auto-skip"
                                    AdPolicyMode.MARK_ONLY -> "Mark only"
                                    AdPolicyMode.OFF -> "Off"
                                },
                                index = pos.index,
                                count = pos.count,
                                onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.SPONSORS)) }
                            )
                        }
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null) },
                                label = "Word list",
                                description = "${rules.size} rules",
                                index = pos.index,
                                count = pos.count,
                                onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.WORD_LIST)) }
                            )
                        }
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.Rounded.Download, null) },
                                label = "Processing",
                                description = whereLabel ?: "Not set up yet",
                                index = pos.index,
                                count = pos.count,
                                onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.PROCESSING)) }
                            )
                        }
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.Rounded.Tune, null) },
                                label = "Advanced",
                                description = "Performance, battery & diagnostics",
                                index = pos.index,
                                count = pos.count,
                                onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.ADVANCED)) }
                            )
                        }
                    }
                }
            }

            HushCastSettingsSection.MUTING -> {
                var selectedPrecision by remember {
                    mutableStateOf(HushCastPrefs.getMuteTimingPrecision(context))
                }

                SettingsLazyColumn(padding) {
                    settingsGroup(key = "muting", spaceAbove = 0.dp) {
                        row { pos ->
                            SettingsSwitchListItem(
                                checked = wordMutingEnabled,
                                onCheckedChange = {
                                    HushCastEngineBridge.wordMutingEnabled = it
                                    scope.launch { settingsRepository.hushCast.setWordMutingEnabled(it) }
                                },
                                icon = { Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null) },
                                label = "Word auto-muting",
                                description = "Mute the words on your word list while you listen",
                                index = pos.index,
                                count = pos.count
                            )
                        }
                    }

                    settingsGroup(header = "Mute mode", key = "mode") {
                        row { pos ->
                            SettingsGroupSurface(position = pos) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        HushCastLabels.userModes.forEachIndexed { idx, mode ->
                                            SegmentedButton(
                                                enabled = wordMutingEnabled,
                                                selected = HushCastLabels.sameUserChoice(mode, selectedActionMode),
                                                onClick = { chooseMode(mode) },
                                                shape = SegmentedButtonDefaults.itemShape(idx, HushCastLabels.userModes.size),
                                                icon = {}
                                            ) {
                                                SegmentedButtonLabel(HushCastLabels.modeLabel(mode))
                                            }
                                        }
                                    }
                                    Text(
                                        text = HushCastLabels.modeDescription(selectedActionMode),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (HushCastLabels.isSound(selectedActionMode)) {
                        settingsGroup(header = "Mute sound", key = "mute_sound") {
                            soundRows(
                                enabled = wordMutingEnabled,
                                builtIns = BleepStyle.entries.map { style ->
                                    BuiltInSound(
                                        id = style.name,
                                        name = style.label,
                                        description = if (style == BleepStyle.DEFAULT) "Default · ${style.description}" else style.description,
                                        selected = selectedActionMode == MuteActionMode.BEEP && bleepStyle == style,
                                        onSelect = { chooseBleep(style) },
                                        onTest = { HushCastEngineBridge.previewBleep(style) }
                                    )
                                },
                                customSounds = customSounds,
                                isCustomSelected = { preset ->
                                    selectedActionMode == MuteActionMode.CUSTOM_SOUND && muteCustomSoundUri == preset.uri
                                },
                                onSelectCustom = { chooseCustomMuteSound(it) },
                                onTestCustom = { HushCastEngineBridge.previewCustomSound(context, it.uri) },
                                onRenameCustom = { startRename(it) },
                                onDeleteCustom = { deleteCustomSound(it) },
                                onAdd = { muteSoundPicker.launch(arrayOf("audio/*")) }
                            )
                        }
                    }

                    settingsGroup(
                        header = "Timing precision",
                        description = "Padding before and after each muted word",
                        key = "precision"
                    ) {
                        MuteTimingPrecision.entries.forEach { precision ->
                            row(key = precision.name) { pos ->
                                ChoiceRow(
                                    position = pos,
                                    title = precision.label,
                                    selected = selectedPrecision == precision,
                                    enabled = wordMutingEnabled,
                                    onSelect = {
                                        selectedPrecision = precision
                                        HushCastPrefs.setMuteTimingPrecision(context, precision)
                                        RuleReevaluationWorker.enqueue(
                                            context = context,
                                            prePadMs = precision.prePadMs,
                                            postPadMs = precision.postPadMs,
                                            defaultActionMode = selectedActionMode
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            HushCastSettingsSection.SPONSORS -> {
                val skippingOn = selectedAdPolicy != AdPolicyMode.OFF

                SettingsLazyColumn(padding) {
                    settingsGroup(header = "Sponsor & section skipping", key = "policy", spaceAbove = 0.dp) {
                        row { pos ->
                            SettingsGroupSurface(position = pos) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val policies = listOf(
                                        AdPolicyMode.AUTO_SKIP to "Auto-skip",
                                        AdPolicyMode.MARK_ONLY to "Mark only",
                                        AdPolicyMode.OFF to "Off"
                                    )
                                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                        policies.forEachIndexed { idx, (policy, label) ->
                                            SegmentedButton(
                                                selected = selectedAdPolicy == policy,
                                                onClick = {
                                                    HushCastEngineBridge.defaultAdPolicy = policy
                                                    HushCastEngineBridge.adSkipObserver.policyMode = policy
                                                    scope.launch { settingsRepository.hushCast.setAdPolicyMode(policy) }
                                                },
                                                shape = SegmentedButtonDefaults.itemShape(idx, policies.size)
                                            ) { SegmentedButtonLabel(label, hasIcon = true) }
                                        }
                                    }
                                    Text(
                                        text = when (selectedAdPolicy) {
                                            AdPolicyMode.AUTO_SKIP -> "Detected sponsor reads are skipped automatically"
                                            AdPolicyMode.MARK_ONLY -> "Sponsor sections are marked, and you choose what to skip"
                                            AdPolicyMode.OFF -> "Episodes play without sponsor detection"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (selectedAdPolicy == AdPolicyMode.AUTO_SKIP) {
                        settingsGroup(header = "Confidence", key = "confidence") {
                            row { pos ->
                                SettingsGroupSurface(position = pos) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val scopes = listOf(
                                            AdAutoSkipScope.HIGH_CONFIDENCE_ONLY to "High (80%+)",
                                            AdAutoSkipScope.MODERATE_AND_HIGH to "Balanced",
                                            AdAutoSkipScope.ALL_DETECTED to "All ads"
                                        )
                                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                            scopes.forEachIndexed { idx, (scopeVal, label) ->
                                                SegmentedButton(
                                                    enabled = skippingOn,
                                                    selected = selectedAutoSkipScope == scopeVal,
                                                    onClick = {
                                                        HushCastEngineBridge.autoSkipScope = scopeVal
                                                        scope.launch { settingsRepository.hushCast.setAdAutoSkipScope(scopeVal) }
                                                    },
                                                    shape = SegmentedButtonDefaults.itemShape(idx, scopes.size)
                                                ) { SegmentedButtonLabel(label, hasIcon = true) }
                                            }
                                        }
                                        Text(
                                            text = when (selectedAutoSkipScope) {
                                                AdAutoSkipScope.HIGH_CONFIDENCE_ONLY ->
                                                    "Only skip high-confidence sponsor reads (80%+). Suggested ads show a Skip button."
                                                AdAutoSkipScope.MODERATE_AND_HIGH ->
                                                    "Skip likely and high-confidence ads (65%+)."
                                                AdAutoSkipScope.ALL_DETECTED ->
                                                    "Skip all detected ads, including suggested ads."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        settingsGroup(header = "Section types to auto-skip", key = "classes") {
                            listOf(
                                "sponsor_ad" to "Sponsors / ads",
                                "house_promo" to "Show promos",
                                "membership_pitch" to "Membership pitches",
                                "boilerplate" to "Intros / outros"
                            ).forEach { (cls, label) ->
                                row(key = cls) { pos ->
                                    val checked = cls in selectedClasses
                                    SettingsSwitchListItem(
                                        checked = checked,
                                        enabled = skippingOn,
                                        onCheckedChange = {
                                            val updated = if (checked) selectedClasses - cls else selectedClasses + cls
                                            HushCastEngineBridge.autoSkipClasses = updated
                                            scope.launch { settingsRepository.hushCast.setAdAutoSkipClasses(updated) }
                                        },
                                        label = label,
                                        index = pos.index,
                                        count = pos.count
                                    )
                                }
                            }
                        }
                    }

                    settingsGroup(header = "Skip sound", key = "skip_sound") {
                        row { pos ->
                            SettingsSwitchListItem(
                                checked = sponsorSkipSoundEnabled,
                                enabled = skippingOn,
                                onCheckedChange = { enabled ->
                                    HushCastEngineBridge.sponsorSkipSoundEnabled = enabled
                                    scope.launch { settingsRepository.hushCast.setSponsorSkipSoundEnabled(enabled) }
                                },
                                icon = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null) },
                                label = "Play a sound when an ad is skipped",
                                description = "So you know the episode jumped ahead",
                                index = pos.index,
                                count = pos.count
                            )
                        }

                        if (sponsorSkipSoundEnabled && skippingOn) {
                            soundRows(
                                enabled = true,
                                builtIns = SkipSound.entries.map { sound ->
                                    BuiltInSound(
                                        id = sound.name,
                                        name = sound.label,
                                        description = if (sound == SkipSound.WHOOSH) "Default · ${sound.description}" else sound.description,
                                        selected = sponsorSkipSoundUri == null && skipSound == sound,
                                        onSelect = { chooseBuiltInSkipSound(sound) },
                                        onTest = { HushCastEngineBridge.previewSkipSound(context, sound) }
                                    )
                                },
                                customSounds = customSounds,
                                isCustomSelected = { preset -> sponsorSkipSoundUri == preset.uri },
                                onSelectCustom = { chooseCustomSkipSound(it) },
                                onTestCustom = { HushCastEngineBridge.previewCustomSound(context, it.uri) },
                                onRenameCustom = { startRename(it) },
                                onDeleteCustom = { deleteCustomSound(it) },
                                onAdd = { skipSoundPicker.launch(arrayOf("audio/*")) }
                            )
                        }
                    }
                }
            }

            HushCastSettingsSection.WORD_LIST -> {
                var newPattern by remember { mutableStateOf("") }
                var isRegex by remember { mutableStateOf(false) }
                var searchQuery by remember { mutableStateOf("") }

                val filteredRules = remember(rules, searchQuery) {
                    val q = searchQuery.trim()
                    if (q.isEmpty()) rules
                    else rules.filter {
                        it.pattern.contains(q, ignoreCase = true) ||
                            it.category.contains(q, ignoreCase = true)
                    }
                }

                val reevalInfos by androidx.work.WorkManager.getInstance(context)
                    .getWorkInfosForUniqueWorkFlow(RuleReevaluationWorker.UNIQUE_NAME)
                    .collectAsStateWithLifecycle(initialValue = emptyList())

                val existingKeys = remember(rules) {
                    rules.map { it.pattern.trim().lowercase() to it.isRegex }.toSet()
                }
                val profanityAdded = remember(existingKeys, selectedActionMode) {
                    PresetDictionaries.profanityRules(selectedActionMode).all {
                        (it.pattern.trim().lowercase() to it.isRegex) in existingKeys
                    }
                }
                val explicitAdded = remember(existingKeys, selectedActionMode) {
                    PresetDictionaries.explicitRules(selectedActionMode).all {
                        (it.pattern.trim().lowercase() to it.isRegex) in existingKeys
                    }
                }

                fun addPreset(presetRules: List<app.hushcast.automute.matcher.MuteRule>) {
                    scope.launch {
                        val entities = presetRules.map {
                            MuteRuleEntity(
                                pattern = it.pattern,
                                isRegex = it.isRegex,
                                matchPlurals = it.matchPlurals,
                                actionMode = if (it.useDefaultMode) app.hushcast.automute.storage.DEFAULT_RULE_MODE else it.actionMode.name,
                                category = it.category
                            )
                        }
                        autoMuteDb.dao().upsertRulesIfNew(entities)
                        RuleReevaluationWorker.enqueue(context = context, defaultActionMode = selectedActionMode)
                    }
                }

                fun deleteRuleWithUndo(rule: MuteRuleEntity) {
                    scope.launch {
                        autoMuteDb.dao().deleteRule(rule.id)
                        RuleReevaluationWorker.enqueue(
                            context = context,
                            defaultActionMode = selectedActionMode
                        )
                        val res = snackbarHostState.showSnackbar(
                            message = "Removed \"${rule.pattern}\"",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short
                        )
                        if (res == SnackbarResult.ActionPerformed) {
                            autoMuteDb.dao().upsertRuleIfNew(rule.copy(id = 0))
                            RuleReevaluationWorker.enqueue(
                                context = context,
                                defaultActionMode = selectedActionMode
                            )
                        }
                    }
                }

                SettingsLazyColumn(padding, Modifier.focusTarget()) {
                    settingsGroup(
                        header = "Add word or pattern",
                        description = if (reevalInfos.any { !it.state.isFinished }) "Updating episodes…" else null,
                        key = "add",
                        spaceAbove = 0.dp
                    ) {
                        row { pos ->
                            SettingsGroupSurface(position = pos) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = newPattern,
                                        onValueChange = { newPattern = it },
                                        label = { Text("Word, phrase, or regex") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        enabled = newPattern.isNotBlank(),
                                        onClick = {
                                            val pat = newPattern.trim()
                                            newPattern = ""
                                            scope.launch {
                                                autoMuteDb.dao().upsertRuleIfNew(
                                                    MuteRuleEntity(
                                                        pattern = pat,
                                                        isRegex = isRegex,
                                                        // Follow the global mute mode, so changing it applies.
                                                        actionMode = app.hushcast.automute.storage.DEFAULT_RULE_MODE
                                                    )
                                                )
                                                RuleReevaluationWorker.enqueue(
                                                    context = context,
                                                    defaultActionMode = selectedActionMode
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = "Add to word list")
                                    }
                                }
                            }
                        }
                        row { pos ->
                            SettingsSwitchListItem(
                                checked = isRegex,
                                onCheckedChange = { isRegex = it },
                                icon = { Icon(Icons.Rounded.Code, contentDescription = null) },
                                label = "Treat as regex",
                                description = "Tip: use \\bword\\b for exact whole-word matching",
                                index = pos.index,
                                count = pos.count
                            )
                        }
                    }

                    settingsGroup(header = "Presets", key = "presets") {
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                label = if (profanityAdded) "Profanity preset added" else "Add profanity preset",
                                description = "Common swear words",
                                enabled = !profanityAdded,
                                index = pos.index,
                                count = pos.count,
                                onClick = { addPreset(PresetDictionaries.profanityRules(selectedActionMode)) }
                            )
                        }
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                label = if (explicitAdded) "Explicit preset added" else "Add explicit preset",
                                description = "Sexual and explicit terms",
                                enabled = !explicitAdded,
                                index = pos.index,
                                count = pos.count,
                                onClick = { addPreset(PresetDictionaries.explicitRules(selectedActionMode)) }
                            )
                        }
                    }

                    if (rules.isNotEmpty()) {
                        settingsGroup(header = "Rules", key = "search") {
                            row { _ ->
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                    label = { Text("Search ${rules.size} rules") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp)
                                )
                            }
                        }
                    }

                    settingsGroup(key = "rules", spaceAbove = 0.dp) {
                        filteredRules.forEach { rule ->
                            row(key = rule.id) { pos ->
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                                            deleteRuleWithUndo(rule)
                                            true
                                        } else false
                                    }
                                )
                                val modeLabel = if (rule.actionMode.equals(app.hushcast.automute.storage.DEFAULT_RULE_MODE, ignoreCase = true)) {
                                    HushCastLabels.modeLabel(selectedActionMode)
                                } else {
                                    runCatching { HushCastLabels.modeLabel(MuteActionMode.valueOf(rule.actionMode)) }
                                        .getOrElse { rule.actionMode.lowercase().replace('_', ' ') }
                                }
                                SwipeToDismissBox(
                                    state = dismissState,
                                    // Clip to the row's group shape, so the delete background
                                    // keeps the row's corners while it's dragged.
                                    modifier = Modifier
                                        .animateItem()
                                        .clip(settingsGroupShape(pos)),
                                    backgroundContent = {
                                        val direction = dismissState.dismissDirection
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(
                                                    if (direction == SwipeToDismissBoxValue.Settled) Color.Transparent
                                                    else MaterialTheme.colorScheme.errorContainer
                                                )
                                                .padding(horizontal = 24.dp),
                                            contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                                                Alignment.CenterStart
                                            } else {
                                                Alignment.CenterEnd
                                            }
                                        ) {
                                            Icon(
                                                Icons.Rounded.Delete,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                ) {
                                    // The whole row toggles the rule and is announced as a switch;
                                    // the Switch only shows the state.
                                    SettingsListItem(
                                        modifier = Modifier.semantics {
                                            role = Role.Switch
                                            toggleableState = ToggleableState(rule.enabled)
                                        },
                                        content = {
                                            Text(rule.pattern, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        },
                                        description = "${rule.category} • $modeLabel${if (rule.isRegex) " • Regex" else ""}",
                                        trailingContent = {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Switch(
                                                    checked = rule.enabled,
                                                    onCheckedChange = null
                                                )
                                                IconButton(onClick = { deleteRuleWithUndo(rule) }) {
                                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete rule ${rule.pattern}")
                                                }
                                            }
                                        },
                                        index = pos.index,
                                        count = pos.count,
                                        onClick = {
                                            scope.launch {
                                                autoMuteDb.dao().setRuleEnabled(rule.id, !rule.enabled)
                                                RuleReevaluationWorker.enqueue(context = context, defaultActionMode = selectedActionMode)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HushCastSettingsSection.PROCESSING -> {
                var desktopEnabled by remember { mutableStateOf(HushCastPrefs.isDesktopWorkerEnabled(context)) }
                var desktopUrl by remember { mutableStateOf(HushCastPrefs.getDesktopWorkerUrl(context)) }
                var desktopToken by remember { mutableStateOf(HushCastPrefs.getDesktopWorkerToken(context)) }
                var desktopStatus by remember { mutableStateOf<String?>(null) }
                var desktopBusy by remember { mutableStateOf(false) }
                var autoDownloadMethod by remember { mutableStateOf(HushCastPrefs.getAutoDownloadMethod(context)) }
                val desktopActive = desktopEnabled && desktopUrl.isNotBlank()
                var showLocalModels by remember { mutableStateOf(!desktopActive) }

                SettingsLazyColumn(padding, Modifier.focusTarget()) {
                    settingsGroup(header = "Desktop / cloud worker", key = "worker", spaceAbove = 0.dp) {
                        row { pos ->
                            SettingsSwitchListItem(
                                checked = desktopEnabled,
                                onCheckedChange = { enabled ->
                                    desktopEnabled = enabled
                                    HushCastPrefs.setDesktopWorkerEnabled(context, enabled)
                                },
                                icon = { Icon(Icons.Rounded.Cloud, contentDescription = null) },
                                label = "Use a desktop or cloud worker",
                                description = "Have your Mac or GPU worker download & analyze episodes, then push exact audio + index over Wi-Fi.",
                                index = pos.index,
                                count = pos.count
                            )
                        }
                        if (desktopEnabled) {
                            row { pos ->
                                SettingsGroupSurface(position = pos) {
                                    Column {
                                        OutlinedTextField(
                                            value = desktopUrl,
                                            onValueChange = { desktopUrl = it },
                                            label = { Text("Worker URL (e.g. http://192.168.1.50:8765)") },
                                            singleLine = true,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { state ->
                                                    if (!state.isFocused) HushCastPrefs.setDesktopWorkerUrl(context, desktopUrl)
                                                }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = desktopToken,
                                            onValueChange = { desktopToken = it },
                                            label = { Text("Pairing token") },
                                            singleLine = true,
                                            visualTransformation = PasswordVisualTransformation(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onFocusChanged { state ->
                                                    if (!state.isFocused) HushCastPrefs.setDesktopWorkerToken(context, desktopToken)
                                                }
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedButton(
                                                enabled = !desktopBusy,
                                                onClick = {
                                                    desktopBusy = true
                                                    desktopStatus = "Opening QR scanner…"
                                                    val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
                                                        .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
                                                        .build()
                                                    com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
                                                        .startScan()
                                                        .addOnSuccessListener { barcode ->
                                                            val parsed = parseWorkerPairingPayload(barcode.rawValue.orEmpty())
                                                            if (parsed == null) {
                                                                desktopStatus = "Unrecognized QR code."
                                                                desktopBusy = false
                                                                return@addOnSuccessListener
                                                            }
                                                            val (scannedUrl, scannedToken) = parsed
                                                            desktopUrl = scannedUrl
                                                            HushCastPrefs.setDesktopWorkerUrl(context, scannedUrl)
                                                            if (scannedToken.isNotBlank()) {
                                                                desktopToken = scannedToken
                                                                HushCastPrefs.setDesktopWorkerToken(context, scannedToken)
                                                            }
                                                            desktopStatus = "Scanned $scannedUrl — verifying…"
                                                            scope.launch {
                                                                try {
                                                                    val effectiveToken = scannedToken.ifBlank { desktopToken }
                                                                    val health = app.hushcast.automute.desktop.DesktopWorkerClient.healthCheck(scannedUrl, effectiveToken)
                                                                    val authErr = runCatching {
                                                                        app.hushcast.automute.desktop.DesktopWorkerClient.listReadyEpisodes(scannedUrl, effectiveToken)
                                                                    }.exceptionOrNull()
                                                                    desktopStatus = if (authErr != null) {
                                                                        "Server reachable (${health.hostname}), but auth failed: ${authErr.message ?: authErr.javaClass.simpleName}"
                                                                    } else {
                                                                        val outdatedNote = if (
                                                                            health.apiVersion < app.hushcast.automute.desktop.DesktopWorkerClient.MIN_WORKER_API_VERSION ||
                                                                            health.pipelineVersion < app.hushcast.automute.pipeline.AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
                                                                        ) " • Worker outdated (update recommended)" else ""
                                                                        "Connected: ${health.hostname} (${health.accelerator.uppercase()}) • ${health.whisperModel}$outdatedNote"
                                                                    }
                                                                } catch (t: Throwable) {
                                                                    desktopStatus = "Saved QR config, but connection check failed: ${t.message ?: t.javaClass.simpleName}"
                                                                } finally {
                                                                    desktopBusy = false
                                                                }
                                                            }
                                                        }
                                                        .addOnCanceledListener {
                                                            desktopStatus = null
                                                            desktopBusy = false
                                                        }
                                                        .addOnFailureListener { e ->
                                                            desktopStatus = "QR scanner unavailable (${e.message ?: "no Play Services"})."
                                                            desktopBusy = false
                                                        }
                                                }
                                            ) { Text("Scan QR") }
                                            OutlinedButton(
                                                enabled = !desktopBusy,
                                                onClick = {
                                                    desktopBusy = true
                                                    desktopStatus = "Searching Wi-Fi for _hushcast._tcp…"
                                                    scope.launch {
                                                        val found = app.hushcast.automute.desktop.DesktopWorkerDiscovery.discoverFirst(context)
                                                        if (found != null) {
                                                            desktopUrl = found.url
                                                            HushCastPrefs.setDesktopWorkerUrl(context, found.url)
                                                            desktopStatus = "Found ${found.serviceName} at ${found.url}"
                                                        } else {
                                                            desktopStatus = "No HushCast worker found on local Wi-Fi."
                                                        }
                                                        desktopBusy = false
                                                    }
                                                }
                                            ) { Text("Discover") }
                                            Button(
                                                enabled = !desktopBusy && desktopUrl.isNotBlank(),
                                                onClick = {
                                                    desktopBusy = true
                                                    desktopStatus = "Connecting…"
                                                    scope.launch {
                                                        try {
                                                            val health = app.hushcast.automute.desktop.DesktopWorkerClient.healthCheck(desktopUrl, desktopToken)
                                                            val authErr = runCatching {
                                                                app.hushcast.automute.desktop.DesktopWorkerClient.listReadyEpisodes(desktopUrl, desktopToken)
                                                            }.exceptionOrNull()
                                                            desktopStatus = if (authErr != null) {
                                                                "Server reachable (${health.hostname}), but auth failed: ${authErr.message ?: authErr.javaClass.simpleName}"
                                                            } else {
                                                                val modelNote = buildString {
                                                                    if (health.whisperModel.isNotBlank()) append(" • ${health.whisperModel}")
                                                                    if (health.chatModelReady) append(" • LLM Chat")
                                                                }
                                                                val outdatedNote = if (
                                                                    health.apiVersion < app.hushcast.automute.desktop.DesktopWorkerClient.MIN_WORKER_API_VERSION ||
                                                                    health.pipelineVersion < app.hushcast.automute.pipeline.AutoMuteAnalyzer.CURRENT_PIPELINE_VERSION
                                                                ) " • Worker outdated (update recommended)" else ""
                                                                "Connected: ${health.hostname} (${health.accelerator.uppercase()}) • " +
                                                                    (if (health.modelsReady) "Models Ready" else "Models Missing") +
                                                                    "$modelNote • ${health.cachedEpisodes} cached$outdatedNote"
                                                            }
                                                        } catch (t: Throwable) {
                                                            desktopStatus = "Connection failed: ${t.message ?: t.javaClass.simpleName}"
                                                        } finally {
                                                            desktopBusy = false
                                                        }
                                                    }
                                                }
                                            ) { Text("Test") }
                                        }
                                        desktopStatus?.let { status ->
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                status,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    settingsGroup(
                        header = "Auto-download analysis",
                        description = "How newly auto-downloaded episodes are processed",
                        key = "auto_download"
                    ) {
                        row { pos ->
                            SettingsGroupSurface(position = pos) {
                                val autoOptions = listOf(
                                    DownloadAnalysisMethod.AUTO to "Auto",
                                    DownloadAnalysisMethod.ON_DEVICE to "On-device",
                                    DownloadAnalysisMethod.SKIP_ANALYSIS to "Skip"
                                )
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    autoOptions.forEachIndexed { index, (method, label) ->
                                        SegmentedButton(
                                            selected = autoDownloadMethod == method,
                                            onClick = {
                                                autoDownloadMethod = method
                                                HushCastPrefs.setAutoDownloadMethod(context, method)
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(index = index, count = autoOptions.size)
                                        ) { SegmentedButtonLabel(label, hasIcon = true) }
                                    }
                                }
                            }
                        }
                    }

                    settingsGroup(header = "On-device AI models", key = "models") {
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                label = "On-device models (~455 MB)",
                                description = when {
                                    modelsReady -> "Ready ($downloadedMb MB local)"
                                    desktopActive -> "Optional offline fallback. Your worker handles analysis."
                                    else -> "Not downloaded"
                                },
                                trailingContent = {
                                    Icon(
                                        imageVector = if (showLocalModels) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                        contentDescription = if (showLocalModels) "Collapse" else "Expand"
                                    )
                                },
                                index = pos.index,
                                count = pos.count,
                                onClick = { showLocalModels = !showLocalModels }
                            )
                        }
                        if (showLocalModels) {
                            row { pos ->
                                SettingsGroupSurface(position = pos) {
                                    Column {
                                        Text(
                                            when {
                                                modelsReady -> "All models ready ($downloadedMb MB stored locally). \$0 server cost, 100% private."
                                                desktopActive -> "Optional offline fallback: your worker is active, so on-device models aren't needed unless you want offline analysis."
                                                else -> "Download Whisper base.en q5_1 (58 MB), Silero VAD (2 MB), and Qwen3-Embedding Q4 (395 MB) over Wi-Fi."
                                            },
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        downloadProgress?.let { p ->
                                            Spacer(Modifier.height(8.dp))
                                            Text("${p.modelName}: ${p.bytesDownloaded / (1024 * 1024)} MB", style = MaterialTheme.typography.labelSmall)
                                            val frac = if (p.totalBytes > 0) p.bytesDownloaded.toFloat() / p.totalBytes.toFloat() else 0f
                                            LinearProgressIndicator(progress = { frac.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                        }
                                        downloadError?.let { err ->
                                            Spacer(Modifier.height(6.dp))
                                            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                enabled = downloadProgress == null,
                                                onClick = {
                                                    downloadError = null
                                                    scope.launch {
                                                        try {
                                                            withContext(Dispatchers.IO) {
                                                                modelManager.ensureAllModels { prog -> downloadProgress = prog }
                                                            }
                                                            modelsReady = modelManager.areAllModelsReady()
                                                            downloadedMb = (modelManager.totalDownloadedBytes() / (1024L * 1024L)).toInt()
                                                        } catch (t: Throwable) {
                                                            downloadError = t.message ?: "Download failed"
                                                        } finally {
                                                            downloadProgress = null
                                                        }
                                                    }
                                                }
                                            ) { Text(if (modelsReady) "Verify models" else "Download models (Wi-Fi)") }
                                            if (downloadedMb > 0) {
                                                OutlinedButton(onClick = {
                                                    modelManager.deleteAllModels()
                                                    modelsReady = false
                                                    downloadedMb = 0
                                                }) { Text("Delete models") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HushCastSettingsSection.ADVANCED -> {
                var fastModelEnabled by remember { mutableStateOf(HushCastPrefs.isFastModelEnabled(context)) }
                var fastAttentionEnabled by remember { mutableStateOf(HushCastPrefs.isFastAttentionEnabled(context)) }
                var chargingOnly by remember { mutableStateOf(HushCastPrefs.isAnalyzeOnlyWhileCharging(context)) }
                val thermalLabel = remember { CpuTopology.thermalStatusLabel(context) }
                val perfCores = remember { CpuTopology.performanceCoreCount() }
                var tinyReady by remember { mutableStateOf(modelManager.isTinyModelReady()) }
                val currentPssMb = remember {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val info = am?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()
                    (info?.totalPss ?: 0) / 1024
                }
                var queueClearedMsg by remember { mutableStateOf<String?>(null) }

                SettingsLazyColumn(padding) {
                    settingsGroup(
                        header = "Performance & battery",
                        description = "Using $perfCores performance CPU cores • Thermal: $thermalLabel",
                        key = "performance",
                        spaceAbove = 0.dp
                    ) {
                        row { pos ->
                            SettingsSwitchListItem(
                                checked = fastModelEnabled,
                                onCheckedChange = { enabled ->
                                    fastModelEnabled = enabled
                                    HushCastPrefs.setFastModelEnabled(context, enabled)
                                    if (enabled && !tinyReady && downloadProgress == null) {
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    modelManager.ensureTinyModel { prog -> downloadProgress = prog }
                                                }
                                                tinyReady = modelManager.isTinyModelReady()
                                                downloadedMb = (modelManager.totalDownloadedBytes() / (1024L * 1024L)).toInt()
                                            } catch (t: Throwable) {
                                                downloadError = t.message ?: "tiny.en download failed"
                                            } finally {
                                                downloadProgress = null
                                            }
                                        }
                                    }
                                },
                                icon = { Icon(Icons.Rounded.Speed, contentDescription = null) },
                                label = "Fast transcription model (tiny.en)",
                                description = if (tinyReady) "2–3× faster (32 MB). Slightly lower word accuracy."
                                else "2–3× faster (downloads 32 MB tiny.en q5_1).",
                                index = pos.index,
                                count = pos.count
                            )
                        }
                        row { pos ->
                            SettingsSwitchListItem(
                                checked = fastAttentionEnabled,
                                onCheckedChange = { enabled ->
                                    fastAttentionEnabled = enabled
                                    HushCastPrefs.setFastAttentionEnabled(context, enabled)
                                },
                                icon = { Icon(Icons.Rounded.Bolt, contentDescription = null) },
                                label = "Fast timing mode (Flash Attention)",
                                description = "1.3× faster by skipping DTW word alignment; mute timing is slightly looser.",
                                index = pos.index,
                                count = pos.count
                            )
                        }
                        row { pos ->
                            SettingsSwitchListItem(
                                checked = chargingOnly,
                                onCheckedChange = { enabled ->
                                    chargingOnly = enabled
                                    HushCastPrefs.setAnalyzeOnlyWhileCharging(context, enabled)
                                },
                                icon = { Icon(Icons.Rounded.Power, contentDescription = null) },
                                label = "Analyze only while charging",
                                description = "Defers episode analysis until plugged in to save battery and prevent heat.",
                                index = pos.index,
                                count = pos.count
                            )
                        }
                    }

                    settingsGroup(header = "Developer", key = "developer") {
                        row { pos ->
                            SettingsSwitchListItem(
                                checked = devMode,
                                onCheckedChange = { enabled ->
                                    scope.launch { settingsRepository.hushCast.setDevMode(enabled) }
                                },
                                icon = { Icon(Icons.Rounded.Code, contentDescription = null) },
                                label = "Developer mode",
                                description = "Show timeline export, diagnostic logs, and stage-specific cloud reprocessing",
                                index = pos.index,
                                count = pos.count
                            )
                        }
                    }

                    settingsGroup(header = "Diagnostics", key = "diagnostics") {
                        row { pos ->
                            SettingsGroupSurface(position = pos) {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Memory, contentDescription = null)
                                        Spacer(Modifier.width(12.dp))
                                        Text(
                                            "Current App Memory (PSS): $currentPssMb MB • Target Ceiling: <= 1024 MB\n" +
                                                "Models Stored: $downloadedMb MB • Sequential Unload: Enabled",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    queueClearedMsg?.let { msg ->
                                        Spacer(Modifier.height(6.dp))
                                        Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        row { pos ->
                            SettingsListItem(
                                icon = { Icon(Icons.Rounded.Cancel, contentDescription = null) },
                                label = "Cancel analysis queue",
                                description = "Stop all queued episode analysis",
                                index = pos.index,
                                count = pos.count,
                                onClick = {
                                    EpisodeAnalysisWorker.cancelAll(context)
                                    queueClearedMsg = "Analysis queue cancelled."
                                }
                            )
                        }
                        if (devMode) {
                            row { pos ->
                                SettingsListItem(
                                    icon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                    label = "Export logs",
                                    description = "Share diagnostic logs",
                                    index = pos.index,
                                    count = pos.count,
                                    onClick = {
                                        scope.launch {
                                            val logText = withContext(Dispatchers.IO) {
                                                val pkgInfo = runCatching {
                                                    context.packageManager.getPackageInfo(context.packageName, 0)
                                                }.getOrNull()
                                                val verName = pkgInfo?.versionName ?: "unknown"
                                                val verCode = if (pkgInfo != null) androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(pkgInfo) else 0L
                                                val statuses = runCatching {
                                                    autoMuteDb.dao().observeAllStatuses().first()
                                                }.getOrElse { emptyList() }
                                                val header = buildString {
                                                    appendLine("HushCast Diagnostics")
                                                    appendLine("App: $verName ($verCode)")
                                                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})")
                                                    appendLine("Perf Cores: ${CpuTopology.performanceCoreCount()} • Thermal: ${CpuTopology.thermalStatusLabel(context)}")
                                                    appendLine("PSS: $currentPssMb MB • Models: $downloadedMb MB")
                                                    appendLine("Fast Model: ${HushCastPrefs.isFastModelEnabled(context)} • Flash Attn: ${HushCastPrefs.isFastAttentionEnabled(context)} • Charging Only: ${HushCastPrefs.isAnalyzeOnlyWhileCharging(context)}")
                                                    appendLine("--- Analysis Statuses (${statuses.size}) ---")
                                                    statuses.takeLast(10).forEach { st ->
                                                        appendLine("${st.episodeId.take(28)}: ${st.badge} ${st.progressPercent}% [${st.stageLabel}] rss=${st.peakRssMb}MB err=${st.errorMessage ?: "none"}")
                                                    }
                                                    appendLine("--- HushCastPerf & Crashes ---")
                                                }
                                                val perfLogs = runCatching {
                                                    val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "HushCastPerf:V", "HushCastDesktop:V", "AndroidRuntime:E"))
                                                    proc.inputStream.bufferedReader().use { it.readText() }
                                                }.getOrElse { "perf logcat failed: ${it.message}" }
                                                val tailLogs = runCatching {
                                                    val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "250"))
                                                    proc.inputStream.bufferedReader().use { it.readText() }
                                                }.getOrElse { "tail logcat failed: ${it.message}" }
                                                (header + perfLogs + "\n--- Recent System Tail ---\n" + tailLogs).takeLast(90_000)
                                            }
                                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(android.content.Intent.EXTRA_SUBJECT, "HushCast Diagnostic Logs")
                                                putExtra(android.content.Intent.EXTRA_TEXT, logText)
                                            }
                                            context.startActivity(android.content.Intent.createChooser(intent, "Export HushCast Logs"))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The list every HushCast section uses: grouped rows with M3E's small gap between them. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsLazyColumn(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .then(modifier),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        content = content
    )
}

/* ---------------------------------------------------------------------------------------------
 * Sound lists (R13)
 * ------------------------------------------------------------------------------------------- */

private class BuiltInSound(
    val id: String,
    val name: String,
    val description: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
    val onTest: () -> Unit
)

/**
 * The rows of one sound list: built-in sounds first, then the user's custom sounds, then
 * "Add custom sound".  Every sound row has a radio button and a Test button; choosing a row also
 * plays it once.
 */
private fun SettingsGroupBuilder.soundRows(
    enabled: Boolean,
    builtIns: List<BuiltInSound>,
    customSounds: List<CustomSoundPreset>,
    isCustomSelected: (CustomSoundPreset) -> Boolean,
    onSelectCustom: (CustomSoundPreset) -> Unit,
    onTestCustom: (CustomSoundPreset) -> Unit,
    onRenameCustom: (CustomSoundPreset) -> Unit,
    onDeleteCustom: (CustomSoundPreset) -> Unit,
    onAdd: () -> Unit
) {
    builtIns.forEach { sound ->
        row(key = "builtin_${sound.id}") { pos ->
            SoundRow(
                position = pos,
                name = sound.name,
                description = sound.description,
                selected = sound.selected,
                enabled = enabled,
                onSelect = sound.onSelect,
                onTest = sound.onTest
            )
        }
    }
    customSounds.forEach { preset ->
        row(key = "custom_${preset.id}") { pos ->
            SoundRow(
                position = pos,
                name = preset.name,
                description = "Custom sound",
                selected = isCustomSelected(preset),
                enabled = enabled,
                onSelect = { onSelectCustom(preset) },
                onTest = { onTestCustom(preset) },
                onRename = { onRenameCustom(preset) },
                onDelete = { onDeleteCustom(preset) }
            )
        }
    }
    row(key = "add") { pos ->
        SettingsListItem(
            icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            label = "Add custom sound",
            enabled = enabled,
            index = pos.index,
            count = pos.count,
            onClick = onAdd
        )
    }
}

@Composable
private fun SoundRow(
    position: SettingsGroupPosition,
    name: String,
    description: String?,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onTest: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    SettingsListItem(
        modifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        },
        content = {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        description = description,
        enabled = enabled,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = enabled, onClick = onTest) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = "Play $name sample")
                }
                if (onRename != null || onDelete != null) {
                    Box {
                        IconButton(enabled = enabled, onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More options for $name")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (onRename != null) {
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onRename()
                                    }
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        index = position.index,
        count = position.count,
        onClick = onSelect
    )
}

/** A grouped radio row for a single choice. */
@Composable
private fun ChoiceRow(
    position: SettingsGroupPosition,
    title: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    description: String? = null
) {
    SettingsListItem(
        modifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        },
        leadingContent = {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        },
        label = title,
        description = description,
        enabled = enabled,
        index = position.index,
        count = position.count,
        onClick = onSelect
    )
}

private fun parseWorkerPairingPayload(raw: String): Pair<String, String>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("hushcast://", ignoreCase = true)) {
        val uri = android.net.Uri.parse(trimmed)
        val url = uri.getQueryParameter("url")?.trim()?.trimEnd('/') ?: return null
        if (url.isBlank()) return null
        val token = uri.getQueryParameter("token")?.trim().orEmpty()
        return url to token
    }
    if (trimmed.startsWith("{")) {
        return runCatching {
            val obj = org.json.JSONObject(trimmed)
            val url = obj.optString("url").trim().trimEnd('/')
            if (url.isBlank()) null else url to obj.optString("token").trim()
        }.getOrNull()
    }
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed.trimEnd('/') to ""
    }
    return null
}
