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
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.projectPath
import de.andi1984.cadence.domain.parse.ParsedQuickAdd
import de.andi1984.cadence.domain.parse.QuickAddLexicon
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.domain.parse.QuickAddParser
import de.andi1984.cadence.domain.parse.TokenKind
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceDatePickerDialog
import de.andi1984.cadence.ui.components.PrioritySpine
import de.andi1984.cadence.ui.components.ProjectPickerDialog
import de.andi1984.cadence.ui.components.ProjectSwatch
import de.andi1984.cadence.ui.components.TagChip
import de.andi1984.cadence.ui.format.currentLocale
import de.andi1984.cadence.ui.format.describeRecurrence
import de.andi1984.cadence.ui.format.formatDate
import de.andi1984.cadence.ui.format.label
import de.andi1984.cadence.ui.recurrence.RecurrenceSheet
import de.andi1984.cadence.ui.theme.CadenceColors
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
    tags: List<Tag>,
    today: LocalDate,
    defaultProjectId: String?,
    onDismiss: () -> Unit,
    onSubmit: (ParsedQuickAdd) -> Unit,
    // Voice capture (an App Actions `CREATE_ITEM_LIST` fulfillment on Android) opens the sheet
    // with the spoken text already parsed, exactly as if the user had typed it — the sheet still
    // requires a confirming tap so a bad transcription never reaches the task list unseen.
    initialText: String = "",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val cadenceColors = LocalCadenceColors.current
    val scheme = MaterialTheme.colorScheme

    var text by remember { mutableStateOf(initialText) }
    var dateOverride by remember { mutableStateOf<LocalDate?>(null) }
    var priorityOverride by remember { mutableStateOf<Priority?>(null) }
    var projectOverride by remember { mutableStateOf(defaultProjectId) }
    var recurrenceOverride by remember { mutableStateOf<RecurrenceRule?>(null) }
    var datePickerOpen by remember { mutableStateOf(false) }
    var projectPickerOpen by remember { mutableStateOf(false) }
    var recurrenceOpen by remember { mutableStateOf(false) }

    // Keywords follow the app language, not the system one — English always stays understood.
    val lexicon = QuickAddLexicon.forLocale(currentLocale())
    val parsed = remember(text, projects, tags, lexicon) {
        QuickAddParser.parse(text, projects, tags, today, lexicon)
    }
    val effective = parsed.copy(
        dueDate = dateOverride ?: parsed.dueDate,
        priority = priorityOverride ?: parsed.priority,
        projectId = projectOverride ?: parsed.projectId,
        recurrence = recurrenceOverride ?: parsed.recurrence,
    )
    val chosenProject = projects.firstOrNull { it.id == effective.projectId }
    val chosenTags = effective.tagIds.mapNotNull { id -> tags.firstOrNull { it.id == id } }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface,
    ) {
        QuickAddBody(
            text = text,
            onTextChange = { text = it },
            parsed = parsed,
            effective = effective,
            projects = projects,
            chosenProject = chosenProject,
            chosenTags = chosenTags,
            cadenceColors = cadenceColors,
            focusRequester = focusRequester,
            onOpenDatePicker = { datePickerOpen = true },
            onOpenProjectPicker = { projectPickerOpen = true },
            onOpenRecurrence = { recurrenceOpen = true },
            onPriorityChange = { priorityOverride = it },
            onSubmit = { onSubmit(effective) },
        )
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
            taskTitle = effective.title.ifBlank { stringResource(Res.string.quick_add_new_task) },
            anchorDate = effective.dueDate ?: today,
            onDismiss = { recurrenceOpen = false },
            onSave = { rule ->
                recurrenceOverride = rule
                recurrenceOpen = false
            },
        )
    }
}

