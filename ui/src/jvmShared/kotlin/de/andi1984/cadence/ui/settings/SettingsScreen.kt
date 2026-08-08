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
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.backup.BackupFailure
import de.andi1984.cadence.domain.backup.BackupOutcome
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceChip
import de.andi1984.cadence.ui.format.backupFailureText
import de.andi1984.cadence.ui.format.backupOutcomeText
import de.andi1984.cadence.ui.format.formatFileSize
import de.andi1984.cadence.ui.format.relativeTime
import de.andi1984.cadence.ui.platform.AppInfo
import de.andi1984.cadence.ui.platform.BackupFilePicker
import de.andi1984.cadence.ui.platform.BackupTarget
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.net.URLDecoder
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    state: CadenceUiState,
    appInfo: AppInfo,
    filePicker: BackupFilePicker,
    onBack: () -> Unit,
    onThemeChange: (ThemeChoice) -> Unit,
    onDensityChange: (Density) -> Unit,
    onShowCompletedChange: (Boolean) -> Unit,
    onExport: (BackupTarget) -> Unit,
    onImport: (BackupTarget) -> Unit,
    onClearBackupOutcome: () -> Unit,
    onEnableAutoBackup: (BackupTarget) -> Unit,
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
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back))
            }
            Text(
                text = stringResource(Res.string.settings_title),
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
            SettingSection(stringResource(Res.string.settings_theme))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CadenceChip(
                    label = stringResource(Res.string.settings_theme_system),
                    selected = settings.theme == ThemeChoice.SYSTEM,
                    onClick = { onThemeChange(ThemeChoice.SYSTEM) },
                )
                CadenceChip(
                    label = stringResource(Res.string.settings_theme_light),
                    selected = settings.theme == ThemeChoice.LIGHT,
                    onClick = { onThemeChange(ThemeChoice.LIGHT) },
                )
                CadenceChip(
                    label = stringResource(Res.string.settings_theme_dark),
                    selected = settings.theme == ThemeChoice.DARK,
                    onClick = { onThemeChange(ThemeChoice.DARK) },
                )
            }

            SettingSection(stringResource(Res.string.settings_density))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CadenceChip(
                    label = stringResource(Res.string.settings_density_comfortable),
                    selected = settings.density == Density.COMFORTABLE,
                    onClick = { onDensityChange(Density.COMFORTABLE) },
                )
                CadenceChip(
                    label = stringResource(Res.string.settings_density_compact),
                    selected = settings.density == Density.COMPACT,
                    onClick = { onDensityChange(Density.COMPACT) },
                )
            }
            Text(
                text = stringResource(Res.string.settings_density_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SettingSection(stringResource(Res.string.settings_lists))
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
                        text = stringResource(Res.string.settings_show_completed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.settings_show_completed_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingSection(stringResource(Res.string.settings_data))
            BackupControls(
                state = state,
                filePicker = filePicker,
                onExport = onExport,
                onImport = onImport,
                onClearBackupOutcome = onClearBackupOutcome,
                onEnableAutoBackup = onEnableAutoBackup,
                onDisableAutoBackup = onDisableAutoBackup,
                onDeclineAutoBackup = onDeclineAutoBackup,
            )

            SettingSection(stringResource(Res.string.settings_about))
            Text(
                text = stringResource(Res.string.settings_about_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AppMetadata(state = state, appInfo = appInfo)
        }
    }
}

/** Version, record counts and database size — diagnostic, not domain state, and none of it
 *  something `:core` knows, so the shell measures it and this only words it. */
@Composable
private fun AppMetadata(state: CadenceUiState, appInfo: AppInfo) {
    val databaseSize = formatFileSize(appInfo.databaseSizeBytes)

    Column(
        modifier = Modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_metadata_version, appInfo.version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                Res.string.settings_metadata_counts,
                pluralStringResource(Res.plurals.task_count, state.tasks.size, state.tasks.size),
                pluralStringResource(Res.plurals.project_count, state.projects.size, state.projects.size),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.settings_metadata_database_size, databaseSize),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Export and import go through the Storage Access Framework, so the user names the file and the
 * app needs no storage permission. Import replaces everything, hence the confirmation dialog.
 *
 * The first successful export *or* import is also a moment the app offers to keep that file up
 * to date by itself: until there is a file, the offer would have nothing to point at, and a file
 * just imported is exactly the file a user picking up automatic sync already trusts. Whatever
 * the answer, it is asked once — from then on [AutoBackupSection] is the way in and out, and its
 * own "Change file" action (not the switch) is what re-runs this same picker later, so a file
 * that stops working is never a dead end.
 */
@Composable
private fun BackupControls(
    state: CadenceUiState,
    filePicker: BackupFilePicker,
    onExport: (BackupTarget) -> Unit,
    onImport: (BackupTarget) -> Unit,
    onClearBackupOutcome: () -> Unit,
    onEnableAutoBackup: (BackupTarget) -> Unit,
    onDisableAutoBackup: () -> Unit,
    onDeclineAutoBackup: () -> Unit,
) {
    val autoBackup = state.settings.autoBackup
    var pendingImport by remember { mutableStateOf<BackupTarget?>(null) }
    var offerFor by remember { mutableStateOf<BackupTarget?>(null) }
    // The file the offer would be about, held back until the export/import actually went
    // through — offering to keep a file in sync that was never written or read would be a lie.
    var offerCandidate by remember { mutableStateOf<BackupTarget?>(null) }
    val suggestedName = stringResource(Res.string.backup_file_name, LocalDate.now().toString())

    // [enableSync] tells the two reasons for opening the same picker apart: choosing or changing
    // the sync file first means the export that follows is recorded as a sync, so the next launch
    // knows this file as one of its own and leaves the data alone.
    fun pickExportTarget(enableSync: Boolean) {
        filePicker.pickExportTarget(suggestedName) { target ->
            if (enableSync) {
                onEnableAutoBackup(target)
            } else if (!autoBackup.offered) {
                offerCandidate = target
            }
            onExport(target)
        }
    }

    LaunchedEffect(state.backupOutcome, offerCandidate) {
        val candidate = offerCandidate ?: return@LaunchedEffect
        when (state.backupOutcome) {
            is BackupOutcome.Exported, is BackupOutcome.Imported -> {
                offerFor = candidate
                offerCandidate = null
            }

            is BackupOutcome.Failed -> offerCandidate = null
            else -> Unit
        }
    }

    // The result line belongs to this visit to Settings, not to the app.
    DisposableEffect(Unit) { onDispose(onClearBackupOutcome) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { pickExportTarget(enableSync = false) }) {
            Icon(AppIcons.Download, contentDescription = null)
            Text(
                text = stringResource(Res.string.settings_export),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            text = stringResource(Res.string.settings_export_supporting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedButton(
            onClick = { filePicker.pickImportSource { pendingImport = it } },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(AppIcons.Upload, contentDescription = null)
            Text(
                text = stringResource(Res.string.settings_import),
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            text = stringResource(Res.string.settings_import_supporting),
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

        AutoBackupSection(
            settings = autoBackup,
            failure = state.autoBackupFailure,
            onToggle = { checked ->
                if (checked) {
                    // A file is already named: ticking the box again resumes it rather than
                    // sending the user back through the picker.
                    onEnableAutoBackup(BackupTarget(autoBackup.fileUri.orEmpty()))
                } else {
                    onDisableAutoBackup()
                }
            },
            onChooseFile = { pickExportTarget(enableSync = true) },
        )
    }

    offerFor?.let { target ->
        AutoBackupOfferDialog(
            onEnable = {
                offerFor = null
                onEnableAutoBackup(target)
            },
            onDecline = {
                offerFor = null
                onDeclineAutoBackup()
            },
        )
    }

    pendingImport?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text(stringResource(Res.string.backup_import_confirm_title)) },
            text = { Text(stringResource(Res.string.backup_import_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingImport = null
                        if (!autoBackup.offered) offerCandidate = target
                        onImport(target)
                    },
                ) {
                    Text(stringResource(Res.string.backup_import_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

/**
 * Automatic sync has exactly two controls, kept visibly distinct so neither is mistaken for the
 * other: a button when there is no file yet, because picking one opens a whole system dialog and
 * a switch should never do that invisibly, and a switch once there is one, because from then on
 * the action really is just pause/resume. "Change file" stays reachable from both the normal and
 * the failed state — nothing here used to let a broken or unwanted file be swapped out short of
 * losing sync entirely, which was the sharp edge users actually hit.
 */
@Composable
private fun AutoBackupSection(
    settings: AutoBackupSettings,
    failure: BackupFailure?,
    onToggle: (Boolean) -> Unit,
    onChooseFile: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_auto_backup),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(Res.string.settings_auto_backup_supporting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (settings.fileUri == null) {
            OutlinedButton(onClick = onChooseFile, modifier = Modifier.padding(top = 6.dp)) {
                Icon(AppIcons.Upload, contentDescription = null)
                Text(
                    text = stringResource(Res.string.settings_auto_backup_choose),
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Switch(checked = settings.enabled, onCheckedChange = onToggle)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.settings_auto_backup_file, backupFileName(settings.fileUri)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    settings.lastSyncedAt?.let { at ->
                        Text(
                            text = stringResource(
                                Res.string.settings_auto_backup_last_synced,
                                relativeTime(at),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = onChooseFile) {
                Text(stringResource(Res.string.settings_auto_backup_change))
            }
        }

        // Sync is invisible while it works, so it has to speak up when it stops — a file that
        // was moved or deleted, most likely. "Change file" is what actually gets someone unstuck.
        failure?.let { reason ->
            Text(
                text = stringResource(Res.string.settings_auto_backup_failed, backupFailureText(reason)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onChooseFile) {
                Text(stringResource(Res.string.settings_auto_backup_change))
            }
        }
    }
}

/** Asked once, right after the first export produced a file worth keeping up to date. */
@Composable
private fun AutoBackupOfferDialog(onEnable: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDecline,
        icon = { Icon(AppIcons.Download, contentDescription = null) },
        title = { Text(stringResource(Res.string.auto_backup_offer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(Res.string.auto_backup_offer_text))
                Text(
                    text = stringResource(Res.string.auto_backup_offer_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onEnable) {
                Text(stringResource(Res.string.auto_backup_offer_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onDecline) {
                Text(stringResource(Res.string.auto_backup_offer_decline))
            }
        },
    )
}

/**
 * The document's own name, which is all of a content uri a person recognises.
 *
 * A SAF uri ends in one percent-encoded segment that itself holds a path
 * (`…/document/primary%3ADownload%2Fcadence.json`), so the last `/` is looked for twice: once in
 * the uri, once inside the decoded segment. A plain desktop path falls out of the first step.
 * `+` is escaped before decoding because it means a literal plus in a path and a space in a query.
 */
private fun backupFileName(uri: String): String = uri
    .substringBefore('?')
    .substringAfterLast('/')
    .let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrDefault(it) }
    .substringAfterLast('/')
    .ifBlank { uri }

@Composable
private fun SettingSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}
