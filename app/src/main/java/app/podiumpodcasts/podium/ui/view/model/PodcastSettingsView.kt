package app.podiumpodcasts.podium.ui.view.model

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.AutoAddUpNextMode
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastModel
import app.podiumpodcasts.podium.ui.component.common.BackButton
import app.podiumpodcasts.podium.ui.component.settings.SettingsHeader
import app.podiumpodcasts.podium.ui.component.settings.SettingsListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSecondsSliderListItem
import app.podiumpodcasts.podium.ui.component.settings.SettingsSwitchListItem
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
            subscription.value?.let { subscription ->
                item {
                    SettingsHeader(
                        label = stringResource(R.string.view_podcast_settings_details)
                    )
                }

                item {
                    val overrideTitle = remember { mutableStateOf("") }
                    LaunchedEffect(podcast.title, podcast.overrideTitle) {
                        overrideTitle.value = podcast.overrideTitle.ifBlank { podcast.title }
                    }

                    val isSame = overrideTitle.value == podcast.title

                    LaunchedEffect(overrideTitle.value) {
                        delay(1500)
                        vm.setOverrideTitle(
                            db, podcast, when(isSame) {
                                true -> ""
                                false -> overrideTitle.value
                            }
                        )
                    }

                    SettingsListItem(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Rounded.Label,
                                stringResource(R.string.view_podcast_settings_details_override_title)
                            )
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
                                        enter = fadeIn() + expandIn(expandFrom = Alignment.CenterEnd),
                                        exit = fadeOut() + shrinkOut(shrinkTowards = Alignment.CenterEnd)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                overrideTitle.value = podcast.title
                                                vm.setOverrideTitle(db, podcast, "")
                                            }
                                        ) {
                                            Icon(Icons.Rounded.Restore, "")
                                        }
                                    }
                                }
                            }
                        },

                        index = 0,
                        count = 1,

                        onClick = { }
                    )
                }

                item {
                    Spacer(Modifier.height(32.dp))
                }

                item {
                    SettingsHeader(
                        label = stringResource(R.string.view_podcast_settings_subscription)
                    )
                }

                item {
                    SettingsSwitchListItem(
                        checked = subscription.enableAutoDownload,
                        onCheckedChange = {
                            vm.toggleSubscriptionAutoDownload(db, subscription, it)
                        },

                        icon = {
                            Icon(
                                Icons.Rounded.FileDownload,
                                stringResource(R.string.view_podcast_settings_subscription_enable_automatic_downloads)
                            )
                        },
                        label = stringResource(R.string.view_podcast_settings_subscription_enable_automatic_downloads),
                        description = stringResource(R.string.view_podcast_settings_subscription_enable_automatic_downloads_description),

                        index = 0,
                        count = 3
                    )
                }

                item {
                    SettingsSwitchListItem(
                        checked = subscription.enableNotifications,
                        onCheckedChange = {
                            vm.toggleSubscriptionNotifications(db, subscription, it)
                        },

                        icon = {
                            Icon(
                                Icons.Rounded.Notifications,
                                stringResource(R.string.view_podcast_settings_subscription_enable_notifications)
                            )
                        },
                        label = stringResource(R.string.view_podcast_settings_subscription_enable_notifications),
                        description = stringResource(R.string.view_podcast_settings_subscription_enable_notifications_description),

                        index = 1,
                        count = 3
                    )
                }

                item {
                    SettingsListItem(
                        icon = {
                            Icon(
                                Icons.AutoMirrored.Rounded.PlaylistAdd,
                                "Auto add to Up Next"
                            )
                        },
                        label = "Auto add to Up Next",
                        supportingContent = {
                            Column {
                                Text("Automatically queue new episodes when downloaded or refreshed")
                                Spacer(Modifier.height(12.dp))
                                val modes = listOf(
                                    AutoAddUpNextMode.OFF to "Off",
                                    AutoAddUpNextMode.PLAY_NEXT to "Play Next",
                                    AutoAddUpNextMode.PLAY_LAST to "Play Last"
                                )
                                SingleChoiceSegmentedButtonRow(
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    modes.forEachIndexed { idx, (mode, title) ->
                                        SegmentedButton(
                                            selected = autoAddMode == mode,
                                            onClick = {
                                                scope.launch {
                                                    settingsRepository.behavior.setPodcastAutoAddToUpNext(podcast.origin, mode)
                                                }
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(idx, modes.size)
                                        ) {
                                            Text(title, maxLines = 1)
                                        }
                                    }
                                }
                            }
                        },
                        index = 2,
                        count = 3,
                        onClick = { }
                    )
                }

                item {
                    Spacer(Modifier.height(32.dp))
                }
            }

            item {
                SettingsHeader(
                    label = stringResource(R.string.view_podcast_settings_playback)
                )
            }

            item {
                val seconds = remember { mutableIntStateOf(podcast.skipBeginning) }
                LaunchedEffect(podcast.skipBeginning) { seconds.value = podcast.skipBeginning }

                SettingsSecondsSliderListItem(
                    icon = {
                        Icon(
                            Icons.Rounded.SkipNext,
                            stringResource(R.string.view_podcast_settings_playback_skip_begin_of_show)
                        )
                    },
                    label = stringResource(R.string.view_podcast_settings_playback_skip_begin_of_show),

                    value = seconds.value,
                    onValueChange = { seconds.value = it },

                    max = 180,

                    onValueChangeFinished = {
                        vm.setSkipBeginning(db, podcast, seconds.value)
                    },

                    index = 0,
                    count = 3
                )
            }

            item {
                val seconds = remember { mutableIntStateOf(0) }
                LaunchedEffect(podcast.skipEnding) { seconds.value = podcast.skipEnding }

                SettingsSecondsSliderListItem(
                    icon = {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            stringResource(R.string.view_podcast_settings_playback_skp_end_of_show)
                        )
                    },
                    label = stringResource(R.string.view_podcast_settings_playback_skp_end_of_show),

                    value = seconds.value,
                    onValueChange = { seconds.value = it },

                    max = 180,

                    onValueChangeFinished = {
                        vm.setSkipEnding(db, podcast, seconds.value)
                    },

                    index = 1,
                    count = 3
                )
            }

            item {
                SettingsListItem(
                    icon = {
                        Icon(
                            Icons.Rounded.Speed,
                            "Playback speed"
                        )
                    },
                    label = "Playback speed",
                    supportingContent = {
                        Column {
                            Text("Override the global playback speed for this podcast")
                            Spacer(Modifier.height(12.dp))
                            val speeds = listOf(
                                0f to "Global",
                                0.8f to "0.8x",
                                1.0f to "1.0x",
                                1.2f to "1.2x",
                                1.5f to "1.5x",
                                2.0f to "2.0x"
                            )
                            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                            androidx.compose.foundation.layout.FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                speeds.forEach { (speedVal, label) ->
                                    androidx.compose.material3.FilterChip(
                                        selected = kotlin.math.abs(podcastSpeed - speedVal) < 0.01f,
                                        onClick = {
                                            scope.launch {
                                                settingsRepository.behavior.setPodcastPlaybackSpeed(podcast.origin, speedVal)
                                            }
                                        },
                                        label = {
                                            Text(label, maxLines = 1, softWrap = false)
                                        }
                                    )
                                }
                            }
                        }
                    },
                    index = 2,
                    count = 3,
                    onClick = { }
                )
            }

            item {
                Spacer(Modifier.height(32.dp))
            }

            item {
                SettingsHeader(label = "HushCast")
            }

            item {
                val podcastWordMuting by remember(podcast.origin) {
                    settingsRepository.hushCast.podcastWordMuting(podcast.origin)
                }.collectAsState(initial = null)

                SettingsListItem(
                    icon = {
                        Icon(
                            androidx.compose.material.icons.Icons.Rounded.Shield,
                            "Mute words"
                        )
                    },
                    label = "Mute words",
                    supportingContent = {
                        Column {
                            Text("Override global word muting for this podcast")
                            Spacer(Modifier.height(12.dp))
                            val options = listOf(
                                null to "Default",
                                true to "On",
                                false to "Off"
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                options.forEachIndexed { idx, (value, label) ->
                                    SegmentedButton(
                                        selected = podcastWordMuting == value,
                                        onClick = {
                                            scope.launch {
                                                settingsRepository.hushCast.setPodcastWordMuting(podcast.origin, value)
                                            }
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(idx, options.size)
                                    ) {
                                        Text(label, maxLines = 1)
                                    }
                                }
                            }
                        }
                    },
                    index = 0,
                    count = 2,
                    onClick = { }
                )
            }

            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                val autoMuteDb = remember { app.hushcast.automute.storage.AutoMuteDatabase.getInstance(context) }
                val policyEntity by remember(podcast.origin) {
                    autoMuteDb.dao().observePodcastAdPolicy(podcast.origin)
                }.collectAsState(initial = null)
                val globalPolicy by settingsRepository.hushCast.adPolicyMode.collectAsState(
                    initial = app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP
                )
                val overridePolicy = policyEntity?.policyMode?.let {
                    runCatching { app.hushcast.automute.dsp.AdPolicyMode.valueOf(it) }.getOrNull()
                }
                val globalLabel = when (globalPolicy) {
                    app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP -> "Auto-skip"
                    app.hushcast.automute.dsp.AdPolicyMode.MARK_ONLY -> "Mark only"
                    app.hushcast.automute.dsp.AdPolicyMode.OFF -> "Off"
                }

                SettingsListItem(
                    icon = {
                        Icon(
                            androidx.compose.material.icons.Icons.Rounded.FastForward,
                            "Sponsor skipping"
                        )
                    },
                    label = "Sponsor skipping",
                    supportingContent = {
                        Column {
                            Text("Choose how HushCast handles sponsor segments in this podcast")
                            Spacer(Modifier.height(12.dp))
                            val options = listOf<Pair<app.hushcast.automute.dsp.AdPolicyMode?, String>>(
                                null to "Default ($globalLabel)",
                                app.hushcast.automute.dsp.AdPolicyMode.AUTO_SKIP to "Auto-Skip",
                                app.hushcast.automute.dsp.AdPolicyMode.MARK_ONLY to "Mark Only",
                                app.hushcast.automute.dsp.AdPolicyMode.OFF to "Off"
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                options.forEachIndexed { idx, (mode, label) ->
                                    SegmentedButton(
                                        selected = overridePolicy == mode,
                                        onClick = {
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
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(idx, options.size)
                                    ) {
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    },
                    index = 1,
                    count = 2,
                    onClick = { }
                )
            }
        }
    }
}