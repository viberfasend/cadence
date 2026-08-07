package de.andi1984.cadence.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.BuildConfig
import de.andi1984.cadence.R
import de.andi1984.cadence.data.backup.BackupFailure
import de.andi1984.cadence.data.backup.BackupOutcome
import de.andi1984.cadence.data.db.CadenceDatabase
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceChip
import de.andi1984.cadence.ui.format.backupFailureText
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
    onEnableAutoBackup: (Uri) -> Unit,
    onDisableAutoBackup: () -> Unit,
    onDeclineAutoBackup: () -> Unit,
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
                onEnableAutoBackup = onEnableAutoBackup,
                onDisableAutoBackup = onDisableAutoBackup,
                onDeclineAutoBackup = onDeclineAutoBackup,
            )

            SettingSection(stringResource(R.string.settings_about))
            Text(
                text = stringResource(R.string.settings_about_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppMetadata(state = state)
        }
    }
}

/** Version, record counts and database size — diagnostic, not domain state, so it is read here. */
@Composable
private fun AppMetadata(state: CadenceUiState) {
    val context = LocalContext.current
    val databaseSize = remember(state.tasks.size, state.projects.size) {
        Formatter.formatShortFileSize(context, context.getDatabasePath(CadenceDatabase.DATABASE_NAME).length())
    }

    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_metadata_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.settings_metadata_counts,
                pluralStringResource(R.plurals.task_count, state.tasks.size, state.tasks.size),
                pluralStringResource(R.plurals.project_count, state.projects.size, state.projects.size),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.settings_metadata_database_size, databaseSize),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Export and import go through the Storage Access Framework, so the user names the file and the
 * app needs no storage permission. Import replaces everything, hence the confirmation dialog.
 *
 * The first successful export is also the only moment the app offers to keep that file up to
 * date by itself: until there is a file, the offer would have nothing to point at. Whatever the
 * answer, it is asked once — from then on the switch below is the way in and out.
 */
@Composable
private fun BackupControls(
    state: CadenceUiState,
    onExport: (Uri) -> Unit,
    onImport: (Uri) -> Unit,
    onClearBackupOutcome: () -> Unit,
    onEnableAutoBackup: (Uri) -> Unit,
    onDisableAutoBackup: () -> Unit,
    onDeclineAutoBackup: () -> Unit,
) {
    val autoBackup = state.settings.autoBackup
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var offerFor by remember { mutableStateOf<Uri?>(null) }
    // The file the offer would be about, held back until the export has actually written it —
    // offering to keep a file in sync that could not be written would be a lie.
    var offerCandidate by remember { mutableStateOf<Uri?>(null) }
    // Set when the picker was opened by the switch rather than by the export button: the file
    // has to exist and hold the current data before sync starts writing to it.
    var enableAfterExport by remember { mutableStateOf(false) }
    val suggestedName = stringResource(R.string.backup_file_name, LocalDate.now().toString())

    val exportLauncher = rememberLauncherForActivityResult(
        PersistableCreateDocument(BACKUP_MIME_TYPE),
    ) { uri ->
        if (uri == null) {
            enableAfterExport = false
            return@rememberLauncherForActivityResult
        }
        // Switching sync on first means the export that follows is recorded as a sync, so the
        // next launch knows this file as one of its own and leaves the data alone.
        if (enableAfterExport) {
            enableAfterExport = false
            onEnableAutoBackup(uri)
        } else if (!autoBackup.offered) {
            offerCandidate = uri
        }
        onExport(uri)
    }

    LaunchedEffect(state.backupOutcome, offerCandidate) {
        val candidate = offerCandidate ?: return@LaunchedEffect
        when (state.backupOutcome) {
            is BackupOutcome.Exported -> {
                offerFor = candidate
                offerCandidate = null
            }

            is BackupOutcome.Failed -> offerCandidate = null
            else -> Unit
        }
    }
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

        AutoBackupToggle(
            settings = autoBackup,
            failure = state.autoBackupFailure,
            onCheckedChange = { checked ->
                when {
                    !checked -> onDisableAutoBackup()
                    // A file is already named: ticking the box again resumes it rather than
                    // sending the user back through the picker.
                    autoBackup.fileUri != null -> onEnableAutoBackup(Uri.parse(autoBackup.fileUri))
                    else -> {
                        enableAfterExport = true
                        exportLauncher.launch(suggestedName)
                    }
                }
            },
        )
    }

    offerFor?.let { uri ->
        AutoBackupOfferDialog(
            onEnable = {
                offerFor = null
                onEnableAutoBackup(uri)
            },
            onDecline = {
                offerFor = null
                onDeclineAutoBackup()
            },
        )
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

/**
 * The switch that keeps automatic sync reversible: the feature can be given up and taken back
 * at any time, and turning it off leaves manual export and import exactly as they were.
 */
@Composable
private fun AutoBackupToggle(
    settings: AutoBackupSettings,
    failure: BackupFailure?,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Switch(checked = settings.enabled, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_auto_backup),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.settings_auto_backup_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (settings.enabled) {
        settings.fileUri?.let { uri ->
            Text(
                text = stringResource(R.string.settings_auto_backup_file, backupFileName(uri)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        settings.lastSyncedAt?.let { at ->
            Text(
                text = stringResource(
                    R.string.settings_auto_backup_last_synced,
                    DateUtils.getRelativeTimeSpanString(at.toEpochMilli()).toString(),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Sync is invisible while it works, so it has to speak up when it stops — a file that was
    // moved or deleted, or a switch that would not stay on because the file can no longer be
    // reached. The message outlives the switch going back off, which is when it matters most.
    failure?.let { reason ->
        Text(
            text = stringResource(R.string.settings_auto_backup_failed, backupFailureText(reason)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** Asked once, right after the first export produced a file worth keeping up to date. */
@Composable
private fun AutoBackupOfferDialog(onEnable: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(AppIcons.Download, contentDescription = null) },
        title = { Text(stringResource(R.string.auto_backup_offer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.auto_backup_offer_text))
                Text(
                    text = stringResource(R.string.auto_backup_offer_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text(stringResource(R.string.auto_backup_offer_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(R.string.auto_backup_offer_decline))
            }
        },
    )
}

/** The document's own name, which is all of a content uri a person recognises. */
private fun backupFileName(uri: String): String =
    Uri.parse(uri).lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { uri }

/**
 * The grant a picker hands out lasts only as long as the process. Sync has to survive a restart,
 * so the intent asks for a permission that can be persisted; [BackupIo] would otherwise lose
 * access to the file the moment the app is killed.
 */
private class PersistableCreateDocument(mimeType: String) :
    ActivityResultContracts.CreateDocument(mimeType) {

    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
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
