package app.podiumpodcasts.podium

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.navigation3.runtime.NavKey
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.podiumpodcasts.podium.background.PlaybackService
import app.podiumpodcasts.podium.manager.DatabaseManager
import app.podiumpodcasts.podium.ui.LocalStartupDestination
import app.podiumpodcasts.podium.ui.extractDeepLink
import app.podiumpodcasts.podium.ui.navigation.parseStartupDestination
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.URI

private data class PendingWorkerPairing(
    val url: String,
    val host: String,
    val token: String
)

class AppActivity : ComponentActivity() {

    private val db by lazy {
        DatabaseManager.build(this)
    }

    private val settingsRepository by lazy { SettingsRepository(this) }
    private val mediaPlayerViewModel: MediaPlayerViewModel by viewModels()

    var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private var pendingPairing by mutableStateOf<PendingWorkerPairing?>(null)
    private var pairingErrorMessage by mutableStateOf<String?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    /** S3: the startup destination, read off the main thread before the first frame. */
    private var startupDestination by mutableStateOf<NavKey?>(null)

    /** S3: true once the first frames with the real settings have been composed. */
    @Volatile
    private var uiReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // S3: keep the system splash screen up until settings are loaded, instead of blocking
        // composition with runBlocking (and briefly drawing the default theme and tab).
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !uiReady }

        lifecycleScope.launch {
            val raw = withTimeoutOrNull(STARTUP_SETTINGS_TIMEOUT_MS) {
                settingsRepository.appearance.startupDestination.first()
            }
            startupDestination = parseStartupDestination(raw)
        }
        lifecycleScope.launch {
            // Never keep the splash forever, whatever happens to the settings read.
            delay(SPLASH_MAX_MS)
            uiReady = true
        }

        if (savedInstanceState == null) {
            handleWorkerPairingIntent(intent)
        }

        val deepLink = intent.extractDeepLink()

        enableEdgeToEdge()
        setContent {
            // Create Favorites system list if it doesn't exist
            LaunchedEffect(Unit) {
                db.lists().createFavorites()
            }

            val startKey = startupDestination ?: return@setContent
            LaunchedEffect(Unit) {
                // Let the theme and grid settings (already cached by DataStore) reach the
                // composition before the splash goes away.
                withFrameNanos { }
                withFrameNanos { }
                uiReady = true
            }

            CompositionLocalProvider(
                LocalDatabase provides db,
                LocalSettingsRepository provides settingsRepository,
                LocalStartupDestination provides startKey
            ) {
                PodiumApp(deepLink)

                pendingPairing?.let { pairing ->
                    AlertDialog(
                        onDismissRequest = { pendingPairing = null },
                        title = { Text("Pair desktop worker?") },
                        text = {
                            Text(
                                "Allow HushCast to send episode titles and audio URLs to:\n\n${pairing.host}\n\n(${pairing.url})"
                            )
                        },
                        confirmButton = {
                            Button(
                                onClick = {
                                    app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerEnabled(this@AppActivity, true)
                                    app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerUrl(this@AppActivity, pairing.url)
                                    if (pairing.token.isNotBlank()) {
                                        app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerToken(this@AppActivity, pairing.token)
                                    }
                                    pendingPairing = null
                                }
                            ) {
                                Text("Pair")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { pendingPairing = null }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                pairingErrorMessage?.let { msg ->
                    AlertDialog(
                        onDismissRequest = { pairingErrorMessage = null },
                        title = { Text("Invalid worker link") },
                        text = { Text(msg) },
                        confirmButton = {
                            TextButton(onClick = { pairingErrorMessage = null }) {
                                Text("OK")
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken)
            .setListener(mediaPlayerViewModel.controllerListener)
            .buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }.getOrNull()?.let { controller ->
                    mediaController = controller
                    mediaPlayerViewModel.registerMediaController(controller)
                    mediaPlayerViewModel.restoreQueueIfNeeded(applicationContext)
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    override fun onStop() {
        mediaPlayerViewModel.unregisterMediaController()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
            controllerFuture = null
        }
        mediaController = null
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWorkerPairingIntent(intent)
    }

    private fun handleWorkerPairingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "hushcast" || data.host != "pair") return
        val rawUrl = data.getQueryParameter("url")?.trim()?.trimEnd('/') ?: return
        if (rawUrl.isBlank()) return
        val token = data.getQueryParameter("token")?.trim().orEmpty()

        // Clear data so configuration changes won't re-trigger pairing
        intent.data = null

        val parsed = runCatching { URI(rawUrl) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        val host = parsed?.host?.trim().orEmpty()
        if (parsed == null || host.isBlank() || (scheme != "https" && scheme != "http")) {
            pairingErrorMessage = "The pairing URL must use https:// or a private LAN http:// address."
            return
        }

        if (scheme == "http" && !isPrivateLanOrLocalHost(host)) {
            pairingErrorMessage = "Plain http:// is only allowed for local/private LAN hosts. Use https:// for \"$host\"."
            return
        }

        pendingPairing = PendingWorkerPairing(
            url = rawUrl,
            host = host,
            token = token
        )
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        val prefs = getSharedPreferences("hushcast_startup", MODE_PRIVATE)
        if (prefs.getBoolean("requested_post_notifications", false)) return
        prefs.edit().putBoolean("requested_post_notifications", true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun isPrivateLanOrLocalHost(host: String): Boolean {
        val lower = host.lowercase()
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".lan") || lower.endsWith(".internal")) {
            return true
        }
        return runCatching {
            val addr = InetAddress.getByName(host)
            addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress
        }.getOrDefault(false)
    }

    private companion object {
        const val STARTUP_SETTINGS_TIMEOUT_MS = 1_500L
        const val SPLASH_MAX_MS = 2_500L
    }
}
