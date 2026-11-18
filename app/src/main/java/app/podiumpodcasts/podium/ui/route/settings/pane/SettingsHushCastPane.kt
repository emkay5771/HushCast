package app.podiumpodcasts.podium.ui.route.settings.pane

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hushcast.automute.dsp.AdAutoSkipScope
import app.hushcast.automute.dsp.AdPolicyMode
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
import app.hushcast.automute.storage.toDomain
import app.hushcast.automute.worker.EpisodeAnalysisWorker
import app.hushcast.automute.worker.RuleReevaluationWorker
import app.podiumpodcasts.podium.CustomSoundPreset
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.component.PodiumSnackbarHost
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
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
                            HushCastEngineBridge.loadCustomMuteSound(context, uriStr)
                            settingsRepository.hushCast.setMuteCustomSoundUri(uriStr)
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
                val workerConfigured = HushCastPrefs.isDesktopWorkerEnabled(context) &&
                    HushCastPrefs.getDesktopWorkerUrl(context).isNotBlank()
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

                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                ) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
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

                    item {
                        SettingsListItem(
                            icon = { Icon(Icons.AutoMirrored.Rounded.VolumeOff, null) },
                            label = "Muting",
                            description = if (wordMutingEnabled) "Enabled · ${selectedActionMode.name.lowercase().replace('_', ' ')}" else "Off",
                            index = 0,
                            count = 5,
                            onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.MUTING)) }
                        )
                    }
                    item {
                        SettingsListItem(
                            icon = { Icon(Icons.Rounded.Shield, null) },
                            label = "Sponsor skipping",
                            description = when (selectedAdPolicy) {
                                AdPolicyMode.AUTO_SKIP -> "Auto-skip"
                                AdPolicyMode.MARK_ONLY -> "Mark only"
                                AdPolicyMode.OFF -> "Off"
                            },
                            index = 1,
                            count = 5,
                            onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.SPONSORS)) }
                        )
                    }
                    item {
                        SettingsListItem(
                            icon = { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, null) },
                            label = "Word list",
                            description = "${rules.size} rules",
                            index = 2,
                            count = 5,
                            onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.WORD_LIST)) }
                        )
                    }
                    item {
                        SettingsListItem(
                            icon = { Icon(Icons.Rounded.Download, null) },
                            label = "Processing",
                            description = whereLabel ?: "Not set up yet",
                            index = 3,
                            count = 5,
                            onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.PROCESSING)) }
                        )
                    }
                    item {
                        SettingsListItem(
                            icon = { Icon(Icons.Rounded.Tune, null) },
                            label = "Advanced",
                            description = "Performance, battery & diagnostics",
                            index = 4,
                            count = 5,
                            onClick = { onNavigate(SettingsHushCastKey(HushCastSettingsSection.ADVANCED)) }
                        )
                    }
                }
            }

            HushCastSettingsSection.MUTING -> {
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Word auto-muting", style = MaterialTheme.typography.titleMedium)
                                    }
                                    Switch(
                                        checked = wordMutingEnabled,
                                        onCheckedChange = {
                                            HushCastEngineBridge.wordMutingEnabled = it
                                            scope.launch { settingsRepository.hushCast.setWordMutingEnabled(it) }
                                        }
                                    )
                                }

                                Column(modifier = Modifier.alpha(if (wordMutingEnabled) 1f else 0.5f)) {
                                    Spacer(Modifier.height(12.dp))
                                    Text("Action mode", style = MaterialTheme.typography.labelMedium)
                                    Spacer(Modifier.height(6.dp))
                                    val modes = listOf(
                                        MuteActionMode.HARD_SILENCE to "Silence",
                                        MuteActionMode.VOLUME_DUCK to "Duck 10%",
                                        MuteActionMode.SAMPLE_DROP_MICRO_SKIP to "Micro-Skip",
                                        MuteActionMode.BEEP to "Bleep",
                                        MuteActionMode.CUSTOM_SOUND to "Custom"
                                    )
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        modes.forEach { (mode, label) ->
                                            FilterChip(
                                                enabled = wordMutingEnabled,
                                                selected = selectedActionMode == mode,
                                                onClick = {
                                                    scope.launch {
                                                        settingsRepository.hushCast.setMuteActionMode(mode)
                                                    }
                                                },
                                                label = { Text(label) }
                                            )
                                        }
                                    }

                                    if (selectedActionMode == MuteActionMode.CUSTOM_SOUND) {
                                        Spacer(Modifier.height(10.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Custom sound presets", style = MaterialTheme.typography.labelMedium)
                                            TextButton(
                                                enabled = wordMutingEnabled,
                                                onClick = { muteSoundPicker.launch(arrayOf("audio/*")) }
                                            ) { Text("+ Add sound") }
                                        }
                                        if (customSounds.isEmpty()) {
                                            Text(
                                                text = "No custom sounds saved yet. Default bleep will be used until you add one.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                customSounds.forEach { preset ->
                                                    val isSelected = muteCustomSoundUri == preset.uri
                                                    Surface(
                                                        shape = RoundedCornerShape(10.dp),
                                                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                                        else MaterialTheme.colorScheme.surfaceContainerLow,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable(enabled = wordMutingEnabled) {
                                                                HushCastEngineBridge.loadCustomMuteSound(context, preset.uri)
                                                                scope.launch { settingsRepository.hushCast.setMuteCustomSoundUri(preset.uri) }
                                                            }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(horizontal = 8.dp, vertical = 4.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.SpaceBetween
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                RadioButton(
                                                                    enabled = wordMutingEnabled,
                                                                    selected = isSelected,
                                                                    onClick = {
                                                                        HushCastEngineBridge.loadCustomMuteSound(context, preset.uri)
                                                                        scope.launch { settingsRepository.hushCast.setMuteCustomSoundUri(preset.uri) }
                                                                    }
                                                                )
                                                                Text(
                                                                    text = preset.name,
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    maxLines = 1,
                                                                    overflow = TextOverflow.Ellipsis
                                                                )
                                                            }
                                                            Row {
                                                                IconButton(
                                                                    enabled = wordMutingEnabled,
                                                                    onClick = {
                                                                        renameText = preset.name
                                                                        renamingSound = preset
                                                                    }
                                                                ) {
                                                                    Icon(Icons.Rounded.Edit, contentDescription = "Rename", modifier = Modifier.size(18.dp))
                                                                }
                                                                IconButton(
                                                                    enabled = wordMutingEnabled,
                                                                    onClick = {
                                                                        if (isSelected) HushCastEngineBridge.loadCustomMuteSound(context, null)
                                                                        scope.launch { settingsRepository.hushCast.removeCustomSound(preset.id) }
                                                                    }
                                                                ) {
                                                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", modifier = Modifier.size(18.dp))
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    var selectedPrecision by remember {
                                        mutableStateOf(HushCastPrefs.getMuteTimingPrecision(context))
                                    }
                                    Text("Timing precision", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        "Controls pre/post padding around each matched word.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        MuteTimingPrecision.entries.forEach { precision ->
                                            FilterChip(
                                                enabled = wordMutingEnabled,
                                                selected = selectedPrecision == precision,
                                                onClick = {
                                                    selectedPrecision = precision
                                                    HushCastPrefs.setMuteTimingPrecision(context, precision)
                                                    RuleReevaluationWorker.enqueue(
                                                        context = context,
                                                        prePadMs = precision.prePadMs,
                                                        postPadMs = precision.postPadMs,
                                                        defaultActionMode = selectedActionMode
                                                    )
                                                },
                                                label = { Text(precision.label) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HushCastSettingsSection.SPONSORS -> {
                val skippingOn = selectedAdPolicy != AdPolicyMode.OFF
                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Shield, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Sponsor & section skipping", style = MaterialTheme.typography.titleMedium)
                                }
                                Spacer(Modifier.height(8.dp))
                                val policies = listOf(
                                    AdPolicyMode.AUTO_SKIP to "Auto-Skip",
                                    AdPolicyMode.MARK_ONLY to "Mark Only",
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
                                        ) { Text(label) }
                                    }
                                }

                                Column(modifier = Modifier.alpha(if (skippingOn) 1f else 0.5f)) {
                                    if (selectedAdPolicy == AdPolicyMode.AUTO_SKIP) {
                                        Spacer(Modifier.height(12.dp))
                                        Text("Confidence", style = MaterialTheme.typography.labelMedium)
                                        Spacer(Modifier.height(4.dp))
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
                                        Spacer(Modifier.height(6.dp))
                                        val scopes = listOf(
                                            AdAutoSkipScope.HIGH_CONFIDENCE_ONLY to "High (80%+)",
                                            AdAutoSkipScope.MODERATE_AND_HIGH to "Balanced",
                                            AdAutoSkipScope.ALL_DETECTED to "All Ads"
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
                                                ) { Text(label) }
                                            }
                                        }
                                        Spacer(Modifier.height(12.dp))
                                        Text("Section types to auto-skip", style = MaterialTheme.typography.labelMedium)
                                        Spacer(Modifier.height(4.dp))
                                        val sectionTypes = listOf(
                                            "sponsor_ad" to "Sponsors / Ads",
                                            "house_promo" to "Show Promos",
                                            "membership_pitch" to "Membership Pitches",
                                            "boilerplate" to "Intros / Outros"
                                        )
                                        FlowRow(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            sectionTypes.forEach { (cls, label) ->
                                                val checked = cls in selectedClasses
                                                FilterChip(
                                                    enabled = skippingOn,
                                                    selected = checked,
                                                    onClick = {
                                                        val updated = if (checked) selectedClasses - cls else selectedClasses + cls
                                                        HushCastEngineBridge.autoSkipClasses = updated
                                                        scope.launch { settingsRepository.hushCast.setAdAutoSkipClasses(updated) }
                                                    },
                                                    label = { Text(label) }
                                                )
                                            }
                                        }
                                    }

                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Skip chime", style = MaterialTheme.typography.bodyMedium)
                                            Text(
                                                text = if (sponsorSkipSoundUri != null) "Custom sound selected" else "Play a subtle chime when an ad is skipped",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            enabled = skippingOn,
                                            checked = sponsorSkipSoundEnabled,
                                            onCheckedChange = { enabled ->
                                                HushCastEngineBridge.sponsorSkipSoundEnabled = enabled
                                                scope.launch { settingsRepository.hushCast.setSponsorSkipSoundEnabled(enabled) }
                                            }
                                        )
                                    }
                                    if (sponsorSkipSoundEnabled && skippingOn) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End
                                        ) {
                                            TextButton(onClick = {
                                                HushCastEngineBridge.playSponsorSkipIndicator(context, forceTest = true)
                                            }) { Text("Test chime") }
                                            TextButton(onClick = { skipSoundPicker.launch(arrayOf("audio/*")) }) {
                                                Text(if (sponsorSkipSoundUri != null) "Change sound" else "Custom sound")
                                            }
                                            if (sponsorSkipSoundUri != null) {
                                                TextButton(onClick = {
                                                    HushCastEngineBridge.sponsorSkipSoundUri = null
                                                    scope.launch { settingsRepository.hushCast.setSponsorSkipSoundUri(null) }
                                                }) { Text("Reset to chime") }
                                            }
                                        }
                                    }
                                }
                            }
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

                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .focusTarget(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Add word or pattern", style = MaterialTheme.typography.titleMedium)
                                if (reevalInfos.any { !it.state.isFinished }) {
                                    Text(
                                        "Updating episodes…",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
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
                                                        actionMode = selectedActionMode.name
                                                    )
                                                )
                                                RuleReevaluationWorker.enqueue(
                                                    context = context,
                                                    defaultActionMode = selectedActionMode
                                                )
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Add, contentDescription = "Add")
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Switch(checked = isRegex, onCheckedChange = { isRegex = it })
                                    Spacer(Modifier.width(6.dp))
                                    Column {
                                        Text("Treat as regex", style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "Tip: Use \\bword\\b for exact whole-word matching",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                val existingKeys = remember(rules) {
                                    rules.map { it.pattern.trim().lowercase() to it.isRegex }.toSet()
                                }
                                val profanityAdded = remember(existingKeys) {
                                    PresetDictionaries.profanityRules(selectedActionMode).all {
                                        (it.pattern.trim().lowercase() to it.isRegex) in existingKeys
                                    }
                                }
                                val explicitAdded = remember(existingKeys) {
                                    PresetDictionaries.explicitRules(selectedActionMode).all {
                                        (it.pattern.trim().lowercase() to it.isRegex) in existingKeys
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        enabled = !profanityAdded,
                                        onClick = {
                                            scope.launch {
                                                val entities = PresetDictionaries.profanityRules(selectedActionMode).map {
                                                    MuteRuleEntity(
                                                        pattern = it.pattern,
                                                        isRegex = it.isRegex,
                                                        matchPlurals = it.matchPlurals,
                                                        actionMode = it.actionMode.name,
                                                        category = it.category
                                                    )
                                                }
                                                autoMuteDb.dao().upsertRulesIfNew(entities)
                                                RuleReevaluationWorker.enqueue(context = context, defaultActionMode = selectedActionMode)
                                            }
                                        }
                                    ) { Text(if (profanityAdded) "Profanity added" else "+ Profanity preset") }
                                    OutlinedButton(
                                        enabled = !explicitAdded,
                                        onClick = {
                                            scope.launch {
                                                val entities = PresetDictionaries.explicitRules(selectedActionMode).map {
                                                    MuteRuleEntity(
                                                        pattern = it.pattern,
                                                        isRegex = it.isRegex,
                                                        matchPlurals = it.matchPlurals,
                                                        actionMode = it.actionMode.name,
                                                        category = it.category
                                                    )
                                                }
                                                autoMuteDb.dao().upsertRulesIfNew(entities)
                                                RuleReevaluationWorker.enqueue(context = context, defaultActionMode = selectedActionMode)
                                            }
                                        }
                                    ) { Text(if (explicitAdded) "Explicit added" else "+ Explicit preset") }
                                }
                            }
                        }
                    }

                    if (rules.isNotEmpty()) {
                        item {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                label = { Text("Search ${rules.size} rules") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    items(filteredRules, key = { it.id }) { rule ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart || value == SwipeToDismissBoxValue.StartToEnd) {
                                    deleteRuleWithUndo(rule)
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(rule.pattern, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "${rule.category} • ${rule.actionMode}${if (rule.isRegex) " • Regex" else ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = rule.enabled,
                                        onCheckedChange = { enabled ->
                                            scope.launch {
                                                autoMuteDb.dao().setRuleEnabled(rule.id, enabled)
                                                RuleReevaluationWorker.enqueue(context = context, defaultActionMode = selectedActionMode)
                                            }
                                        }
                                    )
                                    IconButton(onClick = { deleteRuleWithUndo(rule) }) {
                                        Icon(Icons.Rounded.Delete, contentDescription = "Delete rule")
                                    }
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

                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .focusTarget(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Desktop / cloud worker", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "Have your Mac or GPU worker download & analyze episodes, then push exact audio + index over Wi-Fi.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = desktopEnabled,
                                        onCheckedChange = { enabled ->
                                            desktopEnabled = enabled
                                            HushCastPrefs.setDesktopWorkerEnabled(context, enabled)
                                        }
                                    )
                                }

                                if (desktopEnabled) {
                                    Spacer(Modifier.height(10.dp))
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
                                    if (desktopStatus != null) {
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            desktopStatus!!,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Spacer(Modifier.height(14.dp))
                                Text("Auto-download analysis", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "How newly auto-downloaded episodes are processed.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                val autoOptions = listOf(
                                    DownloadAnalysisMethod.AUTO to "Auto",
                                    DownloadAnalysisMethod.ON_DEVICE to "On-Device",
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
                                        ) { Text(label) }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.Download, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                        Text("On-device AI models (~455 MB)", style = MaterialTheme.typography.titleMedium)
                                    }
                                    IconButton(onClick = { showLocalModels = !showLocalModels }) {
                                        Icon(
                                            imageVector = if (showLocalModels) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                            contentDescription = if (showLocalModels) "Collapse" else "Expand"
                                        )
                                    }
                                }
                                if (!showLocalModels) {
                                    Text(
                                        text = when {
                                            modelsReady -> "Ready ($downloadedMb MB local). Tap to manage."
                                            desktopActive -> "Optional offline fallback. Your worker handles analysis."
                                            else -> "Tap to download or manage on-device models."
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Spacer(Modifier.height(4.dp))
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
                                            }) { Text("Delete") }
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

                LazyColumn(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Memory, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Performance & battery", style = MaterialTheme.typography.titleMedium)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Using $perfCores performance CPU cores • Thermal: $thermalLabel",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Fast transcription model (tiny.en)", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            if (tinyReady) "2–3× faster (32 MB). Slightly lower word accuracy."
                                            else "2–3× faster (downloads 32 MB tiny.en q5_1).",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
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
                                        }
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Fast timing mode (Flash Attention)", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "1.3× faster by skipping DTW word alignment; mute timing is slightly looser.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = fastAttentionEnabled,
                                        onCheckedChange = { enabled ->
                                            fastAttentionEnabled = enabled
                                            HushCastPrefs.setFastAttentionEnabled(context, enabled)
                                        }
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                        Text("Analyze only while charging", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "Defers episode analysis until plugged in to save battery and prevent heat.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = chargingOnly,
                                        onCheckedChange = { enabled ->
                                            chargingOnly = enabled
                                            HushCastPrefs.setAnalyzeOnlyWhileCharging(context, enabled)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                    Text("Developer mode", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Show timeline export, diagnostic logs, and stage-specific cloud reprocessing",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = devMode,
                                    onCheckedChange = { enabled ->
                                        scope.launch { settingsRepository.hushCast.setDevMode(enabled) }
                                    }
                                )
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Memory, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Diagnostics", style = MaterialTheme.typography.titleMedium)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Current App Memory (PSS): $currentPssMb MB • Target Ceiling: <= 1024 MB\n" +
                                        "Models Stored: $downloadedMb MB • Sequential Unload: Enabled",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                queueClearedMsg?.let { msg ->
                                    Spacer(Modifier.height(6.dp))
                                    Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        EpisodeAnalysisWorker.cancelAll(context)
                                        queueClearedMsg = "Analysis queue cancelled."
                                    }) { Text("Cancel queue") }
                                    if (devMode) {
                                        OutlinedButton(onClick = {
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
                                        }) { Text("Export logs") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
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
