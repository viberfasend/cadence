package de.andi1984.cadence.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.Section
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceChip
import de.andi1984.cadence.ui.components.parseColor

/** Which project dialog is on screen, if any. */
sealed interface ProjectDialogState {
    /** Creating one, nested under [parentId] straight away when that came from a project's menu. */
    data class Create(val parentId: String?) : ProjectDialogState

    data class Edit(val project: Project) : ProjectDialogState

    data class Delete(val project: Project) : ProjectDialogState
}

/**
 * Hosts the create/edit/delete dialogs, so the projects list and a single project's screen offer
 * exactly the same three actions.
 */
@Composable
fun ProjectDialogs(
    dialog: ProjectDialogState?,
    state: CadenceUiState,
    onDismiss: () -> Unit,
    onCreateProject: (String, String, String?) -> Unit,
    onEditProject: (Project, String, String, String?) -> Unit,
    onDeleteProject: (Project, Boolean) -> Unit,
) {
    when (dialog) {
        null -> Unit

        is ProjectDialogState.Create -> ProjectEditorDialog(
            project = null,
            candidates = state.nestingCandidates(null),
            initialParentId = dialog.parentId,
            onDismiss = onDismiss,
            onConfirm = { name, color, parentId ->
                onCreateProject(name, color, parentId)
                onDismiss()
            },
        )

        is ProjectDialogState.Edit -> ProjectEditorDialog(
            project = dialog.project,
            candidates = state.nestingCandidates(dialog.project),
            initialParentId = dialog.project.parentId,
            onDismiss = onDismiss,
            onConfirm = { name, color, parentId ->
                onEditProject(dialog.project, name, color, parentId)
                onDismiss()
            },
        )

        is ProjectDialogState.Delete -> DeleteProjectDialog(
            project = dialog.project,
            taskCount = state.tasksIn(dialog.project.id).size,
            subprojectCount = state.subprojects(dialog.project.id).size,
            onDismiss = onDismiss,
            onConfirm = { deleteTasks ->
                onDeleteProject(dialog.project, deleteTasks)
                onDismiss()
            },
        )
    }
}

/** Which section dialog is on screen, if any. */
sealed interface SectionDialogState {
    /** Creating one at the end of [projectId]'s list. */
    data class Create(val projectId: String) : SectionDialogState

    data class Rename(val section: Section) : SectionDialogState

    data class Delete(val section: Section) : SectionDialogState
}

/** Hosts the create/rename/delete dialogs for a project's sections. */
@Composable
fun SectionDialogs(
    dialog: SectionDialogState?,
    state: CadenceUiState,
    onDismiss: () -> Unit,
    onCreateSection: (String, String) -> Unit,
    onRenameSection: (Section, String) -> Unit,
    onDeleteSection: (Section) -> Unit,
) {
    when (dialog) {
        null -> Unit

        is SectionDialogState.Create -> SectionEditorDialog(
            section = null,
            onDismiss = onDismiss,
            onConfirm = { name ->
                onCreateSection(dialog.projectId, name)
                onDismiss()
            },
        )

        is SectionDialogState.Rename -> SectionEditorDialog(
            section = dialog.section,
            onDismiss = onDismiss,
            onConfirm = { name ->
                onRenameSection(dialog.section, name)
                onDismiss()
            },
        )

        is SectionDialogState.Delete -> DeleteSectionDialog(
            section = dialog.section,
            taskCount = state
                .tasksInSection(dialog.section.projectId, dialog.section.id)
                .size,
            onDismiss = onDismiss,
            onConfirm = {
                onDeleteSection(dialog.section)
                onDismiss()
            },
        )
    }
}

