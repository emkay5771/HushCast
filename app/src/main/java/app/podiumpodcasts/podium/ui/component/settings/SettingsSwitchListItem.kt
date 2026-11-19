package app.podiumpodcasts.podium.ui.component.settings

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * A settings row with a Switch.  The whole row toggles, and it's announced as a switch (the list
 * item would otherwise say "checkbox").
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsSwitchListItem(
    checked: Boolean,
    onCheckedChange: (newValue: Boolean) -> Unit,

    icon: (@Composable () -> Unit)? = null,
    label: String,
    description: String? = null,

    enabled: Boolean = true,

    index: Int = 0,
    count: Int = 1,

    modifier: Modifier = Modifier
) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    SettingsListItem(
        // Outermost semantics win, so this replaces the list item's own checkbox role.
        modifier = modifier.semantics { role = Role.Switch },

        icon = icon,
        label = label,
        description = description,

        checked = checked,
        enabled = enabled,

        trailingContent = {
            Switch(
                enabled = enabled,

                checked = checked,
                onCheckedChange = null
            )
        },

        index = index,
        count = count,
        onClick = {
            val next = !checked
            haptic.performHapticFeedback(
                if (next) androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOn
                else androidx.compose.ui.hapticfeedback.HapticFeedbackType.ToggleOff
            )
            onCheckedChange(next)
        }
    )
}
