package app.podiumpodcasts.podium.ui.component.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.hushcast.automute.dsp.AdSkipEvent
import app.hushcast.automute.dsp.ReadinessBadge
import app.hushcast.automute.engine.HushCastPrefs
import app.hushcast.automute.engine.ModelManager
import app.hushcast.automute.worker.ModelDownloadWorker

@Composable
fun EpisodeReadinessBadge(
    badge: ReadinessBadge,
    progressPercent: Int = 0,
    onAnalyzeNowClick: (() -> Unit)? = null
) {
    val isActionableNotProcessed = badge == ReadinessBadge.NOT_PROCESSED && onAnalyzeNowClick != null
    val (label, containerColor, contentColor, icon) = when {
        isActionableNotProcessed -> Quad(
            "Analyze w/ HushCast AI",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.Rounded.AutoAwesome
        )
        badge == ReadinessBadge.READY -> Quad(
            "HushCast Ready",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            Icons.Rounded.CheckCircle
        )
        badge == ReadinessBadge.WORD_MUTE_READY -> Quad(
            "Word-Mute Ready",
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            Icons.AutoMirrored.Rounded.VolumeOff
        )
        badge == ReadinessBadge.ANALYZING -> Quad(
            "Analyzing $progressPercent%",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            Icons.Rounded.HourglassTop
        )
        else -> Quad(
            "Not Processed",
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Rounded.Info
        )
    }

    val clickableModifier = if (onAnalyzeNowClick != null && badge != ReadinessBadge.ANALYZING) {
        Modifier.clickable(onClick = onAnalyzeNowClick)
    } else {
        Modifier
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(clickableModifier)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
fun OneTapUndoAdSkipBanner(
    event: AdSkipEvent,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.SkipNext, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Skipped ${event.adWindow.label} (${((event.jumpedToMs - event.jumpedFromMs) / 1000L)}s)",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(onClick = onUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Undo", color = MaterialTheme.colorScheme.inversePrimary)
            }
        }
    }
}

@Composable
fun CastingBypassNoticeBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "Casting active: on-device word muting is bypassed on external receivers.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HushCastAnalyzeDialog(
    episodeTitle: String,
    isDownloaded: Boolean,
    onDismiss: () -> Unit,
    onAnalyzeDesktop: () -> Unit,
    onAnalyzeOnDevice: () -> Unit,
    onDownloadAudioOnly: () -> Unit
) {
    val context = LocalContext.current
    val desktopConnected = remember(context) {
        HushCastPrefs.isDesktopWorkerEnabled(context) &&
            HushCastPrefs.getDesktopWorkerUrl(context).isNotBlank()
    }
    val localModelsReady = remember(context) {
        ModelManager(context).areAllModelsReady()
    }

    val title: String
    val body: String
    val primaryLabel: String
    val primaryAction: () -> Unit
    val secondaryLabel: String?
    val secondaryAction: (() -> Unit)?

    when {
        desktopConnected -> {
            title = if (isDownloaded) "Refresh on Desktop / Cloud" else "Analyze with Desktop / Cloud"
            body = if (isDownloaded) {
                "Re-download and analyze \"$episodeTitle\" on your connected Desktop/Cloud Worker for fresh transcript, speakers, chapters, and ad markers."
            } else {
                "Download and analyze \"$episodeTitle\" on your connected Desktop/Cloud Worker, then stream the exact audio + AI bundle to your phone."
            }
            primaryLabel = if (isDownloaded) "Refresh on Desktop" else "Download & Analyze (Worker)"
            primaryAction = onAnalyzeDesktop
            if (localModelsReady) {
                secondaryLabel = if (isDownloaded) "Analyze On-Device" else "On-Device Instead"
                secondaryAction = onAnalyzeOnDevice
            } else {
                secondaryLabel = null
                secondaryAction = null
            }
        }
        localModelsReady -> {
            title = "Analyze On-Device with HushCast AI"
            body = if (isDownloaded) {
                "Run on-device HushCast AI on \"$episodeTitle\" to transcribe, auto-mute words, and detect sponsor reads."
            } else {
                "\"$episodeTitle\" isn't downloaded yet. Download it now to run on-device HushCast AI."
            }
            primaryLabel = if (isDownloaded) "Analyze On-Device" else "Download & Analyze"
            primaryAction = onAnalyzeOnDevice
            if (!isDownloaded) {
                secondaryLabel = "Audio Only"
                secondaryAction = onDownloadAudioOnly
            } else {
                secondaryLabel = null
                secondaryAction = null
            }
        }
        else -> {
            title = "HushCast AI Not Set Up"
            body = "No on-device AI models (~455 MB) are downloaded and no Desktop/Cloud Worker is connected. Download on-device models over Wi-Fi now, or pair a worker in Settings → HushCast."
            primaryLabel = "Set up (Wi-Fi)"
            primaryAction = {
                ModelDownloadWorker.enqueue(
                    context = context,
                    includeAdModels = true,
                    allowMetered = false
                )
            }
            if (!isDownloaded) {
                secondaryLabel = "Audio Only"
                secondaryAction = onDownloadAudioOnly
            } else {
                secondaryLabel = null
                secondaryAction = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    primaryAction()
                }
            ) {
                Text(primaryLabel)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (secondaryLabel != null && secondaryAction != null) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            secondaryAction()
                        }
                    ) {
                        Text(secondaryLabel)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