/** Create or rename — the same dialog either way, because a section is only ever a name. */
@Composable
fun SectionEditorDialog(
    section: Section?,
    onDismiss: () -> Unit,
    onConfirm: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf(section?.name.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (section == null) {
                        Res.string.sections_dialog_new
                    } else {
                        Res.string.sections_dialog_edit
                    },
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(Res.string.sections_dialog_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(
                    stringResource(
                        if (section == null) Res.string.action_create else Res.string.action_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/**
 * Asks before a heading goes away, and says plainly what happens to the work under it.
 *
 * There is deliberately no "delete the tasks too" here, unlike [DeleteProjectDialog]: a section is
 * a band in a list, and nobody means "and everything in it" by dragging a heading away. The
 * sentence exists so the user does not have to guess that.
 */
@Composable
fun DeleteSectionDialog(
    section: Section,
    taskCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sections_delete_confirm_title, section.name)) },
        text = {
            if (taskCount == 0) {
                Text(stringResource(Res.string.sections_delete_no_tasks))
            } else {
                Text(
                    pluralStringResource(
                        Res.plurals.sections_delete_tasks_kept,
                        taskCount,
                        taskCount,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(Res.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** Rename and delete — the per-section actions, on the band's heading. */
@Composable
fun SectionMenu(
    section: Section,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = AppIcons.MoreVert,
                contentDescription = stringResource(Res.string.sections_actions, section.name),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.sections_rename)) },
                onClick = {
                    open = false
                    onRename()
                },
                leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.sections_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    open = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

/** Rename, nest, delete — the per-project actions, on every row and on the detail screen. */
@Composable
fun ProjectMenu(
    project: Project,
    canAddSubproject: Boolean,
    onEdit: () -> Unit,
    onAddSubproject: () -> Unit,
    onDelete: () -> Unit,
    onAddSection: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = AppIcons.MoreVert,
                contentDescription = stringResource(Res.string.projects_actions, project.name),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.projects_edit)) },
                onClick = {
                    open = false
                    onEdit()
                },
                leadingIcon = { Icon(AppIcons.Edit, contentDescription = null) },
            )
            if (canAddSubproject) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.projects_new_subproject)) },
                    onClick = {
                        open = false
                        onAddSubproject()
                    },
                    leadingIcon = { Icon(AppIcons.CreateNewFolder, contentDescription = null) },
                )
            }
            // Only where the project's own list is on screen: adding a band to a list you are not
            // looking at would put the heading somewhere the user cannot see it land.
            if (onAddSection != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.sections_new)) },
                    onClick = {
                        open = false
                        onAddSection()
                    },
                    leadingIcon = { Icon(AppIcons.Section, contentDescription = null) },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(Res.string.projects_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    open = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

/** The palette a project can be tinted with; the label is what a screen reader announces. */
internal val PROJECT_COLORS = listOf(
    "#006A60" to Res.string.projects_color_teal,
    "#3E6373" to Res.string.projects_color_slate,
    "#A1560A" to Res.string.projects_color_amber,
    "#7D5260" to Res.string.projects_color_plum,
    "#6F7976" to Res.string.projects_color_sage,
    "#BA1A1A" to Res.string.projects_color_red,
)

/**
 * Creates or edits a project — the same dialog either way, because the fields are the same and
 * only the title and the confirm button change.
 *
 * [candidates] are the projects this one may be nested under. It is empty when nesting is not on
 * offer at all (a project with subprojects of its own cannot become one), and the section is then
 * left out rather than shown disabled.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectEditorDialog(
    project: Project?,
    candidates: List<Project>,
    initialParentId: String?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, colorHex: String, parentId: String?) -> Unit,
) {
    var name by remember { mutableStateOf(project?.name.orEmpty()) }
    var color by remember { mutableStateOf(project?.colorHex ?: PROJECT_COLORS.first().first) }
    var parentId by remember { mutableStateOf(initialParentId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (project == null) Res.string.projects_new else Res.string.projects_edit,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(Res.string.projects_dialog_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(Res.string.projects_dialog_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PROJECT_COLORS.forEach { (option, label) ->
                        ColorSwatchButton(
                            colorHex = option,
                            label = stringResource(label),
                            selected = option == color,
                            onClick = { color = option },
                        )
                    }
                }

                if (candidates.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.projects_dialog_nest_under),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CadenceChip(
                            label = stringResource(Res.string.projects_dialog_top_level),
                            selected = parentId == null,
                            onClick = { parentId = null },
                        )
                        candidates.forEach { root ->
                            CadenceChip(
                                label = root.name,
                                selected = parentId == root.id,
                                onClick = { parentId = root.id },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, color, parentId) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    stringResource(
                        if (project == null) Res.string.action_create else Res.string.action_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}

/** A 28dp dot in a 48dp target, named so the choice never rests on colour alone. */
@Composable
private fun ColorSwatchButton(
    colorHex: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(parseColor(colorHex))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

/**
 * Asks before a project goes away, and lets the tasks decide their own fate: they either follow
 * the project into the bin or fall back to the Inbox, which is the safe default.
 */
@Composable
fun DeleteProjectDialog(
    project: Project,
    taskCount: Int,
    subprojectCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (deleteTasks: Boolean) -> Unit,
) {
    var deleteTasks by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.projects_delete_confirm_title, project.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (subprojectCount > 0) {
                    Text(
                        pluralStringResource(
                            Res.plurals.projects_delete_includes_subprojects,
                            subprojectCount,
                            subprojectCount,
                        ),
                    )
                }
                if (taskCount == 0) {
                    Text(stringResource(Res.string.projects_delete_no_tasks))
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = deleteTasks,
                                role = Role.Checkbox,
                                onValueChange = { deleteTasks = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = deleteTasks, onCheckedChange = null)
                        Text(
                            text = pluralStringResource(
                                Res.plurals.projects_delete_tasks_toggle,
                                taskCount,
                                taskCount,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = pluralStringResource(
                            if (deleteTasks) {
                                Res.plurals.projects_delete_tasks_gone
                            } else {
                                Res.plurals.projects_delete_tasks_kept
                            },
                            taskCount,
                            taskCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteTasks) }) {
                Text(
                    text = stringResource(Res.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
        },
    )
}
