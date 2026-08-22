package de.andi1984.cadence.ui.attachments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Attachment
import de.andi1984.cadence.domain.model.AttachmentKind
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.format.formatFileSize
import de.andi1984.cadence.ui.platform.AttachmentFilePicker
import de.andi1984.cadence.ui.platform.PickedFile
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.io.File

/**
 * The attachments card on the task detail screen, between the checklist and the notes.
 *
 * Two kinds share one list. A LINK is a URL with a title; a FILE is a row pointing at a blob that
 * may or may not be on this device, and *which* it is decides what tapping it does: open the
 * file, or go looking for it. Nothing here ever asks the filesystem — [isPresent] answers from
 * the index the state was built with, so drawing twenty rows costs twenty map lookups.
 *
 * [picker] is null on a shell with no file chooser, and then the card offers links only rather
 * than a button that cannot work.
 */
@Composable
fun AttachmentCard(
    attachments: List<Attachment>,
    isPresent: (Attachment) -> Boolean,
    blobFile: (String) -> File?,
    picker: AttachmentFilePicker?,
    onAddFile: (PickedFile) -> Unit,
    onAddLink: (url: String, name: String) -> Unit,
    onOpen: (Attachment) -> Unit,
    onRelocate: (Attachment, PickedFile) -> Unit,
    onRemove: (Attachment) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var linkDialogOpen by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainer)
            .padding(vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = AppIcons.Attachment,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(Res.string.attachments_title),
                style = MaterialTheme.typography.titleSmall,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (attachments.isNotEmpty()) {
                val spokenCount = attachmentCountLabel(attachments.size)
                Text(
                    text = attachments.size.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    // A bare "3" beside a header reads as nothing at all out loud, the same
                    // reason the checklist's "2/5" carries a spoken form of its own.
                    modifier = Modifier.semantics { contentDescription = spokenCount },
                )
            }
        }

        if (attachments.isEmpty()) {
            Text(
                text = stringResource(Res.string.attachments_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
            )
        } else {
            attachments.forEach { attachment ->
                AttachmentRow(
                    attachment = attachment,
                    present = isPresent(attachment),
                    blobFile = blobFile,
                    picker = picker,
                    onOpen = { onOpen(attachment) },
                    onRelocate = { picked -> onRelocate(attachment, picked) },
                    onRemove = { onRemove(attachment) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (picker != null) {
                TextButton(onClick = { picker.pickFile(onAddFile) }) {
                    Text(stringResource(Res.string.attachments_add_file))
                }
            }
            TextButton(onClick = { linkDialogOpen = true }) {
                Text(stringResource(Res.string.attachments_add_link))
            }
        }
    }

    if (linkDialogOpen) {
        AttachmentLinkDialog(
            onDismiss = { linkDialogOpen = false },
            onConfirm = onAddLink,
        )
    }
}

/**
 * One attachment.
 *
 * A row whose blob is gone is drawn, not hidden: greyed, with "not on this device" where the size
 * would be, and tapping it opens the picker to find the file again rather than a viewer with
 * nothing to show. That is the whole of the missing-blob contract — never crash, never look like
 * corruption (`docs/attachments-and-share.md`, "When a blob is missing").
 */
@Composable
private fun AttachmentRow(
    attachment: Attachment,
    present: Boolean,
    blobFile: (String) -> File?,
    picker: AttachmentFilePicker?,
    onOpen: () -> Unit,
    onRelocate: (PickedFile) -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // With no picker there is no way to find the file again, so a missing row stays inert rather
    // than offering an action the shell cannot carry out.
    val locate: (() -> Unit)? =
        if (!present && picker != null) ({ picker.pickFile(onRelocate) }) else null
    val openLabel = stringResource(Res.string.attachments_open, attachment.name)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = present || locate != null) {
                if (present) onOpen() else locate?.invoke()
            }
            .defaultMinSize(minHeight = 56.dp)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .semantics { contentDescription = openLabel },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AttachmentThumbnail(
            attachment = attachment,
            present = present,
            blobFile = blobFile,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (present) scheme.onSurface else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    !present -> stringResource(Res.string.attachments_missing)
                    attachment.kind == AttachmentKind.LINK -> attachment.url.orEmpty()
                    else -> formatFileSize(attachment.sizeBytes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (present) scheme.onSurfaceVariant else scheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (locate != null) {
            TextButton(onClick = locate) {
                Text(stringResource(Res.string.attachments_locate))
            }
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = AppIcons.Close,
                contentDescription = stringResource(Res.string.attachments_remove),
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** "3 attachments" — the count a row's paperclip and its screen reader label share. */
@Composable
fun attachmentCountLabel(count: Int): String =
    pluralStringResource(Res.plurals.attachments_count, count, count)
