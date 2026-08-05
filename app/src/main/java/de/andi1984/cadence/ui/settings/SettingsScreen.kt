package de.andi1984.cadence.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.R
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceChip
import de.andi1984.cadence.ui.format.backupOutcomeText
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: CadenceUiState,
    onBack: () -> Unit,
    onThemeChange: (ThemeChoice) -> Unit,
    onDensityChange: (Density) -> Unit,
    onShowCompletedChange: (Boolean) -> Unit,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onClearBackupOutcome: () -> Unit,
) {
    val settings = state.settings

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                text = stringResource(R.string.settings_title),
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
            SettingSection(stringResource(R.string.settings_theme))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CadenceChip(
                    label = stringResource(R.string.settings_theme_system),
                    selected = settings.theme == ThemeChoice.SYSTEM,
                    onClick = { onThemeChange(ThemeChoice.SYSTEM) },
                )
                CadenceChip(
                    label = stringResource(R.string.settings_theme_light),
                    selected = settings.theme == ThemeChoice.LIGHT,
                    onClick = { onThemeChange(ThemeChoice.LIGHT) },
                )
                CadenceChip(
                    label = stringResource(R.string.settings_theme_dark),
                    selected = settings.theme == ThemeChoice.DARK,
                    onClick = { onThemeChange(ThemeChoice.DARK) },
                )
            }

            SettingSection(stringResource(R.string.settings_density))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CadenceChip(
                    label = stringResource(R.string.settings_density_comfortable),
                    selected = settings.density == Density.COMFORTABLE,
                    onClick = { onDensityChange(Density.COMFORTABLE) },
                )
                CadenceChip(
                    label = stringResource(R.string.settings_density_compact),
                    selected = settings.density == Density.COMPACT,
                    onClick = { onDensityChange(Density.COMPACT) },
                )
            }
            Text(
                text = stringResource(R.string.settings_density_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingSection(stringResource(R.string.settings_lists))
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
                        text = stringResource(R.string.settings_show_completed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_show_completed_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingSection(stringResource(R.string.settings_data))
            BackupControls(
                state = state,
                onExport = onExport,
                onImport = onImport,
                onClearBackupOutcome = onClearBackupOutcome,
            )

            SettingSection(stringResource(R.string.settings_about))
            Text(
                text = stringResource(R.string.settings_about_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Export and import go through the Storage Access Framework, so the user names the file and the
 * app needs no storage permission. Import replaces everything, hence the confirmation dialog.
 */
@Composable
private fun BackupControls(
    state: CadenceUiState,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onClearBackupOutcome: () -> Unit,
) {
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val suggestedName = stringResource(R.string.backup_file_name, LocalDate.now().toString())

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE),
    ) { uri -> uri?.let(onExport) }
    // Anything the picker will show: file managers hand backups back as octet-stream often
    // enough that filtering on application/json would hide the user's own export.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { pendingImport = it } }

    // The result line belongs to this visit to Settings, not to the app.
    DisposableEffect(Unit) { onDispose(onClearBackupOutcome) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { exportLauncher.launch(suggestedName) }) {
            Icon(AppIcons.Download, contentDescription = null)
            Text(
                text = stringResource(R.string.settings_export),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            text = stringResource(R.string.settings_export_supporting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("*/*")) },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(AppIcons.Upload, contentDescription = null)
            Text(
                text = stringResource(R.string.settings_import),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            text = stringResource(R.string.settings_import_supporting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.backupOutcome?.let { outcome ->
            Text(
                text = backupOutcomeText(outcome),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(R.string.backup_import_confirm_title)) },
            text = { Text(stringResource(R.string.backup_import_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        onImport(uri)
                    },
                ) {
                    Text(stringResource(R.string.backup_import_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

private const val BACKUP_MIME_TYPE = "application/json"

@Composable
private fun SettingSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}
