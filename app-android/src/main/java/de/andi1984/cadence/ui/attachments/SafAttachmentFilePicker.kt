package de.andi1984.cadence.ui.attachments

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import de.andi1984.cadence.ui.platform.AttachmentFilePicker
import de.andi1984.cadence.ui.platform.PickedFile

/**
 * Android's answer to [AttachmentFilePicker]: `OpenDocument`, the same Storage Access Framework
 * the backup picker uses and for the same reason — the user names the file, so the app needs no
 * storage permission at all.
 *
 * `OpenDocument` rather than `GetContent`: only the former yields a uri the app may read after
 * the picker's own activity is gone, which is the difference between reading the bytes in a
 * coroutine and racing a dialog's teardown. The grant still dies with the *process*, which is
 * why [PickedFile.open] is called once, straight away, rather than parked on a row.
 *
 * The parked-callback shape is the launcher's: it can only be created in a composition, and its
 * result arrives long after the call that asked for one. A cancelled picker drops the callback
 * without invoking it.
 */
@Composable
fun rememberSafAttachmentFilePicker(): AttachmentFilePicker {
    val context = LocalContext.current
    var onPickedFile by remember { mutableStateOf<((PickedFile) -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val callback = onPickedFile
        onPickedFile = null
        if (uri != null) callback?.invoke(context.applicationContext.pickedFile(uri))
    }

    return remember(launcher, context) {
        object : AttachmentFilePicker {
            override fun pickFile(onPicked: (PickedFile) -> Unit) {
                onPickedFile = onPicked
                // Everything: an attachment is whatever the user considers one, and a filter is
                // how a perfectly good file ends up greyed out in the picker.
                launcher.launch(arrayOf("*/*"))
            }
        }
    }
}

/**
 * What the row is drawn from, resolved while the grant is fresh.
 *
 * The name and type are read here rather than inside [PickedFile.open] because they are cheap and
 * a row with no name is unusable, while the bytes are deliberately left unread until the
 * repository is ready to stream them. The `applicationContext` is what holds the resolver: an
 * Activity destroyed by a rotation between the pick and the read would otherwise take it along.
 */
private fun Context.pickedFile(uri: Uri): PickedFile {
    val resolver: ContentResolver = contentResolver
    val name = resolver.displayName(uri) ?: uri.lastPathSegment ?: FALLBACK_NAME
    val mimeType = resolver.getType(uri) ?: FALLBACK_MIME_TYPE
    return PickedFile(
        name = name,
        mimeType = mimeType,
        open = {
            // The provider can be gone by now — a cloud file the user signed out of, a card
            // pulled — and `openInputStream` answers that with null rather than an exception.
            resolver.openInputStream(uri)
                ?: throw java.io.FileNotFoundException("no bytes behind $uri")
        },
    )
}

private fun ContentResolver.displayName(uri: Uri): String? =
    query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
    }?.takeIf { it.isNotBlank() }

/** Only reached for a provider that answers neither a display name nor a path. */
private const val FALLBACK_NAME = "attachment"

private const val FALLBACK_MIME_TYPE = "application/octet-stream"
