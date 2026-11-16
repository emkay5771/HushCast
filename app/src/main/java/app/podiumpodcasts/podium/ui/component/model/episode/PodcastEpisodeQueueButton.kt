package app.podiumpodcasts.podium.ui.component.model.episode

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandIn
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkOut
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.podiumpodcasts.podium.R
import app.podiumpodcasts.podium.api.db.model.PodcastEpisodeBundle
import app.podiumpodcasts.podium.ui.vm.MediaPlayerViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PodcastEpisodeQueueButton(
    bundle: PodcastEpisodeBundle
) {
    val context = LocalContext.current
    val vm = viewModel<MediaPlayerViewModel>()

    val isCurrentlyPlaying = vm.metadataEpisodeId == bundle.episode.id
    val inQueue = vm.queue.contains(bundle.episode.id)
    var showMenu by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = vm.isPlayerVisible && !isCurrentlyPlaying,
        enter = fadeIn() + expandIn(expandFrom = Alignment.Center),
        exit = fadeOut() + shrinkOut(shrinkTowards = Alignment.Center)
    ) {
        Box {
            FilledTonalIconToggleButton(
                checked = inQueue,
                onCheckedChange = {
                    if(inQueue) {
                        vm.dequeue(bundle.episode.id)
                    } else {
                        showMenu = true
                    }
                }
            ) {
                AnimatedContent(
                    targetState = inQueue
                ) { isQueued ->
                    when(isQueued) {
                        true -> Icon(
                            imageVector = Icons.Rounded.PlaylistRemove,
                            contentDescription = stringResource(R.string.common_action_remove_from_queue)
                        )

                        else -> Icon(
                            imageVector = Icons.AutoMirrored.Rounded.PlaylistAdd,
                            contentDescription = stringResource(R.string.common_action_add_to_queue)
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Play Next") },
                    leadingIcon = {
                        Icon(Icons.Rounded.VerticalAlignTop, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        vm.playNext(context, bundle)
                    }
                )
                DropdownMenuItem(
                    text = { Text("Play Last") },
                    leadingIcon = {
                        Icon(Icons.Rounded.VerticalAlignBottom, contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        vm.enqueue(context, bundle)
                    }
                )
            }
        }
    }
}