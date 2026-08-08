package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.ui.platform.BackupFilePicker
import de.andi1984.cadence.ui.platform.BackupTarget
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/** The desktop's answer to [BackupFilePicker]: Swing's file chooser instead of a SAF intent.
 *  Both methods call back synchronously — `showSaveDialog`/`showOpenDialog` are modal, so
 *  [onPicked] has already run by the time the call returns — but the port stays callback-shaped
 *  because Android's genuinely can't be. */
class DesktopBackupFilePicker : BackupFilePicker {

    private val jsonFilter = FileNameExtensionFilter("Cadence backup (*.json)", "json")

    override fun pickExportTarget(suggestedName: String, onPicked: (BackupTarget) -> Unit) {
        val chooser = JFileChooser().apply {
            fileFilter = jsonFilter
            selectedFile = File(suggestedName)
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val chosen = chooser.selectedFile
            val withExtension = if (chosen.extension.equals("json", ignoreCase = true)) {
                chosen
            } else {
                File(chosen.parentFile, "${chosen.name}.json")
            }
            onPicked(BackupTarget(withExtension.absolutePath))
        }
    }

    override fun pickImportSource(onPicked: (BackupTarget) -> Unit) {
        val chooser = JFileChooser().apply { fileFilter = jsonFilter }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            onPicked(BackupTarget(chooser.selectedFile.absolutePath))
        }
    }
}
