package de.andi1984.cadence.ui.quickadd

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.domain.parse.ParsedQuickAdd
import de.andi1984.cadence.domain.parse.QuickAddParser
import de.andi1984.cadence.domain.parse.TokenKind
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceDatePickerDialog
import de.andi1984.cadence.ui.components.PrioritySpine
import de.andi1984.cadence.ui.components.ProjectPickerDialog
import de.andi1984.cadence.ui.components.ProjectSwatch
import de.andi1984.cadence.ui.format.describeRecurrence
import de.andi1984.cadence.ui.format.formatDate
import de.andi1984.cadence.ui.format.label
import de.andi1984.cadence.ui.recurrence.RecurrenceSheet
import de.andi1984.cadence.ui.theme.LocalCadenceColors
import java.time.LocalDate

/**
 * Capture in one line. Dates, recurrence, priority and project are parsed as you type and
 * echoed back as chips — everything is optional and plain words stay in the title.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(
    projects: List<Project>,
    today: LocalDate,
    defaultProjectId: Long?,
    onDismiss: () -> Unit,
    onSubmit: (ParsedQuickAdd) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val cadenceColors = LocalCadenceColors.current
    val scheme = MaterialTheme.colorScheme

    var text by remember { mutableStateOf("") }
    var dateOverride by remember { mutableStateOf<LocalDate?>(null) }
    var priorityOverride by remember { mutableStateOf<Priority?>(null) }
    var projectOverride by remember { mutableStateOf(defaultProjectId) }
    var recurrenceOverride by remember { mutableStateOf<RecurrenceRule?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var projectPickerOpen by remember { mutableStateOf(false) }
    var recurrenceOpen by remember { mutableStateOf(false) }

    val parsed = remember(text, projects) { QuickAddParser.parse(text, projects, today) }
    val effective = parsed.copy(
        dueDate = dateOverride ?: parsed.dueDate,
        priority = priorityOverride ?: parsed.priority,
        projectId = projectOverride ?: parsed.projectId,
        recurrence = recurrenceOverride ?: parsed.recurrence,
    )
    val chosenProject = projects.firstOrNull { it.id == effective.projectId }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
    ) {
        Column(modifier = Modifier.imePadding().padding(bottom = 12.dp)) {
            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.titleLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    visualTransformation = TokenHighlightTransformation(
                        parsed = parsed,
                        dateColor = cadenceColors.tokenDate,
                        priorityColor = cadenceColors.tokenPriority,
                        projectColor = cadenceColors.tokenProject,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = { if (effective.title.isNotBlank()) onSubmit(effective) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    decorationBox = { inner ->
                        if (text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.quick_add_placeholder),
                                style = MaterialTheme.typography.titleLarge,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )

                FlowRow(
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    effective.dueDate?.let { due ->
                        TokenChip(
                            label = formatDate(due),
                            icon = AppIcons.Event,
                            background = cadenceColors.tokenDate,
                            foreground = cadenceColors.onTokenDate,
                            onClick = { datePickerOpen = true },
                        )
                    }
                    effective.recurrence?.let { rule ->
                        TokenChip(
                            label = describeRecurrence(rule),
                            icon = AppIcons.EventRepeat,
                            background = cadenceColors.tokenDate,
                            foreground = cadenceColors.onTokenDate,
                            onClick = { recurrenceOpen = true },
                        )
                    }
                    effective.priority?.let { priority ->
                        TokenChip(
                            label = priority.label(),
                            background = cadenceColors.tokenPriority,
                            foreground = cadenceColors.onTokenPriority,
                            onClick = { priorityOverride = nextPriority(priority) },
                            leading = {
                                PrioritySpine(
                                    priority = priority,
                                    overrideColor = cadenceColors.onTokenPriority,
                                    overrideTrack = cadenceColors.onTokenPriority.copy(alpha = 0.25f),
                                )
                            },
                        )
                    }
                    if (chosenProject != null) {
                        TokenChip(
                            label = projectPath(chosenProject, projects).orEmpty(),
                            background = cadenceColors.tokenProject,
                            foreground = cadenceColors.onTokenProject,
                            onClick = { projectPickerOpen = true },
                            leading = { ProjectSwatch(colorHex = chosenProject.colorHex, size = 9) },
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.quick_add_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
            }

            HorizontalDivider(color = scheme.outlineVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = { datePickerOpen = true }) {
                    Icon(
                        AppIcons.Event,
                        contentDescription = stringResource(R.string.quick_add_set_due_date),
                    )
                }
                IconButton(onClick = { recurrenceOpen = true }) {
                    Icon(
                        AppIcons.EventRepeat,
                        contentDescription = stringResource(R.string.quick_add_set_repeat),
                    )
                }
                IconButton(
                    onClick = {
                        priorityOverride = nextPriority(effective.priority ?: Priority.P4)
                    },
                ) {
                    Icon(
                        AppIcons.Flag,
                        contentDescription = stringResource(R.string.quick_add_cycle_importance),
                    )
                }
                IconButton(onClick = { projectPickerOpen = true }) {
                    Icon(
                        AppIcons.Folder,
                        contentDescription = stringResource(R.string.quick_add_choose_project),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (effective.title.isBlank()) {
                                scheme.surfaceContainerHigh
                            } else {
                                scheme.primary
                            },
                        )
                        .clickable(enabled = effective.title.isNotBlank()) { onSubmit(effective) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = AppIcons.ArrowUpward,
                        contentDescription = stringResource(R.string.quick_add_submit),
                        tint = if (effective.title.isBlank()) {
                            scheme.onSurfaceVariant
                        } else {
                            scheme.onPrimary
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }
    }

    if (datePickerOpen) {
        CadenceDatePickerDialog(
            initial = effective.dueDate,
            onDismiss = { datePickerOpen = false },
            onPick = { dateOverride = it },
        )
    }
    if (projectPickerOpen) {
        ProjectPickerDialog(
            projects = projects,
            selectedId = effective.projectId,
            onDismiss = { projectPickerOpen = false },
            onPick = { projectOverride = it },
        )
    }
    if (recurrenceOpen) {
        RecurrenceSheet(
            initial = effective.recurrence,
            taskTitle = effective.title.ifBlank { stringResource(R.string.quick_add_new_task) },
            anchorDate = effective.dueDate ?: today,
            onDismiss = { recurrenceOpen = false },
            onSave = { rule ->
                recurrenceOverride = rule
                recurrenceOpen = false
            },
        )
    }
}

private fun nextPriority(current: Priority): Priority =
    Priority.entries[(current.ordinal + 1) % Priority.entries.size]

@Composable
private fun TokenChip(
    label: String,
    background: Color,
    foreground: Color,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(18.dp),
            )

            leading != null -> leading()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
        )
    }
}

/** Paints the recognised tokens in the typed line, mirroring the chips below it. */
private class TokenHighlightTransformation(
    private val parsed: ParsedQuickAdd,
    private val dateColor: Color,
    private val priorityColor: Color,
    private val projectColor: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = buildAnnotatedString {
            append(text.text)
            parsed.spans.forEach { span ->
                val start = span.range.first.coerceIn(0, text.length)
                val end = (span.range.last + 1).coerceIn(0, text.length)
                if (start >= end) return@forEach
                val background = when (span.kind) {
                    TokenKind.DATE, TokenKind.TIME, TokenKind.RECURRENCE -> dateColor
                    TokenKind.PRIORITY -> priorityColor
                    TokenKind.PROJECT -> projectColor
                }
                addStyle(SpanStyle(background = background), start, end)
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
