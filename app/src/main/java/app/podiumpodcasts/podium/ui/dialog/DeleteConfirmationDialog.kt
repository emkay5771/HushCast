package app.podiumpodcasts.podium.ui.dialog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * D5: an action-specific confirmation ("Delete download?" with Delete / Cancel), never
 * "Are you sure? / Yes / Abort".  Callers name the action in [title] and, when it isn't a plain
 * delete, in [confirmLabel].
 */
@Composable
fun DeleteConfirmationDialog(
    onDismiss: () -> Unit,
    itemName: String,
    additionalText: String = "",
    title: String = "Delete?",
    confirmLabel: String = "Delete",
    dismissLabel: String = "Cancel",
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            onDismiss()
        },
        icon = {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null
            )
        },
        title = {
            Text(title)
        },
        text = {
            Text(text = deleteConfirmationBody(itemName, additionalText))
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismiss()
                },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Text(dismissLabel)
            }
        },
        containerColor = MaterialTheme.colorScheme.errorContainer,
        iconContentColor = MaterialTheme.colorScheme.error,
        titleContentColor = MaterialTheme.colorScheme.onErrorContainer,
        textContentColor = MaterialTheme.colorScheme.onErrorContainer,
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(confirmLabel)
            }
        }
    )
}

/** "“Name” will be deleted. <extra>", or just the extra text when there's no name. */
fun deleteConfirmationBody(itemName: String, additionalText: String): String {
    val name = itemName.trim()
    val extra = additionalText.trim()
    val lead = if (name.isNotEmpty()) "“$name” will be deleted." else ""
    return listOf(lead, extra).filter { it.isNotEmpty() }.joinToString(" ")
}
