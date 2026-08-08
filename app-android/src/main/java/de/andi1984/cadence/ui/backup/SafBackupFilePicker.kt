package de.andi1984.cadence.ui.backup

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import de.andi1984.cadence.ui.platform.BackupFilePicker
import de.andi1984.cadence.ui.platform.BackupTarget

/**
 * Android's answer to [BackupFilePicker]: the Storage Access Framework, so the user names the
 * file and the app needs no storage permission.
 *
 * The launchers have to be created in a composition, and their results arrive long after the
 * call that asked for one — hence the parked callback rather than a return value. A cancelled
 * picker drops the callback without calling it, which is what "no file" means to Settings.
 */
@Composable
fun rememberSafBackupFilePicker(): BackupFilePicker {
    var onExportPicked by remember { mutableStateOf<((BackupTarget) -> Unit)?>(null) }
    var onImportPicked by remember { mutableStateOf<((BackupTarget) -> Unit)?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        PersistableCreateDocument(BACKUP_MIME_TYPE),
    ) { uri ->
        val callback = onExportPicked
        onExportPicked = null
        if (uri != null) callback?.invoke(BackupTarget(uri.toString()))
    }

    // Anything the picker will show: file managers hand backups back as octet-stream often
    // enough that filtering on application/json would hide the user's own export.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = onImportPicked
        onImportPicked = null
        if (uri != null) callback?.invoke(BackupTarget(uri.toString()))
    }

    return remember(exportLauncher, importLauncher) {
        object : BackupFilePicker {
            override fun pickExportTarget(suggestedName: String, onPicked: (BackupTarget) -> Unit) {
                onExportPicked = onPicked
                exportLauncher.launch(suggestedName)
            }

            override fun pickImportSource(onPicked: (BackupTarget) -> Unit) {
                onImportPicked = onPicked
                importLauncher.launch(arrayOf("*/*"))
            }
        }
    }
}

/**
 * The grant a picker hands out lasts only as long as the process. Sync has to survive a restart,
 * so the intent asks for a permission that can be persisted; `BackupIo` would otherwise lose
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
