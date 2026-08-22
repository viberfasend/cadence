package de.andi1984.cadence.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.ui.components.TagChip
import de.andi1984.cadence.ui.components.parseColor
import de.andi1984.cadence.ui.projects.PROJECT_COLORS
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Which tags a task wears.
 *
 * Checkboxes rather than the single-choice rows [de.andi1984.cadence.ui.components
 * .ProjectPickerDialog] uses, because that is the whole difference between the two ideas: a task
 * is filed in one place and labelled any number of times. There is no "none" row either — clearing
 * every box *is* none.
 */
@Composable
fun TagPickerDialog(
    tags: List<Tag>,
    selectedIds: List<String>,
    onDismiss: () -> Unit,
    onToggle: (Tag) -> Unit,
    onCreate: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tags_picker_title)) },
        text = {
            if (tags.isEmpty()) {
                Text(
                    text = stringResource(Res.string.tags_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    tags.forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(tag) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = tag.id in selectedIds,
                                onCheckedChange = { onToggle(tag) },
                            )
                            TagChip(tag = tag, selected = tag.id in selectedIds)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_save)) }
        },
        dismissButton = onCreate?.let {
            { TextButton(onClick = it) { Text(stringResource(Res.string.tags_new)) } }
        },
    )
}

/**
 * Create or rename a tag, and pick its colour.
 *
 * Shares [PROJECT_COLORS] with the project dialog on purpose: two palettes would drift, and a tag
 * and a project are told apart by the `@` and the chip's shape, never by which six colours they
 * may be.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagEditorDialog(
    tag: Tag?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, colorHex: String) -> Unit,
) {
    var name by remember { mutableStateOf(tag?.name.orEmpty()) }
    var color by remember { mutableStateOf(tag?.colorHex ?: PROJECT_COLORS.first().first) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (tag == null) Res.string.tags_new else Res.string.tags_edit,
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.tags_dialog_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(Res.string.tags_dialog_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PROJECT_COLORS.forEach { (hex, label) ->
                        ColorDot(
                            colorHex = hex,
                            label = stringResource(label),
                            selected = hex == color,
                            onClick = { color = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(name.trim(), color)
                    onDismiss()
                },
            ) {
                Text(
                    stringResource(
                        if (tag == null) Res.string.action_create else Res.string.action_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

@Composable
private fun ColorDot(
    colorHex: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 44dp of touch target around a 24dp dot — the same rule the priority circles follow.
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(parseColor(colorHex))
                .border(
                    width = if (selected) 3.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    shape = CircleShape,
                ),
        )
    }
}

/** Deleting a tag takes no work with it, so the dialog says exactly that and how many rows are
 *  about to lose the label. */
@Composable
fun DeleteTagDialog(
    tag: Tag,
    taskCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.tags_delete_confirm_title, tag.name)) },
        text = {
            Text(
                if (taskCount == 0) {
                    stringResource(Res.string.tags_delete_confirm_none)
                } else {
                    org.jetbrains.compose.resources.pluralStringResource(
                        Res.plurals.tags_delete_confirm_body,
                        taskCount,
                        taskCount,
                    )
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text(stringResource(Res.string.action_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
