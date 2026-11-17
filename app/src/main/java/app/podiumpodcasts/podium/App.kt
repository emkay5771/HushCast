package app.podiumpodcasts.podium

import android.app.Application
import android.content.Context
import app.podiumpodcasts.podium.background.worker.NightlyWorker
import app.podiumpodcasts.podium.background.worker.PeriodicPodcastUpdateWorker
import app.podiumpodcasts.podium.background.worker.sync.PartialSynchronizationWorker
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.util.DebugLogger
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class App : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()

        val settingsRepository = SettingsRepository(this)

        MainScope().launch {
            app.podiumpodcasts.podium.background.HushCastEngineBridge.loadFromSettings(settingsRepository)
            PodcastGridOrganization.init(this@App)

            if(settingsRepository.sync.enable.first()) {
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