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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdPolicyMode
import app.hushcast.automute.dsp.MuteActionMode
import app.hushcast.automute.engine.ModelDownloadProgress
import app.hushcast.automute.engine.ModelManager
import app.hushcast.automute.matcher.PresetDictionaries
import app.hushcast.automute.storage.AutoMuteDatabase
import app.hushcast.automute.storage.MuteRuleEntity
import app.hushcast.automute.storage.toDomain
import app.podiumpodcasts.podium.background.HushCastEngineBridge
import app.podiumpodcasts.podium.ui.route.settings.SettingsPaneKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@kotlinx.parcelize.Parcelize
@Serializable
class SettingsHushCastKey : SettingsPaneKey()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHushCastPane(
    navigationIcon: @Composable () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsRepository = app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository.current
    val autoMuteDb = remember(context) { AutoMuteDatabase.getInstance(context) }
    val modelManager = remember(context) { ModelManager(context) }

    val rules by autoMuteDb.dao().observeRules().collectAsState(initial = emptyList())
    var modelsReady by remember { mutableStateOf(modelManager.areAllModelsReady()) }
    var downloadedMb by remember { mutableStateOf((modelManager.totalDownloadedBytes() / (1024L * 1024L)).toInt()) }
    var downloadProgress by remember { mutableStateOf<ModelDownloadProgress?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val wordMutingEnabled by settingsRepository.hushCast.wordMutingEnabled.collectAsState(initial = HushCastEngineBridge.wordMutingEnabled)
    val selectedActionMode by settingsRepository.hushCast.muteActionMode.collectAsState(initial = HushCastEngineBridge.defaultActionMode)
    val selectedAdPolicy by settingsRepository.hushCast.adPolicyMode.collectAsState(initial = HushCastEngineBridge.defaultAdPolicy)
    val muteCustomSoundUri by settingsRepository.hushCast.muteCustomSoundUri.collectAsState(initial = null)
    val sponsorSkipSoundEnabled by settingsRepository.hushCast.sponsorSkipSoundEnabled.collectAsState(initial = HushCastEngineBridge.sponsorSkipSoundEnabled)
    val sponsorSkipSoundUri by settingsRepository.hushCast.sponsorSkipSoundUri.collectAsState(initial = HushCastEngineBridge.sponsorSkipSoundUri)

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
            val uriStr = uri.toString()
            HushCastEngineBridge.loadCustomMuteSound(context, uriStr)
            scope.launch { settingsRepository.hushCast.setMuteCustomSoundUri(uriStr) }
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
            val uriStr = uri.toString()
            HushCastEngineBridge.sponsorSkipSoundUri = uriStr
            scope.launch { settingsRepository.hushCast.setSponsorSkipSoundUri(uriStr) }
        }
    }

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
            item {
                Text(
                    text = "PROCESSING",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 1. On-Device AI Models Card (collapsible when Desktop/Cloud Worker is active)
            item {
                val desktopActive = remember {
                    app.hushcast.automute.engine.HushCastPrefs.isDesktopWorkerEnabled(context) &&
                        app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerUrl(context).isNotBlank()
                }
                var showLocalModels by remember {
                    mutableStateOf(!desktopActive)
                }
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
                                Text("On-Device AI Models (~455 MB)", style = MaterialTheme.typography.titleMedium)
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
                                    desktopActive -> "Optional offline fallback. Your Cloud / Desktop Worker handles analysis."
                                    else -> "Tap to download or manage on-device models."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                modelsReady -> "All models ready ($downloadedMb MB stored locally). $0 server cost, 100% private."
                                desktopActive -> "Optional offline fallback: your Cloud / Desktop Worker is active, so on-device models aren't needed unless you want offline analysis."
                                else -> "Download Whisper base.en q5_1 (58 MB), Silero VAD (2 MB), and Qwen3-Embedding Q4 (395 MB) over Wi-Fi."
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

            // 1c. Desktop Companion (Mac / GPU) Card
            item {
                var desktopEnabled by remember {
                    mutableStateOf(app.hushcast.automute.engine.HushCastPrefs.isDesktopWorkerEnabled(context))
                }
                var desktopUrl by remember {
                    mutableStateOf(app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerUrl(context))
                }
                var desktopToken by remember {
                    mutableStateOf(app.hushcast.automute.engine.HushCastPrefs.getDesktopWorkerToken(context))
                }
                var desktopStatus by remember { mutableStateOf<String?>(null) }
                var desktopBusy by remember { mutableStateOf(false) }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                Text("Desktop Companion (Mac / GPU)", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Have your Mac download & analyze episodes, then push exact audio + index over Wi-Fi.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = desktopEnabled,
                                onCheckedChange = { enabled ->
                                    desktopEnabled = enabled
                                    app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerEnabled(context, enabled)
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
                                        if (!state.isFocused) {
                                            app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerUrl(context, desktopUrl)
                                        }
                                    }
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = desktopToken,
                                onValueChange = { desktopToken = it },
                                label = { Text("Pairing Token") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { state ->
                                        if (!state.isFocused) {
                                            app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerToken(context, desktopToken)
                                        }
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
                                                val raw = barcode.rawValue.orEmpty()
                                                val parsed = parseWorkerPairingPayload(raw)
                                                if (parsed == null) {
                                                    desktopStatus = "Unrecognized QR code."
                                                    desktopBusy = false
                                                    return@addOnSuccessListener
                                                }
                                                val (scannedUrl, scannedToken) = parsed
                                                desktopUrl = scannedUrl
                                                app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerUrl(context, scannedUrl)
                                                if (scannedToken.isNotBlank()) {
                                                    desktopToken = scannedToken
                                                    app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerToken(context, scannedToken)
                                                }
                                                desktopStatus = "Scanned $scannedUrl — verifying…"
                                                 scope.launch {
                                                    try {
                                                        val effectiveToken = scannedToken.ifBlank { desktopToken }
                                                        val health = app.hushcast.automute.desktop.DesktopWorkerClient.healthCheck(
                                                            baseUrl = scannedUrl,
                                                            token = effectiveToken
                                                        )
                                                        val authErr = runCatching {
                                                            app.hushcast.automute.desktop.DesktopWorkerClient.listReadyEpisodes(
                                                                baseUrl = scannedUrl,
                                                                token = effectiveToken
                                                            )
                                                        }.exceptionOrNull()
                                                        desktopStatus = if (authErr != null) {
                                                            "Server reachable (${health.hostname}), but auth failed: ${authErr.message ?: authErr.javaClass.simpleName}"
                                                        } else {
                                                            "Connected: ${health.hostname} (${health.accelerator.uppercase()}) • ${health.whisperModel}"
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
                                                desktopStatus = "QR scanner unavailable (${e.message ?: "no Play Services"}). Scan with your Camera app instead."
                                                desktopBusy = false
                                            }
                                    }
                                ) {
                                    Text("Scan QR")
                                }
                                OutlinedButton(
                                    enabled = !desktopBusy,
                                    onClick = {
                                        desktopBusy = true
                                        desktopStatus = "Searching Wi-Fi for _hushcast._tcp…"
                                        scope.launch {
                                            val found = app.hushcast.automute.desktop.DesktopWorkerDiscovery.discoverFirst(context)
                                            if (found != null) {
                                                desktopUrl = found.url
                                                app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerUrl(context, found.url)
                                                desktopStatus = "Found ${found.serviceName} at ${found.url}"
                                            } else {
                                                desktopStatus = "No HushCast worker found on local Wi-Fi."
                                            }
                                            desktopBusy = false
                                        }
                                    }
                                ) {
                                    Text("Discover")
                                }
                                Button(
                                    enabled = !desktopBusy && desktopUrl.isNotBlank(),
                                    onClick = {
                                        desktopBusy = true
                                        desktopStatus = "Connecting…"
                                        scope.launch {
                                            try {
                                                val health = app.hushcast.automute.desktop.DesktopWorkerClient.healthCheck(
                                                    baseUrl = desktopUrl,
                                                    token = desktopToken
                                                )
                                                val authErr = runCatching {
                                                    app.hushcast.automute.desktop.DesktopWorkerClient.listReadyEpisodes(
                                                        baseUrl = desktopUrl,
                                                        token = desktopToken
                                                    )
                                                }.exceptionOrNull()
                                                if (authErr != null) {
                                                    desktopStatus = "Server reachable (${health.hostname}), but auth failed: ${authErr.message ?: authErr.javaClass.simpleName}"
                                                } else {
                                                    val modelNote = buildString {
                                                        if (health.whisperModel.isNotBlank()) append(" • ${health.whisperModel}")
                                                        if (health.chatModelReady) append(" • LLM Chat")
                                                    }
                                                    desktopStatus = "Connected: ${health.hostname} (${health.accelerator.uppercase()}) • " +
                                                        (if (health.modelsReady) "Models Ready" else "Models Missing") +
                                                        "$modelNote • ${health.cachedEpisodes} cached"
                                                }
                                            } catch (t: Throwable) {
                                                desktopStatus = "Connection failed: ${t.message ?: t.javaClass.simpleName}"
                                            } finally {
                                                desktopBusy = false
                                            }
                                        }
                                    }
                                ) {
                                    Text("Test")
                                }
                            }
                            if (desktopStatus != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    desktopStatus!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            if (desktopUrl.isNotBlank()) {
                                Spacer(Modifier.height(14.dp))
                                HorizontalDivider()
                                Spacer(Modifier.height(10.dp))
                                Text("Semantic Search Across Episodes", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Search topics or concepts across all desktop-analyzed episodes using Qwen3 embeddings.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(6.dp))
                                var searchQuery by remember { mutableStateOf("") }
                                var searchBusy by remember { mutableStateOf(false) }
                                var searchError by remember { mutableStateOf<String?>(null) }
                                var searchHits by remember {
                                    mutableStateOf<List<app.hushcast.automute.desktop.DesktopSearchHit>>(emptyList())
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        label = { Text("Ask or search a topic…") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        enabled = !searchBusy && searchQuery.isNotBlank(),
                                        onClick = {
                                            searchBusy = true
                                            searchError = null
                                            scope.launch {
                                                try {
                                                    val resp = app.hushcast.automute.desktop.DesktopWorkerClient.semanticSearch(
                                                        baseUrl = desktopUrl,
                                                        token = desktopToken,
                                                        query = searchQuery,
                                                        topK = 8
                                                    )
                                                    searchHits = resp.hits
                                                    if (resp.hits.isEmpty()) {
                                                        searchError = "No matching moments found in cached episodes."
                                                    }
                                                } catch (t: Throwable) {
                                                    searchError = t.message ?: t.javaClass.simpleName
                                                } finally {
                                                    searchBusy = false
                                                }
                                            }
                                        }
                                    ) {
                                        Text(if (searchBusy) "…" else "Search")
                                    }
                                }
                                searchError?.let { err ->
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        err,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (searchHits.isNotEmpty()) {
                                    Spacer(Modifier.height(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        searchHits.forEach { hit ->
                                            val totalSec = (hit.startMs / 1000L).coerceAtLeast(0L)
                                            val h = totalSec / 3600L
                                            val m = (totalSec % 3600L) / 60L
                                            val s = totalSec % 60L
                                            val ts = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
                                            Surface(
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(10.dp)) {
                                                    Text(
                                                        text = "${hit.episodeTitle.ifBlank { hit.episodeId }} • $ts (${"%.2f".format(hit.score)})",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Spacer(Modifier.height(2.dp))
                                                    Text(
                                                        text = hit.text,
                                                        style = MaterialTheme.typography.bodySmall
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        var autoDownloadMethod by remember {
                            mutableStateOf(app.hushcast.automute.engine.HushCastPrefs.getAutoDownloadMethod(context))
                        }
                        Text("Auto-Download Analysis", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "How newly auto-downloaded episodes are processed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        val autoOptions = listOf(
                            app.hushcast.automute.engine.DownloadAnalysisMethod.AUTO to "Auto",
                            app.hushcast.automute.engine.DownloadAnalysisMethod.ON_DEVICE to "On-Device",
                            app.hushcast.automute.engine.DownloadAnalysisMethod.SKIP_ANALYSIS to "Skip"
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            autoOptions.forEachIndexed { index, (method, label) ->
                                SegmentedButton(
                                    selected = autoDownloadMethod == method,
                                    onClick = {
                                        autoDownloadMethod = method
                                        app.hushcast.automute.engine.HushCastPrefs.setAutoDownloadMethod(context, method)
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = autoOptions.size)
                                ) {
                                    Text(label)
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
                                Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Word Auto-Muting", style = MaterialTheme.typography.titleMedium)
                            }
                            Switch(
                                checked = wordMutingEnabled,
                                onCheckedChange = {
                                    HushCastEngineBridge.wordMutingEnabled = it
                                    scope.launch { settingsRepository.hushCast.setWordMutingEnabled(it) }
                                }
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Action Mode", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(6.dp))
                        val modes = listOf(
                            MuteActionMode.HARD_SILENCE to "Silence",
                            MuteActionMode.VOLUME_DUCK to "Duck 10%",
                            MuteActionMode.SAMPLE_DROP_MICRO_SKIP to "Micro-Skip",
                            MuteActionMode.BEEP to "Bleep",
                            MuteActionMode.CUSTOM_SOUND to "Custom"
                        )
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            modes.forEach { (mode, label) ->
                                androidx.compose.material3.FilterChip(
                                    selected = selectedActionMode == mode,
                                    onClick = {
                                        HushCastEngineBridge.defaultActionMode = mode
                                        scope.launch { settingsRepository.hushCast.setMuteActionMode(mode) }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                        if (selectedActionMode == MuteActionMode.CUSTOM_SOUND) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (muteCustomSoundUri != null) "Custom sound selected" else "Default bleep (no file chosen)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                androidx.compose.material3.TextButton(onClick = { muteSoundPicker.launch(arrayOf("audio/*")) }) {
                                    Text(if (muteCustomSoundUri != null) "Change" else "Choose file")
                                }
                                if (muteCustomSoundUri != null) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        HushCastEngineBridge.loadCustomMuteSound(context, null)
                                        scope.launch { settingsRepository.hushCast.setMuteCustomSoundUri(null) }
                                    }) {
                                        Text("Clear")
                                    }
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
                                        HushCastEngineBridge.defaultAdPolicy = policy
                                        HushCastEngineBridge.adSkipObserver.policyMode = policy
                                        scope.launch { settingsRepository.hushCast.setAdPolicyMode(policy) }
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(idx, policies.size)
                                ) {
                                    Text(label)
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
                                Text("Sponsor skip indicator sound", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (sponsorSkipSoundUri != null) "Custom sound selected" else "Play a subtle chime when an ad is skipped",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = sponsorSkipSoundEnabled,
                                onCheckedChange = { enabled ->
                                    HushCastEngineBridge.sponsorSkipSoundEnabled = enabled
                                    scope.launch { settingsRepository.hushCast.setSponsorSkipSoundEnabled(enabled) }
                                }
                            )
                        }
                        if (sponsorSkipSoundEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                androidx.compose.material3.TextButton(onClick = { skipSoundPicker.launch(arrayOf("audio/*")) }) {
                                    Text(if (sponsorSkipSoundUri != null) "Change sound" else "Custom sound")
                                }
                                if (sponsorSkipSoundUri != null) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        HushCastEngineBridge.sponsorSkipSoundUri = null
                                        scope.launch { settingsRepository.hushCast.setSponsorSkipSoundUri(null) }
                                    }) {
                                        Text("Reset to chime")
                                    }
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
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val analyzer = app.hushcast.automute.pipeline.AutoMuteAnalyzer(context, autoMuteDb)
                                            val indices = autoMuteDb.dao().getAllWordIndices()
                                            for (idx in indices) {
                                                analyzer.reevaluateMuteRules(idx.fileSha256, idx.episodeId, defaultActionMode = HushCastEngineBridge.defaultActionMode)
                                                if (idx.episodeId == HushCastEngineBridge.currentEpisodeId) {
                                                    val updated = autoMuteDb.dao().getMuteSegments(idx.fileSha256).map { it.toDomain() }
                                                    HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
                                                }
                                            }
                                        }
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
                                Text("Treat as Regex", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "Tip: Use \\bword\\b for exact whole-word matching",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                                    scope.launch {
                                        autoMuteDb.dao().setRuleEnabled(rule.id, enabled)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            val analyzer = app.hushcast.automute.pipeline.AutoMuteAnalyzer(context, autoMuteDb)
                                            val indices = autoMuteDb.dao().getAllWordIndices()
                                            for (idx in indices) {
                                                analyzer.reevaluateMuteRules(idx.fileSha256, idx.episodeId, defaultActionMode = HushCastEngineBridge.defaultActionMode)
                                                if (idx.episodeId == HushCastEngineBridge.currentEpisodeId) {
                                                    val updated = autoMuteDb.dao().getMuteSegments(idx.fileSha256).map { it.toDomain() }
                                                    HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
                                                }
                                            }
                                        }
                                    }
                                }
                            )
                            IconButton(onClick = {
                                scope.launch {
                                    autoMuteDb.dao().deleteRule(rule.id)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        val analyzer = app.hushcast.automute.pipeline.AutoMuteAnalyzer(context, autoMuteDb)
                                        val indices = autoMuteDb.dao().getAllWordIndices()
                                        for (idx in indices) {
                                            analyzer.reevaluateMuteRules(idx.fileSha256, idx.episodeId, defaultActionMode = HushCastEngineBridge.defaultActionMode)
                                            if (idx.episodeId == HushCastEngineBridge.currentEpisodeId) {
                                                val updated = autoMuteDb.dao().getMuteSegments(idx.fileSha256).map { it.toDomain() }
                                                HushCastEngineBridge.autoMuteProcessor.updateWindows(updated)
                                            }
                                        }
                                    }
                                }
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
