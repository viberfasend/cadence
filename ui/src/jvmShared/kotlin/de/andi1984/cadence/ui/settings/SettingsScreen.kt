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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.data.sync.SyncFailure
import de.andi1984.cadence.data.sync.SyncStatus
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceChip
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
    onRemindersChange: (Boolean) -> Unit,
    onExport: (BackupTarget) -> Unit,
    onImport: (BackupTarget) -> Unit,
    onClearBackupOutcome: () -> Unit,
    onSignIn: (String, String) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
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

            SettingSection(stringResource(Res.string.settings_reminders))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Switch(
                    checked = settings.remindersEnabled,
                    onCheckedChange = onRemindersChange,
                )
                Text(
                    text = stringResource(Res.string.settings_reminders_supporting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }

            SettingSection(stringResource(Res.string.settings_sync))
            SyncSection(
                sync = state.sync,
                onSignIn = onSignIn,
                onSyncNow = onSyncNow,
                onSignOut = onSignOut,
            )

            SettingSection(stringResource(Res.string.settings_data))
            BackupControls(
                state = state,
                filePicker = filePicker,
                onExport = onExport,
                onImport = onImport,
                onClearBackupOutcome = onClearBackupOutcome,
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
 * Export and import go through the platform's file picker, so the user names the file and the app
 * needs no storage permission.
 *
 * Import used to replace both tables and therefore needed a warning; since ADR 0002 phase 1 it is
 * a merge, so the dialog now says what actually happens — nothing here is deleted, and the newer
 * version of a task wins. The dialog stays, because folding somebody else's file into your own
 * data is still not something to do by a stray tap.
 */
@Composable
private fun BackupControls(
    state: CadenceUiState,
    filePicker: BackupFilePicker,
    onExport: (BackupTarget) -> Unit,
    onImport: (BackupTarget) -> Unit,
    onClearBackupOutcome: () -> Unit,
) {
    var pendingImport by remember { mutableStateOf<BackupTarget?>(null) }
    val suggestedName = stringResource(Res.string.backup_file_name, LocalDate.now().toString())

    // The result line belongs to this visit to Settings, not to the app.
    DisposableEffect(Unit) { onDispose(onClearBackupOutcome) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = { filePicker.pickExportTarget(suggestedName, onExport) }) {
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
 * Sign in, sync now, sign out — the whole of sync's UI in phase 2, and deliberately manual.
 *
 * There is a form and no "create account" link because there is exactly one account and sign-ups
 * are disabled server-side (ADR 0002, decision 2): a registration form here could only ever
 * produce an error. Automatic triggers — on start, after an edit, on close — are phase 3; what
 * this screen proves is that the phone and the desktop reach the same data at all.
 */
@Composable
private fun SyncSection(
    sync: SyncUiState,
    onSignIn: (String, String) -> Unit,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(Res.string.settings_sync_supporting),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val status = sync.status) {
            is SyncStatus.SignedOut -> SignInForm(sync = sync, onSignIn = onSignIn)
            is SyncStatus.Syncing -> SignedIn(
                email = status.email,
                busy = true,
                line = stringResource(Res.string.settings_sync_running),
                onSyncNow = onSyncNow,
                onSignOut = onSignOut,
            )
            is SyncStatus.Idle -> SignedIn(
                email = status.email,
                busy = false,
                line = status.lastSyncedAt
                    ?.let { stringResource(Res.string.settings_sync_last_synced, relativeTime(it)) }
                    ?: stringResource(Res.string.settings_sync_never),
                onSyncNow = onSyncNow,
                onSignOut = onSignOut,
            )
            is SyncStatus.Failed -> SignedIn(
                email = status.email,
                busy = false,
                line = syncFailureText(status.reason),
                isError = true,
                onSyncNow = onSyncNow,
                onSignOut = onSignOut,
            )
        }
    }
}

@Composable
private fun SignInForm(sync: SyncUiState, onSignIn: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text(stringResource(Res.string.settings_sync_email)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(stringResource(Res.string.settings_sync_password)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = { onSignIn(email, password) },
        enabled = !sync.signingIn && email.isNotBlank() && password.isNotBlank(),
    ) {
        Text(
            text = if (sync.signingIn) {
                stringResource(Res.string.settings_sync_signing_in)
            } else {
                stringResource(Res.string.settings_sync_sign_in)
            },
        )
    }
    sync.error?.let { error ->
        Text(
            text = signInErrorText(error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SignedIn(
    email: String?,
    busy: Boolean,
    line: String,
    isError: Boolean = false,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
) {
    email?.let {
        Text(
            text = stringResource(Res.string.settings_sync_signed_in_as, it),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Text(
        text = line,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = onSyncNow, enabled = !busy) {
            Text(stringResource(Res.string.settings_sync_now))
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.padding(start = 4.dp))
        }
        TextButton(onClick = onSignOut, enabled = !busy) {
            Text(stringResource(Res.string.settings_sync_sign_out))
        }
    }
}

@Composable
private fun signInErrorText(error: SignInError): String = when (error) {
    SignInError.WRONG_CREDENTIALS -> stringResource(Res.string.settings_sync_error_credentials)
    SignInError.OFFLINE -> stringResource(Res.string.settings_sync_error_offline)
    SignInError.SERVER -> stringResource(Res.string.settings_sync_error_server)
}

@Composable
private fun syncFailureText(reason: SyncFailure): String = when (reason) {
    SyncFailure.OFFLINE -> stringResource(Res.string.settings_sync_failed_offline)
    SyncFailure.PROJECT_ASLEEP -> stringResource(Res.string.settings_sync_failed_asleep)
    SyncFailure.SESSION_EXPIRED -> stringResource(Res.string.settings_sync_failed_session)
    SyncFailure.SERVER -> stringResource(Res.string.settings_sync_failed_server)
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
