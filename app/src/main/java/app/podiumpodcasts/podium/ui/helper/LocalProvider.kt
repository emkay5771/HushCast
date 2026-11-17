package app.podiumpodcasts.podium.ui.helper

import androidx.compose.runtime.staticCompositionLocalOf
import app.podiumpodcasts.podium.SettingsRepository
import app.podiumpodcasts.podium.api.db.AppDatabase

val LocalDatabase = staticCompositionLocalOf<AppDatabase> { error("LocalDatabase not provided") }
val LocalSettingsRepository = staticCompositionLocalOf<SettingsRepository> { error("LocalSettingsRepository not provided") }