package app.podiumpodcasts.podium

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.podiumpodcasts.podium.background.PlaybackService
import app.podiumpodcasts.podium.manager.DatabaseManager
import app.podiumpodcasts.podium.ui.extractDeepLink
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel
import com.google.common.util.concurrent.Futures.immediateFuture
import com.google.common.util.concurrent.MoreExecutors

class AppActivity : ComponentActivity() {

    private val db by lazy {
        DatabaseManager.build(this)
    }

    private val settingsRepository = SettingsRepository(this)
    private val mediaPlayerViewModel: MediaPlayerViewModel by viewModels()

    var mediaController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleWorkerPairingIntent(intent)
        mediaPlayerViewModel.passDB(db)

        val deepLink = intent.extractDeepLink()

        enableEdgeToEdge()
        setContent {
            // create default lists if they don't exist
            LaunchedEffect(Unit) {
                db.lists().createFavorites()
                db.lists().createHearLater()
            }

            val launcher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { }

            // request notification permission
            LaunchedEffect(Unit) {
                if(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            CompositionLocalProvider(
                LocalDatabase provides db,
                LocalSettingsRepository provides settingsRepository
            ) {
                PodiumApp(
                    deepLink
                )
            }
        }
    }

    override fun onStart() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                mediaController = controllerFuture.get()
                mediaController?.let {
                    mediaPlayerViewModel.registerMediaController(it)
                    mediaPlayerViewModel.restoreQueueIfNeeded(applicationContext)
                }
            },
            MoreExecutors.directExecutor()
        )

        super.onStart()
    }

    override fun onStop() {
        mediaPlayerViewModel.unregisterMediaController()

        mediaController?.let { controller ->
            MediaController.releaseFuture(immediateFuture(controller))
            mediaController = null
        }

        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWorkerPairingIntent(intent)
    }

    private fun handleWorkerPairingIntent(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "hushcast" || data.host != "pair") return
        val url = data.getQueryParameter("url")?.trim()?.trimEnd('/') ?: return
        if (url.isBlank()) return
        val token = data.getQueryParameter("token")?.trim().orEmpty()
        app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerEnabled(this, true)
        app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerUrl(this, url)
        if (token.isNotBlank()) {
            app.hushcast.automute.engine.HushCastPrefs.setDesktopWorkerToken(this, token)
        }
        android.widget.Toast.makeText(
            this,
            "HushCast Worker paired: $url",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
