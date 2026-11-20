package app.podiumpodcasts.podium

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.podiumpodcasts.podium.background.worker.NightlyWorker
import app.podiumpodcasts.podium.background.worker.PeriodicPodcastUpdateWorker
import app.podiumpodcasts.podium.background.worker.sync.PartialSynchronizationWorker
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import app.hushcast.automute.storage.migratePresetsToBuiltInFiltersOnce
import app.hushcast.automute.storage.migrateRulesToDefaultModeOnce

class App : Application(), SingletonImageLoader.Factory {
    private var foregroundSyncScheduled = false

    override fun onCreate() {
        super.onCreate()

        val settingsRepository = SettingsRepository(this)

        MainScope().launch {
            app.podiumpodcasts.podium.background.HushCastEngineBridge.loadFromSettings(settingsRepository)
            PodcastGridOrganization.init(this@App)
        }
        MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            // Custom sounds saved before 1.0.26 live only in the "selected sound" keys.  Copy them
            // into the sound list once, so picking a built-in tone can clear the selection safely.
            runCatching { settingsRepository.hushCast.migrateLegacyCustomSoundsOnce() }
        }
        MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            // Rules saved before 1.0.31 stored the global mode's name, so mode changes never reached
            // them.  Switch them to follow the global mode once.
            runCatching {
                val dao = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(this@App).dao()
                dao.migrateRulesToDefaultModeOnce(this@App)
            }
        }
        MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
            // 1.0.45: presets copied into the word list become the built-in filters (profanity
            // level, Explicit content), and the matcher catches more forms (words inside longer
            // words, compounds, masked words).  Match the stored transcripts again once; nothing
            // is transcribed again.
            runCatching {
                val dao = app.hushcast.automute.storage.AutoMuteDatabase.getInstance(this@App).dao()
                val movedPresets = dao.migratePresetsToBuiltInFiltersOnce(this@App)
                val matcherVersion = app.hushcast.automute.matcher.WordMatcher.MATCHER_VERSION
                if (movedPresets ||
                    app.hushcast.automute.engine.HushCastPrefs.getMatcherVersion(this@App) < matcherVersion
                ) {
                    app.hushcast.automute.worker.RuleReevaluationWorker.enqueue(
                        context = this@App,
                        defaultActionMode = settingsRepository.hushCast.muteActionMode.first()
                    )
                    app.hushcast.automute.engine.HushCastPrefs.setMatcherVersion(this@App, matcherVersion)
                }
            }
        }

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (foregroundSyncScheduled) return
                foregroundSyncScheduled = true
                MainScope().launch {
                    if (settingsRepository.sync.enable.first()) {
                        val syncInterval = settingsRepository.sync.intervalMinutes.first().toLong()
                        PartialSynchronizationWorker.schedule(
                            context = this@App,
                            repeatIntervalMinutes = syncInterval
                        )
                        PartialSynchronizationWorker.enqueue(
                            context = this@App
                        )
                    }

                    PeriodicPodcastUpdateWorker.enqueueWorker(
                        context = this@App,
                        settingsRepository = settingsRepository,
                        replace = false
                    )
                }
            }
        })

        NightlyWorker.scheduleNightlyWork(this)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(okio.Path.Companion.run {
                        java.io.File(context.noBackupFilesDir, "artwork_cache").apply { mkdirs() }.absolutePath.toPath()
                    })
                    .maxSizeBytes(150L * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .build()
    }
}