/**
 * The sheet's content — text field, recognised-token chips, hint and the action row. Pulled out
 * of [QuickAddSheet] so a future attachment section (phase 1) has a body to add itself to
 * without threading through the [ModalBottomSheet] shell.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAddBody(
    text: String,
    onTextChange: (String) -> Unit,
    parsed: ParsedQuickAdd,
    effective: ParsedQuickAdd,
    projects: List<Project>,
    chosenProject: Project?,
    chosenTags: List<Tag>,
    cadenceColors: CadenceColors,
    focusRequester: FocusRequester,
    onOpenDatePicker: () -> Unit,
    onOpenProjectPicker: () -> Unit,
    onOpenRecurrence: () -> Unit,
    onPriorityChange: (Priority) -> Unit,
    onSubmit: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    Column(modifier = Modifier.imePadding().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
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
                    onDone = { if (effective.title.isNotBlank()) onSubmit() },
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            text = stringResource(Res.string.quick_add_placeholder),
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
                        onClick = onOpenDatePicker,
                    )
                }
                effective.recurrence?.let { rule ->
                    TokenChip(
                        label = describeRecurrence(rule),
                        icon = AppIcons.EventRepeat,
                        background = cadenceColors.tokenDate,
                        foreground = cadenceColors.onTokenDate,
                        onClick = onOpenRecurrence,
                    )
                }
                effective.priority?.let { priority ->
                    TokenChip(
                        label = priority.label(),
                        background = cadenceColors.tokenPriority,
                        foreground = cadenceColors.onTokenPriority,
                        onClick = { onPriorityChange(nextPriority(priority)) },
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
                        onClick = onOpenProjectPicker,
                        leading = { ProjectSwatch(colorHex = chosenProject.colorHex, size = 9) },
                    )
                }
                // Both halves of what the `@` pass found: the tags that exist, drawn as
                // themselves, and the handles that matched nothing, which the sheet promises to
                // create rather than leave in the title.
                chosenTags.forEach { tag -> TagChip(tag = tag) }
                effective.newTagNames.forEach { name ->
                    TokenChip(
                        label = "@$name",
                        icon = AppIcons.Add,
                        background = cadenceColors.tokenProject,
                        foreground = cadenceColors.onTokenProject,
                    )
                }
            }

            Text(
                text = stringResource(Res.string.quick_add_hint),
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
            IconButton(onClick = onOpenDatePicker) {
                Icon(
                    AppIcons.Event,
                    contentDescription = stringResource(Res.string.quick_add_set_due_date),
                )
            }
            IconButton(onClick = onOpenRecurrence) {
                Icon(
                    AppIcons.EventRepeat,
                    contentDescription = stringResource(Res.string.quick_add_set_repeat),
                )
            }
            IconButton(
                onClick = { onPriorityChange(nextPriority(effective.priority ?: Priority.P4)) },
            ) {
                Icon(
                    AppIcons.Flag,
                    contentDescription = stringResource(Res.string.quick_add_cycle_importance),
                )
            }
            IconButton(onClick = onOpenProjectPicker) {
                Icon(
                    AppIcons.Folder,
                    contentDescription = stringResource(Res.string.quick_add_choose_project),
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
                    .clickable(enabled = effective.title.isNotBlank(), onClick = onSubmit),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = AppIcons.ArrowUpward,
                    contentDescription = stringResource(Res.string.quick_add_submit),
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

private fun nextPriority(current: Priority): Priority =
    Priority.entries[(current.ordinal + 1) % Priority.entries.size]

@Composable
private fun TokenChip(
    label: String,
    background: Color,
    foreground: Color,
    /** Null for a chip that only reports — the tag the sheet is about to create has nothing to
     *  open, and a clickable with no action is a target a screen reader promises for nothing. */
    onClick: (() -> Unit)? = null,
    icon: ImageVector? = null,
    leading: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(if (onClick == null) Modifier else Modifier.clickable(onClick = onClick))
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
                    // Tags share the project highlight for the same reason they share its
                    // palette: two tints that mean "a thing this task belongs to" would only
                    // ever be told apart by people who can tell them apart.
                    TokenKind.PROJECT, TokenKind.TAG -> projectColor
                }
                addStyle(SpanStyle(background = background), start, end)
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
