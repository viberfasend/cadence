package de.andi1984.cadence.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.CadenceViewModel
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.palette.CommandPalette
import de.andi1984.cadence.ui.palette.PaletteEntry
import de.andi1984.cadence.ui.palette.PaletteKind
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.ThemeChoice
import org.jetbrains.compose.resources.stringResource

/**
 * `Ctrl`/`Cmd`+`K`: one field over every task, every project and every verb the shell has.
 *
 * The ranking is `:ui`'s [CommandPalette], which is pure and tested. What lives here is the part
 * that cannot be: which commands exist (half of them are navigation, which only the shell knows),
 * and what choosing a row does.
 */
@Composable
fun CommandPaletteDialog(
    state: CadenceUiState,
    viewModel: CadenceViewModel,
    navigator: DesktopNavigator,
    onDismiss: () -> Unit,
    onNewTask: () -> Unit,
    onNewProject: () -> Unit,
    onShowShortcuts: () -> Unit,
    onToggleSidebar: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val commands = paletteCommands(
        onNewTask = onNewTask,
        onNewProject = onNewProject,
        onSyncNow = { viewModel.syncNow() },
        onToggleTheme = {
            viewModel.setTheme(
                if (state.settings.theme == ThemeChoice.DARK) ThemeChoice.LIGHT else ThemeChoice.DARK,
            )
        },
        onToggleDensity = {
            viewModel.setDensity(
                if (state.settings.density == Density.COMPACT) Density.COMFORTABLE else Density.COMPACT,
            )
        },
        onShowShortcuts = onShowShortcuts,
        onToggleSidebar = onToggleSidebar,
        onGoTo = navigator::switchTo,
    )

    val entries = remember(state.tasks, state.projects, commands) {
        commands.map { it.entry } +
            state.projects.map { PaletteEntry(it.id, it.name, PaletteKind.Project) } +
            state.openTasks().map { task ->
                PaletteEntry(
                    id = task.id,
                    title = task.title,
                    kind = PaletteKind.Task,
                    subtitle = state.projectLabel(task),
                )
            }
    }

    val matches = CommandPalette.search(query, entries)
    val safeSelection = selected.coerceIn(0, (matches.size - 1).coerceAtLeast(0))

    fun run(index: Int) {
        val entry = matches.getOrNull(index)?.entry ?: return
        onDismiss()
        when (entry.kind) {
            PaletteKind.Command -> commands.firstOrNull { it.entry.id == entry.id }?.run?.invoke()
            PaletteKind.Project -> navigator.go(Route.ProjectDetail(entry.id))
            PaletteKind.Task -> navigator.go(Route.TaskDetail(entry.id))
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(safeSelection) {
        if (matches.isNotEmpty()) listState.animateScrollToItem(safeSelection)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(620.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        selected = 0
                    },
                    singleLine = true,
                    placeholder = { Text(stringResource(Res.string.palette_placeholder)) },
                    leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
                    supportingText = { Text(stringResource(Res.string.palette_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        // Preview, so the arrows drive the list rather than moving the caret.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (matches.isNotEmpty()) selected = (safeSelection + 1) % matches.size
                                    true
                                }

                                Key.DirectionUp -> {
                                    if (matches.isNotEmpty()) {
                                        selected = (safeSelection - 1 + matches.size) % matches.size
                                    }
                                    true
                                }

                                Key.Enter, Key.NumPadEnter -> {
                                    run(safeSelection)
                                    true
                                }

                                Key.Escape -> {
                                    onDismiss()
                                    true
                                }

                                else -> false
                            }
                        },
                )

                if (matches.isEmpty()) {
                    Text(
                        text = stringResource(Res.string.palette_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = 420.dp),
                    ) {
                        matches.forEachIndexed { index, match ->
                            // A heading whenever the kind changes: the ranking already groups
                            // them, so this is a label rather than a second sort.
                            if (index == 0 || matches[index - 1].entry.kind != match.entry.kind) {
                                item(key = "header-${match.entry.kind}") {
                                    PaletteGroupHeader(match.entry.kind)
                                }
                            }
                            item(key = "${match.entry.kind}-${match.entry.id}") {
                                PaletteRow(
                                    entry = match.entry,
                                    selected = index == safeSelection,
                                    onClick = { run(index) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaletteGroupHeader(kind: PaletteKind) {
    val label = when (kind) {
        PaletteKind.Command -> Res.string.palette_group_commands
        PaletteKind.Project -> Res.string.palette_group_projects
        PaletteKind.Task -> Res.string.palette_group_tasks
    }
    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text(
            text = stringResource(label).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun PaletteRow(entry: PaletteEntry, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = when (entry.kind) {
                PaletteKind.Command -> AppIcons.Tune
                PaletteKind.Project -> AppIcons.Folder
                PaletteKind.Task -> AppIcons.Checklist
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            entry.subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A verb the palette can run, paired with the entry that offers it. */
private class PaletteCommand(val entry: PaletteEntry, val run: () -> Unit)

@Composable
private fun paletteCommands(
    onNewTask: () -> Unit,
    onNewProject: () -> Unit,
    onSyncNow: () -> Unit,
    onToggleTheme: () -> Unit,
    onToggleDensity: () -> Unit,
    onShowShortcuts: () -> Unit,
    onToggleSidebar: () -> Unit,
    onGoTo: (Route) -> Unit,
): List<PaletteCommand> {
    fun command(id: String, title: String, run: () -> Unit) =
        PaletteCommand(PaletteEntry(id, title, PaletteKind.Command), run)

    val goTo = stringResource(Res.string.command_go_to, "")
    val destinations = listOf(
        Route.Today to stringResource(Res.string.nav_today),
        Route.Upcoming to stringResource(Res.string.nav_upcoming),
        Route.Inbox to stringResource(Res.string.nav_inbox),
        Route.Projects to stringResource(Res.string.nav_projects),
        Route.Search to stringResource(Res.string.search_title),
        Route.Settings to stringResource(Res.string.settings_title),
    )

    return listOf(
        command("cmd:new-task", stringResource(Res.string.command_new_task), onNewTask),
        command("cmd:new-project", stringResource(Res.string.command_new_project), onNewProject),
        command("cmd:sync", stringResource(Res.string.command_sync_now), onSyncNow),
        command("cmd:theme", stringResource(Res.string.command_toggle_theme), onToggleTheme),
        command("cmd:density", stringResource(Res.string.command_toggle_density), onToggleDensity),
        command("cmd:sidebar", stringResource(Res.string.command_toggle_sidebar), onToggleSidebar),
        command("cmd:shortcuts", stringResource(Res.string.command_show_shortcuts), onShowShortcuts),
    ) + destinations.map { (route, name) ->
        // "Go to Today" rather than "Today": a command reads as an instruction, and it keeps a
        // view from ranking against a project or task of the same name.
        command("cmd:go:$name", (goTo + name).trim(), { onGoTo(route) })
    }
}
