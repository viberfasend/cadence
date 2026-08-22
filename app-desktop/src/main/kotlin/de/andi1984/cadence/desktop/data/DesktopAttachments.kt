package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.ui.platform.AttachmentFilePicker
import de.andi1984.cadence.ui.platform.AttachmentOpener
import de.andi1984.cadence.ui.platform.PickedFile
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import javax.swing.JFileChooser

/**
 * The desktop's answer to [AttachmentFilePicker]: Swing's chooser, exactly as
 * [DesktopBackupFilePicker] uses it, minus the extension filter — an attachment is any file.
 *
 * `showOpenDialog` is modal, so [onPicked] has already run by the time `pickFile` returns; the
 * port stays callback-shaped because Android's genuinely cannot be.
 */
class DesktopAttachmentFilePicker : AttachmentFilePicker {

    override fun pickFile(onPicked: (PickedFile) -> Unit) {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return
        val chosen = chooser.selectedFile ?: return
        onPicked(
            PickedFile(
                name = chosen.name,
                mimeType = chosen.probeMimeType(),
                open = { chosen.inputStream() },
            ),
        )
    }
}

/**
 * The desktop's answer to [AttachmentOpener]: `java.awt.Desktop`, which hands the file or the URL
 * to whatever the session's desktop environment has registered for it.
 *
 * Every call is guarded twice — [Desktop.isDesktopSupported] and the per-action
 * [Desktop.isSupported] — because a headless run and a Linux session without `xdg-open` both
 * report a desktop that cannot actually open anything, and the port's `false` is precisely the
 * answer for that.
 */
class DesktopAttachmentOpener : AttachmentOpener {

    override fun openFile(file: File, name: String, mimeType: String): Boolean =
        desktop(Desktop.Action.OPEN)?.runCatching { open(file) }?.isSuccess ?: false

    override fun openLink(url: String): Boolean =
        desktop(Desktop.Action.BROWSE)?.runCatching { browse(URI(url)) }?.isSuccess ?: false

    /** The shared desktop, or null when this machine has none that can do [action]. */
    private fun desktop(action: Desktop.Action): Desktop? =
        if (!Desktop.isDesktopSupported()) {
            null
        } else {
            Desktop.getDesktop().takeIf { it.isSupported(action) }
        }
}

/**
 * The file's media type, from the OS's own table.
 *
 * `Files.probeContentType` is the JDK's answer and returns null often enough — a system with no
 * mime database, an extension nobody registered — that the fallback matters: `octet-stream` is
 * what an unknown file is, and it only ever decides which icon the row draws.
 */
private fun File.probeMimeType(): String =
    runCatching { Files.probeContentType(toPath()) }.getOrNull() ?: "application/octet-stream"
