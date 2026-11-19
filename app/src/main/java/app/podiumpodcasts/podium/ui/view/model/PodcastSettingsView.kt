package app.podiumpodcasts.podium.ui.view.model

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.hushcast.automute.dsp.AdPolicyMode
import app.podiumpodcasts.podium.AutoAddUpNextMode
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.ui.component.common.BackButton
import app.podiumpodcasts.podium.ui.component.settings.SegmentedButtonLabel
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupPosition
import app.podiumpodcasts.podium.ui.component.settings.SettingsGroupSurface
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSecondsSliderListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSwitchListItem
import app.podiumpodcasts.podium.ui.component.settings.formatSettingsPlaybackSpeed
import app.podiumpodcasts.podium.ui.component.settings.settingsGroup
import app.podiumpodcasts.podium.ui.helper.LocalDatabase
import app.podiumpodcasts.podium.ui.helper.LocalSettingsRepository
import app.podiumpodcasts.podium.ui.vm.PodcastSettingsViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastSettingsView(
    podcast: PodcastModel,
    onBack: () -> Unit
) {
    val db = LocalDatabase.current
    val settingsRepository = LocalSettingsRepository.current
    val scope = rememberCoroutineScope()

    val vm = viewModel<PodcastSettingsViewModel>()

    val subscription = remember { db.podcastSubscriptions().get(podcast.origin) }
        .collectAsState(null)

    val autoAddMode by remember(podcast.origin) {
        settingsRepository.behavior.podcastAutoAddToUpNext(podcast.origin)
    }.collectAsState(initial = AutoAddUpNextMode.OFF)

    val podcastSpeed by remember(podcast.origin) {
        settingsRepository.behavior.podcastPlaybackSpeed(podcast.origin)
    }.collectAsState(initial = 0f)

    // Group headers are read here: the lazy list's content isn't composable.
    val detailsHeader = stringResource(R.string.view_podcast_settings_details)
    val subscriptionHeader = stringResource(R.string.view_podcast_settings_subscription)
    val playbackHeader = stringResource(R.string.view_podcast_settings_playback)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            CenterAlignedTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton(
                        icon = Icons.Rounded.Close,
                        onClick = onBack
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.route_podcast_settings),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    ) { inset ->
        LazyColumn(
            modifier = Modifier
                .padding(inset)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
        ) {
            val subscribed = subscription.value
            if (subscribed != null) {
                settingsGroup(header = detailsHeader, key = "details", spaceAbove = 0.dp) {
                    row { pos ->
                        val overrideTitle = remember { mutableStateOf("") }
                        LaunchedEffect(podcast.title, podcast.overrideTitle) {
                            overrideTitle.value = podcast.overrideTitle.ifBlank { podcast.title }
                        }

                        val isSame = overrideTitle.value == podcast.title

                        LaunchedEffect(overrideTitle.value) {
                            delay(1500)
                            vm.setOverrideTitle(
                                db, podcast, when (isSame) {
                                    true -> ""
                                    false -> overrideTitle.value
                                }
                            )
                        }

                        SettingsListItem(
                            icon = {
                                Icon(Icons.AutoMirrored.Rounded.Label, contentDescription = null)
                            },
                            label = stringResource(R.string.view_podcast_settings_details_override_title),

                            supportingContent = {
                                Column {
                                    Text(stringResource(R.string.view_podcast_settings_details_override_title_description))

                                    Spacer(Modifier.height(16.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextField(
                                            modifier = Modifier.weight(1f, true),

                                            value = overrideTitle.value,
                                            onValueChange = {
                                                overrideTitle.value = it
                                            },

                                            singleLine = true
                                        )

                                        AnimatedVisibility(
                                            visible = !isSame,
                                            enter = app.podiumpodcasts.podium.ui.theme.AppMotion.expandFrom(Alignment.CenterEnd),
                                            exit = app.podiumpodcasts.podium.ui.theme.AppMotion.shrinkTowards(Alignment.CenterEnd)
                                        ) {
                                            IconButton(
                                                onClick = {
                                                    overrideTitle.value = podcast.title
                                                    vm.setOverrideTitle(db, podcast, "")
                                                }
                                            ) {
                                                Icon(Icons.Rounded.Restore, contentDescription = "Restore original title")
                                            }
                                        }
                                    }
                                }
                            },

                            index = pos.index,
                            count = pos.count,

                            onClick = { }
                        )
                    }
                }

                settingsGroup(header = subscriptionHeader, key = "subscription") {
                    row { pos ->
                        SettingsSwitchListItem(
                            checked = subscribed.enableAutoDownload,
                            onCheckedChange = {
                                vm.toggleSubscriptionAutoDownload(db, subscribed, it)
                            },

                            icon = {
                                Icon(Icons.Rounded.FileDownload, contentDescription = null)
                            },
                            label = stringResource(R.string.view_podcast_settings_subscription_enable_automatic_downloads),
                            description = stringResource(R.string.view_podcast_settings_subscription_enable_automatic_downloads_description),

                            index = pos.index,
                            count = pos.count
                        )
                    }

                    row { pos ->
                        SettingsSwitchListItem(
                            checked = subscribed.enableNotifications,
                            onCheckedChange = {
                                vm.toggleSubscriptionNotifications(db, subscribed, it)
                            },

                            icon = {
                                Icon(Icons.Rounded.Notifications, contentDescription = null)
                            },
                            label = stringResource(R.string.view_podcast_settings_subscription_enable_notifications),
                            description = stringResource(R.string.view_podcast_settings_subscription_enable_notifications_description),

                            index = pos.index,
                            count = pos.count
                        )
                    }

                    row { pos ->
                        SettingsControlRow(
                            position = pos,
                            icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            label = "Auto add to Up Next",
                            description = "Automatically queue new episodes when downloaded or refreshed"
                        ) {
                            val modes = listOf(
                                AutoAddUpNextMode.OFF to "Off",
                                AutoAddUpNextMode.PLAY_NEXT to "Play next",
                                AutoAddUpNextMode.PLAY_LAST to "Play last"
                            )
                            app.podiumpodcasts.podium.ui.component.common.ConnectedSingleChoiceGroup(
                                options = modes,
                                selected = modes.first { it.first == autoAddMode },
                                onSelect = { (mode, _) ->
                                    scope.launch {
                                        settingsRepository.behavior.setPodcastAutoAddToUpNext(podcast.origin, mode)
                                    }
                                }
                            ) { (_, title) ->
                                SegmentedButtonLabel(title, hasIcon = false)
                            }
                        }
                    }
                }
            }

            settingsGroup(
                header = playbackHeader,
                key = "playback",
                spaceAbove = if (subscribed != null) 16.dp else 0.dp
            ) {
                row { pos ->
                    val seconds = remember { mutableIntStateOf(podcast.skipBeginning) }
                    LaunchedEffect(podcast.skipBeginning) { seconds.intValue = podcast.skipBeginning }

                    SettingsSecondsSliderListItem(
                        icon = {
                            Icon(Icons.Rounded.SkipNext, contentDescription = null)
                        },
                        label = stringResource(R.string.view_podcast_settings_playback_skip_begin_of_show),

                        value = seconds.intValue,
                        onValueChange = { seconds.intValue = it },

                        max = 180,

                        onValueChangeFinished = {
                            vm.setSkipBeginning(db, podcast, seconds.intValue)
                        },

                        index = pos.index,
                        count = pos.count
                    )
                }

                row { pos ->
                    val seconds = remember { mutableIntStateOf(0) }
                    LaunchedEffect(podcast.skipEnding) { seconds.intValue = podcast.skipEnding }

                    SettingsSecondsSliderListItem(
                        icon = {
                            Icon(Icons.Rounded.SkipPrevious, contentDescription = null)
                        },
                        label = stringResource(R.string.view_podcast_settings_playback_skp_end_of_show),

                        value = seconds.intValue,
                        onValueChange = { seconds.intValue = it },

                        max = 180,

                        onValueChangeFinished = {
                            vm.setSkipEnding(db, podcast, seconds.intValue)
                        },

                        index = pos.index,
                        count = pos.count
                    )
                }

                row { pos ->
                    val globalSpeed by settingsRepository.behavior.playerPlaybackSpeed.collectAsState(initial = 1.0f)
                    SettingsControlRow(
                        position = pos,
                        icon = Icons.Rounded.Speed,
                        label = "Playback speed",
                        description = "Override the global playback speed for this podcast"
                    ) {
                        ChoiceChips(
                            options = listOf(
                                0f to "Global (${formatSettingsPlaybackSpeed(globalSpeed)})",
                                0.8f to "0.8x",
                                1.0f to "1.0x",
                                1.2f to "1.2x",
                                1.5f to "1.5x",
                                2.0f to "2.0x"
                            ),
                            isSelected = { kotlin.math.abs(podcastSpeed - it) < 0.01f },
                            onSelect = { speedVal ->
                                scope.launch {
                                    settingsRepository.behavior.setPodcastPlaybackSpeed(podcast.origin, speedVal)
                                }
                            }
                        )
                    }
                }
            }

            settingsGroup(header = "HushCast", key = "hushcast") {
                row { pos ->
                    val podcastWordMuting by remember(podcast.origin) {
                        settingsRepository.hushCast.podcastWordMuting(podcast.origin)
                    }.collectAsState(initial = null)
                    val globalWordMuting by settingsRepository.hushCast.wordMutingEnabled.collectAsState(initial = true)

                    SettingsControlRow(
                        position = pos,
                        icon = Icons.Rounded.Shield,
                        label = "Mute words",
                        description = "Override global word muting for this podcast"
                    ) {
                        ChoiceChips(
                            options = listOf<Pair<Boolean?, String>>(
                                null to "Default (${if (globalWordMuting) "On" else "Off"})",
                                true to "On",
                                false to "Off"
                            ),
                            isSelected = { podcastWordMuting == it },
                            onSelect = { value ->
                                scope.launch {
                                    settingsRepository.hushCast.setPodcastWordMuting(podcast.origin, value)
                                }
                            }
                        )
                    }
                }

                row { pos ->
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val autoMuteDb = remember { app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context) }
                    val policyEntity by remember(podcast.origin) {
                        autoMuteDb.dao().observePodcastAdPolicy(podcast.origin)
                    }.collectAsState(initial = null)
                    val globalPolicy by settingsRepository.hushCast.adPolicyMode.collectAsState(
                        initial = AdPolicyMode.AUTO_SKIP
                    )
                    val overridePolicy = policyEntity?.policyMode?.let {
                        runCatching { AdPolicyMode.valueOf(it) }.getOrNull()
                    }

                    SettingsControlRow(
                        position = pos,
                        icon = Icons.Rounded.FastForward,
                        label = "Sponsor skipping",
                        description = "Choose how HushCast handles sponsor segments in this podcast"
                    ) {
                        ChoiceChips(
                            options = listOf<Pair<AdPolicyMode?, String>>(
                                null to "Default (${adPolicyLabel(globalPolicy)})",
                                AdPolicyMode.AUTO_SKIP to adPolicyLabel(AdPolicyMode.AUTO_SKIP),
                                AdPolicyMode.MARK_ONLY to adPolicyLabel(AdPolicyMode.MARK_ONLY),
                                AdPolicyMode.OFF to adPolicyLabel(AdPolicyMode.OFF)
                            ),
                            isSelected = { overridePolicy == it },
                            onSelect = { mode ->
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    if (mode == null) {
                                        autoMuteDb.dao().deletePodcastAdPolicy(podcast.origin)
                                    } else {
                                        autoMuteDb.dao().upsertPodcastAdPolicy(
                                            app.hushcast.automute.storage.PodcastAdPolicyEntity(
                                                podcastOrigin = podcast.origin,
                                                policyMode = mode.name
                                            )
                                        )
                                    }
                                }
                            }
                        )
                    }
                }

                row { pos ->
                    val disableAutoProcess by remember(podcast.origin) {
                        settingsRepository.hushCast.podcastDisableAutoProcess(podcast.origin)
                    }.collectAsState(initial = false)

                    SettingsSwitchListItem(
                        checked = disableAutoProcess,
                        onCheckedChange = { disabled ->
                            scope.launch {
                                settingsRepository.hushCast.setPodcastDisableAutoProcess(podcast.origin, disabled)
                            }
                        },
                        icon = {
                            Icon(Icons.Rounded.FileDownload, contentDescription = null)
                        },
                        label = "Download direct (skip auto-processing)",
                        description = "Download episodes directly without queuing HushCast processing automatically",
                        index = pos.index,
                        count = pos.count
                    )
                }
            }
        }
    }
}

/** Same names as the HushCast settings' sponsor-skipping choices. */
private fun adPolicyLabel(mode: AdPolicyMode): String = when (mode) {
    AdPolicyMode.AUTO_SKIP -> "Auto-skip"
    AdPolicyMode.MARK_ONLY -> "Mark only"
    AdPolicyMode.OFF -> "Off"
}

/**
 * A grouped row with an icon, a title and a description above a full-width control.  The
 * controls get the row's whole width, so their labels (like "Default (Auto-skip)") aren't cut off
 * on narrow screens, as they were inside the list item's text column.
 */
@Composable
private fun SettingsControlRow(
    position: SettingsGroupPosition,
    icon: ImageVector,
    label: String,
    description: String,
    control: @Composable () -> Unit
) {
    SettingsGroupSurface(position = position) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .padding(8.dp)
                        .clearAndSetSemantics { }
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            control()
        }
    }
}

/** Single-choice chips that wrap onto more lines instead of clipping their labels. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                // One choice of several: announce it as a radio button, not a checkbox.
                modifier = Modifier.semantics { role = Role.RadioButton },
                selected = isSelected(value),
                onClick = { onSelect(value) },
                label = { Text(label, maxLines = 1) }
            )
        }
    }
}
