package de.andi1984.cadence.ui.attachments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Paste-a-link, the other half of the add menu.
 *
 * A LINK attachment is a URL and nothing else — no bytes, no blob, nothing to reclaim — so this
 * needs no picker, no permission and no copy. The title is optional and falls back to the URL
 * itself in [de.andi1984.cadence.data.CadenceRepository.addLinkAttachment], which is where the
 * validation lives too: the dialog only refuses to *submit* an empty link, it does not decide
 * what a link is.
 */
@Composable
fun AttachmentLinkDialog(
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.attachments_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(Res.string.attachments_link_url)) },
                    placeholder = {
                        Text(stringResource(Res.string.attachments_link_url_placeholder))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.attachments_link_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank(),
                onClick = {
                    onConfirm(url.trim(), name.trim())
                    onDismiss()
                },
            ) {
                Text(stringResource(Res.string.attachments_link_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
