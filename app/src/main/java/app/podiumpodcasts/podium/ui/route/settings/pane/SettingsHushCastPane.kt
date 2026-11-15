package app.podiumpodcasts.podium.ui.route.settings.pane

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.engine.ModelDownloadProgress
import app.hushcast.automute.engine.ModelManager
import app.hushcast.automute.matcher.PresetDictionaries
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.MuteRuleEntity
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@SuppressLint("ParcelCreator")
@Serializable
class SettingsHushCastKey : SettingsPaneKey()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHushCastPane(
    navigationIcon: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }
    val modelManager = remember(context) { ModelManager(context) }

    val rules by autoMuteDb.dao().observeRules().collectAsState(initial = emptyList())
    var modelsReady by remember { mutableStateOf(modelManager.areAllModelsReady()) }
    var downloadedMb by remember { mutableStateOf((modelManager.totalDownloadedBytes() / (1024L * 1024L)).toInt()) }
    var downloadProgress by remember { mutableStateOf<ModelDownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    var wordMutingEnabled by remember { mutableStateOf(HushCastEngineBridge.wordMutingEnabled) }
    var selectedActionMode by remember { mutableStateOf(HushCastEngineBridge.defaultActionMode) }
    var selectedAdPolicy by remember { mutableStateOf(HushCastEngineBridge.defaultAdPolicy) }

    var newPattern by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }

    val currentPssMb = remember {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = am?.getProcessMemoryInfo(intArrayOf(Process.myPid()))?.firstOrNull()
        (info?.totalPss ?: 0) / 1024
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                navigationIcon = navigationIcon,
                title = { Text("HushCast Auto-Mute & Ad-Skip") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. On-Device AI Models Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("On-Device AI Models (~800 MB)", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (modelsReady) {
                                "All models ready ($downloadedMb MB stored locally). $0 server cost, 100% private."
                            } else {
                                "Download Whisper base.en (148 MB), Silero VAD (2 MB), and Qwen3-Embedding (640 MB) over Wi-Fi."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        downloadProgress?.let { p ->
                            Spacer(Modifier.height(8.dp))
                            Text("${p.modelName}: ${p.bytesDownloaded / (1024 * 1024)} MB", style = MaterialTheme.typography.labelSmall)
                            val frac = if (p.totalBytes > 0) p.bytesDownloaded.toFloat() / p.totalBytes.toFloat() else 0f
                            LinearProgressIndicator(
                                progress = { frac.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth()
                            )
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
                                                modelManager.ensureAllModels { prog ->
                                                    downloadProgress = prog
                                                }
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
                            ) {
                                Text(if (modelsReady) "Verify Models" else "Download Models (Wi-Fi)")
                            }
                            if (downloadedMb > 0) {
                                OutlinedButton(onClick = {
                                    modelManager.deleteAllModels()
                                    modelsReady = false
                                    downloadedMb = 0
                                }) {
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }

            // 2. Feature A: Word Auto-Muting Controls
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.VolumeOff, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Word Auto-Muting", style = MaterialTheme.typography.titleMedium)
                            }
                            Switch(
                                checked = wordMutingEnabled,
                                onCheckedChange = {
                                    wordMutingEnabled = it
                                    HushCastEngineBridge.wordMutingEnabled = it
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Action Mode", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        val modes = listOf(
                            MuteActionMode.HARD_SILENCE to "Silence",
                            MuteActionMode.VOLUME_DUCK to "Duck 10%",
                            MuteActionMode.SAMPLE_DROP_MICRO_SKIP to "Micro-Skip"
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { idx, (mode, label) ->
                                SegmentedButton(
                                    selected = selectedActionMode == mode,
                                    onClick = {
                                        selectedActionMode = mode
                                        HushCastEngineBridge.defaultActionMode = mode
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(idx, modes.size)
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }

            // 3. Feature B: Sponsor / Ad Blocker Controls
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Shield, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("On-Device Ad Blocker", style = MaterialTheme.typography.titleMedium)
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
                                        selectedAdPolicy = policy
                                        HushCastEngineBridge.defaultAdPolicy = policy
                                        HushCastEngineBridge.adSkipObserver.policyMode = policy
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(idx, policies.size)
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                }
            }

            // 4. Muted Words Dictionary + Presets
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Muted Words Dictionary (${rules.size} rules)", style = MaterialTheme.typography.titleMedium)
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
                                        autoMuteDb.dao().upsertRule(
                                            MuteRuleEntity(
                                                pattern = pat,
                                                isRegex = isRegex,
                                                actionMode = selectedActionMode.name
                                            )
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
                            Text("Treat as Regex", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
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
                                    autoMuteDb.dao().upsertRules(entities)
                                }
                            }) {
                                Text("+ Profanity Preset")
                            }
                            OutlinedButton(onClick = {
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
                                    autoMuteDb.dao().upsertRules(entities)
                                }
                            }) {
                                Text("+ Explicit Preset")
                            }
                        }
                    }
                }
            }

            items(rules, key = { it.id }) { rule ->
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
                                scope.launch { autoMuteDb.dao().setRuleEnabled(rule.id, enabled) }
                            }
                        )
                        IconButton(onClick = {
                            scope.launch { autoMuteDb.dao().deleteRule(rule.id) }
                        }) {
                            Icon(Icons.Rounded.Delete, contentDescription = "Delete rule")
                        }
                    }
                }
            }

            // 5. On-Device Diagnostics Card (Replaces standalone Phase 0 app)
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("On-Device Diagnostics", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Current App Memory (PSS): $currentPssMb MB • Target Ceiling: <= 1024 MB\n" +
                                "Models Stored: $downloadedMb MB • Sequential Unload: Enabled",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
