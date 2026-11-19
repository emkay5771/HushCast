package app.podiumpodcasts.podium.ui.component.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Section header above a settings group.  The label is announced as a heading, so TalkBack users
 * can jump between sections.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsHeader(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null
) {
    Column(
        modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleSmall
        )
        if (description != null) {
            Text(
                text = description,
                modifier = Modifier.padding(top = 2.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
