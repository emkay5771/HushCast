package app.podiumpodcasts.podium.ui.route.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.podiumpodcasts.podium.PodcastFolder
import app.podiumpodcasts.podium.PodcastGridOrganization
import app.podiumpodcasts.podium.SubscriptionsBadgeMode
import app.podiumpodcasts.podium.api.db.model.PodcastSubscriptionBundle
import app.podiumpodcasts.podium.ui.component.model.SubscriptionCard
import app.podiumpodcasts.podium.ui.dialog.ShimmerAsyncImage
import app.podiumpodcasts.podium.ui.theme.Typography

@Composable
fun CreateFolderDialog(
    allSubscriptions: List<PodcastSubscriptionBundle>,
    onDismiss: () -> Unit,
    onCreated: (PodcastFolder) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    val selectedOrigins = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Folder") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Select podcasts", style = Typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                ) {
                    items(allSubscriptions, key = { it.podcast.origin }) { bundle ->
                        val checked = bundle.podcast.origin in selectedOrigins
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (checked) selectedOrigins.remove(bundle.podcast.origin)
                                    else selectedOrigins.add(bundle.podcast.origin)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    if (it) selectedOrigins.add(bundle.podcast.origin)
                                    else selectedOrigins.remove(bundle.podcast.origin)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            ShimmerAsyncImage(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                model = bundle.podcast.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                targetSizePx = 96
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = bundle.podcast.fetchTitle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val created = PodcastGridOrganization.createFolder(
                        context = context,
                        name = name.ifBlank { "Folder" },
                        colorIndex = (System.currentTimeMillis() % 6).toInt(),
                        initialOrigins = selectedOrigins.toList()
                    )
                    onCreated(created)
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FolderBottomSheet(
    folder: PodcastFolder,
    folderSubscriptions: List<PodcastSubscriptionBundle>,
    allSubscriptions: List<PodcastSubscriptionBundle>,
    badgeMode: SubscriptionsBadgeMode,
    onDismiss: () -> Unit,
    onClickPodcast: (String) -> Unit
) {
    val context = LocalContext.current
    var showRenameDialog by remember { mutableStateOf(false) }
    var showAddPodcastsDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = folder.name,
                    style = Typography.titleLargeEmphasized,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = { showAddPodcastsDialog = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add podcasts")
                }
                IconButton(onClick = { showRenameDialog = true }) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Rename folder")
                }
                IconButton(onClick = {
                    PodcastGridOrganization.deleteFolder(context, folder.id)
                    onDismiss()
                }) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete folder")
                }
            }

            if (folderSubscriptions.isEmpty()) {
                Text(
                    text = "Folder is empty. Tap + to add podcasts.",
                    style = Typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(folderSubscriptions.size, key = { folderSubscriptions[it].podcast.origin }) { idx ->
                        val bundle = folderSubscriptions[idx]
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SubscriptionCard(
                                bundle = bundle,
                                badgeMode = badgeMode,
                                fillCell = true,
                                showTitles = true,
                                onClick = {
                                    onDismiss()
                                    onClickPodcast(bundle.podcast.origin)
                                }
                            )
                            TextButton(
                                onClick = {
                                    PodcastGridOrganization.removePodcastFromFolder(
                                        context,
                                        bundle.podcast.origin,
                                        folder.id
                                    )
                                }
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Remove", style = Typography.labelSmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(folder.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Folder") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PodcastGridOrganization.renameFolder(context, folder.id, newName)
                    showRenameDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddPodcastsDialog) {
        val currentOrigins = folder.origins.toSet()
        val available = allSubscriptions.filterNot { it.podcast.origin in currentOrigins }
        AlertDialog(
            onDismissRequest = { showAddPodcastsDialog = false },
            title = { Text("Add to ${folder.name}") },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp)
                ) {
                    items(available, key = { it.podcast.origin }) { bundle ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    PodcastGridOrganization.addPodcastToFolder(
                                        context,
                                        bundle.podcast.origin,
                                        folder.id
                                    )
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ShimmerAsyncImage(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                                model = bundle.podcast.imageUrl,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                targetSizePx = 96
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = bundle.podcast.fetchTitle(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddPodcastsDialog = false }) { Text("Done") }
            }
        )
    }
}

@Composable
fun MoveToFolderDialog(
    podcastOrigin: String,
    podcastTitle: String,
    folders: List<PodcastFolder>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val currentFolder = folders.firstOrNull { podcastOrigin in it.origins }
    var newFolderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Move \"$podcastTitle\"",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (currentFolder != null) {
                    TextButton(
                        onClick = {
                            PodcastGridOrganization.removePodcastFromFolder(
                                context,
                                podcastOrigin,
                                currentFolder.id
                            )
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Remove from \"${currentFolder.name}\"")
                    }
                }
                if (folders.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            val isHere = folder.id == currentFolder?.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !isHere) {
                                        PodcastGridOrganization.addPodcastToFolder(
                                            context,
                                            podcastOrigin,
                                            folder.id
                                        )
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = folder.name,
                                    style = Typography.bodyLarge,
                                    color = if (isHere) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (isHere) {
                                    Text(
                                        text = "Current",
                                        style = Typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newFolderName,
                        onValueChange = { newFolderName = it },
                        label = { Text("New folder") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        enabled = newFolderName.isNotBlank(),
                        onClick = {
                            PodcastGridOrganization.createFolder(
                                context = context,
                                name = newFolderName.trim(),
                                colorIndex = (System.currentTimeMillis() % 6).toInt(),
                                initialOrigins = listOf(podcastOrigin)
                            )
                            onDismiss()
                        }
                    ) {
                        Text("Create")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

