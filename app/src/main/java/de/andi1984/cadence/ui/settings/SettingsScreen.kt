package de.andi1984.cadence.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceChip

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: CadenceUiState,
    onBack: () -> Unit,
    onThemeChange: (ThemeChoice) -> Unit,
    onDensityChange: (Density) -> Unit,
    onShowCompletedChange: (Boolean) -> Unit,
) {
    val settings = state.settings

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = "Settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingSection("Theme")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CadenceChip(
                    label = "Follow system",
                    selected = settings.theme == ThemeChoice.SYSTEM,
                    onClick = { onThemeChange(ThemeChoice.SYSTEM) },
                )
                CadenceChip(
                    label = "Light",
                    selected = settings.theme == ThemeChoice.LIGHT,
                    onClick = { onThemeChange(ThemeChoice.LIGHT) },
                )
                CadenceChip(
                    label = "Dark",
                    selected = settings.theme == ThemeChoice.DARK,
                    onClick = { onThemeChange(ThemeChoice.DARK) },
                )
            }

            SettingSection("Row density")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CadenceChip(
                    label = "Comfortable",
                    selected = settings.density == Density.COMFORTABLE,
                    onClick = { onDensityChange(Density.COMFORTABLE) },
                )
                CadenceChip(
                    label = "Compact",
                    selected = settings.density == Density.COMPACT,
                    onClick = { onDensityChange(Density.COMPACT) },
                )
            }
            Text(
                text = "Comfortable shows 64dp rows with the full meta line. Compact drops to " +
                    "52dp single-line rows. Touch targets stay at 48dp either way.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingSection("Lists")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Switch(
                    checked = settings.showCompleted,
                    onCheckedChange = onShowCompletedChange,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Show completed tasks",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Finished work stays in place for the rest of the day",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingSection("About")
            Text(
                text = "Cadence orders work by importance first and uses the due date only to " +
                    "break ties. Priority is always shown as a segmented spine plus its label, " +
                    "never by colour alone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}
