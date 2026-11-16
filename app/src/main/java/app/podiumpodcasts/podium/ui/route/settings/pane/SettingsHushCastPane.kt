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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import kotlinx.coroutines.flow.first
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
    var wordListExpanded by remember { mutableStateOf(false) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

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
                .fillMaxSize()
                .focusTarget(),
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
                            Text("On-Device AI Models (~455 MB)", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (modelsReady) {
                                "All models ready ($downloadedMb MB stored locally). $0 server cost, 100% private."
                            } else {
                                "Download Whisper base.en q5_1 (58 MB), Silero VAD (2 MB), and Qwen3-Embedding Q4 (395 MB) over Wi-Fi."
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

            // 1b. Performance & Battery Card
            item {
                var fastModelEnabled by remember {
                    mutableStateOf(app.hushcast.automute.engine.HushCastPrefs.isFastModelEnabled(context))
                }
                var fastAttentionEnabled by remember {
                    mutableStateOf(app.hushcast.automute.engine.HushCastPrefs.isFastAttentionEnabled(context))
                }
                var chargingOnly by remember {
                    mutableStateOf(app.hushcast.automute.engine.HushCastPrefs.isAnalyzeOnlyWhileCharging(context))
                }
                val thermalLabel = remember {
                    app.hushcast.automute.engine.CpuTopology.thermalStatusLabel(context)
                }
                val perfCores = remember {
                    app.hushcast.automute.engine.CpuTopology.performanceCoreCount()
                }
                var tinyReady by remember { mutableStateOf(modelManager.isTinyModelReady()) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Memory, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Performance & Battery", style = MaterialTheme.typography.titleMedium)
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
                                    app.hushcast.automute.engine.HushCastPrefs.setFastModelEnabled(context, enabled)
                                    if (enabled && !tinyReady && downloadProgress == null) {
                                        scope.launch {
                                            try {
                                                withContext(Dispatchers.IO) {
                                                    modelManager.ensureTinyModel { prog ->
                                                        downloadProgress = prog
                                                    }
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
                                    app.hushcast.automute.engine.HushCastPrefs.setFastAttentionEnabled(context, enabled)
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
                                    app.hushcast.automute.engine.HushCastPrefs.setAnalyzeOnlyWhileCharging(context, enabled)
                                }
                            )
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
                                Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null)
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
                                        autoMuteDb.dao().upsertRuleIfNew(
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
                                    }
                                }
                            ) {
                                Text(if (profanityAdded) "Profanity Added" else "+ Profanity Preset")
                            }
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
                                    }
                                }
                            ) {
                                Text(if (explicitAdded) "Explicit Added" else "+ Explicit Preset")
                            }
                        }
                        if (rules.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = { wordListExpanded = !wordListExpanded },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (wordListExpanded) "Hide Word List (${rules.size})"
                                    else "Show Word List (${rules.size})"
                                )
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    if (wordListExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null
                                )
                            }
                        }
                    }
                }
            }

            if (wordListExpanded) {
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
            }

            // 5. On-Device Diagnostics Card
            item {
                var queueClearedMsg by remember { mutableStateOf<String?>(null) }
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
                        queueClearedMsg?.let { msg ->
                            Spacer(Modifier.height(6.dp))
                            Text(msg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                app.hushcast.automute.worker.EpisodeAnalysisWorker.cancelAll(context)
                                queueClearedMsg = "Analysis queue cancelled."
                            }) {
                                Text("Cancel Queue")
                            }
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
                                            appendLine("Perf Cores: ${app.hushcast.automute.engine.CpuTopology.performanceCoreCount()} • Thermal: ${app.hushcast.automute.engine.CpuTopology.thermalStatusLabel(context)}")
                                            appendLine("PSS: $currentPssMb MB • Models: $downloadedMb MB")
                                            appendLine("Fast Model: ${app.hushcast.automute.engine.HushCastPrefs.isFastModelEnabled(context)} • Flash Attn: ${app.hushcast.automute.engine.HushCastPrefs.isFastAttentionEnabled(context)} • Charging Only: ${app.hushcast.automute.engine.HushCastPrefs.isAnalyzeOnlyWhileCharging(context)}")
                                            appendLine("--- Analysis Statuses (${statuses.size}) ---")
                                            statuses.takeLast(10).forEach { st ->
                                                appendLine("${st.episodeId.take(28)}: ${st.badge} ${st.progressPercent}% [${st.stageLabel}] rss=${st.peakRssMb}MB err=${st.errorMessage ?: "none"}")
                                            }
                                            appendLine("--- HushCastPerf & Crashes ---")
                                        }
                                        val perfLogs = runCatching {
                                            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-s", "HushCastPerf:V", "AndroidRuntime:E"))
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
                            }) {
                                Text("Export Logs")
                            }
                        }
                    }
                }
            }
        }
    }
}
