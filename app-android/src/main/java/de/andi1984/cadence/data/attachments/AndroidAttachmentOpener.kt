package de.andi1984.cadence.data.attachments

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import de.andi1984.cadence.ui.platform.AttachmentOpener
import java.io.File

/**
 * Android's answer to [AttachmentOpener]: an `ACTION_VIEW` intent, and a `FileProvider` to make
 * the blob readable by whatever takes it.
 *
 * A blob lives under `filesDir`, which no other app can read — handing out a `file://` uri would
 * throw `FileUriExposedException` on anything since Android 7 anyway. The provider declared in
 * the manifest turns it into a `content://` uri, and `FLAG_GRANT_READ_URI_PERMISSION` lends the
 * viewer read access for as long as its task lives, which is the whole grant: nothing else about
 * the file leaves the app.
 *
 * The context is the application's — the intent outlives whichever screen started it, and
 * `FLAG_ACTIVITY_NEW_TASK` is required for exactly that reason when starting from a non-Activity
 * context.
 */
class AndroidAttachmentOpener(context: Context) : AttachmentOpener {

    private val context = context.applicationContext

    override fun openFile(file: File, name: String, mimeType: String): Boolean {
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
        } catch (e: IllegalArgumentException) {
            // The file is outside every path the provider declares — a bug rather than a state,
            // but not one worth crashing a viewer tap over.
            return false
        }
        return start(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    override fun openLink(url: String): Boolean =
        start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        // A phone with nothing that opens this type, or a "link" that is not a web address at
        // all. Both are ordinary; the caller says so in a snackbar.
        false
    }
}
