package de.andi1984.cadence.ui.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import de.andi1984.cadence.ui.TaskView
import de.andi1984.cadence.ui.taskList
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ScreenHeader
import de.andi1984.cadence.ui.components.SyncActions
import de.andi1984.cadence.ui.components.SyncControls
import de.andi1984.cadence.ui.components.SyncRefreshBox
import de.andi1984.cadence.ui.components.TaskRow
import de.andi1984.cadence.ui.dnd.DropCaret
import de.andi1984.cadence.ui.dnd.DropTarget
import de.andi1984.cadence.ui.dnd.OrderedList
import java.time.LocalDate

@Composable
fun InboxScreen(
    state: CadenceUiState,
    today: LocalDate,
    onTaskClick: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onTriage: () -> Unit,
    syncControls: SyncControls = SyncControls(),
) {
    // Root tasks only: a subtask of an Inbox task is folded into its parent's row unless the
    // parent is expanded (see rows below).
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }
    val list = state.taskList(TaskView.Inbox, today, expandedIds)
    val open = list.openCount
    val rows = list.rows

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(Res.string.inbox_title),
            subtitle = if (open == 0) {
                stringResource(Res.string.inbox_empty_subtitle)
            } else {
                pluralStringResource(Res.plurals.inbox_to_sort, open, open)
            },
        ) {
            SyncActions(status = state.sync.status, controls = syncControls)
            if (open > 0) {
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onTriage)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = AppIcons.Flag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(Res.string.triage_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        if (list.isEmpty) {
            EmptyState(
                title = stringResource(Res.string.inbox_empty_title),
                supporting = stringResource(Res.string.inbox_empty_supporting),
            )
            return@Column
        }

        SyncRefreshBox(status = state.sync.status, controls = syncControls) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // The Inbox is a container, so a row dropped between two here is both an ordering
                // and — if it came from a project — a move back to the Inbox. `resolveDrop` says
                // which; this only registers the gaps. Only root rows are ordered: a step's place
                // belongs to its parent's checklist.
                val rootIds = rows.filterNot { it.isSubtaskRow }.map { it.task.id }
                var rootIndex = 0
                rows.forEach { row ->
                    if (!row.isSubtaskRow) {
                        val index = rootIndex++
                        item(key = "caret-$index") {
                            DropCaret(
                                key = "inbox:caret:$index",
                                target = DropTarget.Between(OrderedList.Tasks(null), index, rootIds),
                            )
                        }
                    }
                    item(key = if (row.isSubtaskRow) "sub-${row.task.id}" else row.task.id) {
                    val task = row.task
                    TaskRow(
                        task = task,
                        projectLabel = null,
                        today = today,
                        onToggle = { onToggle(task) },
                        onClick = { onTaskClick(task) },
                        showProject = false,
                        subtaskProgress = if (row.isSubtaskRow) null else state.subtaskProgress(task.id),
                        attachmentCount = state.attachmentCount(task.id),
                        expanded = task.id in expandedIds,
                        onExpandToggle = if (!row.isSubtaskRow && state.subtaskProgress(task.id) != null) {
                            {
                                expandedIds = if (task.id in expandedIds) {
                                    expandedIds - task.id
                                } else {
                                    expandedIds + task.id
                                }
                            }
                        } else {
                            null
                        },
                        // See the same line in `ProjectDetailScreen`: a reorder settles.
                        modifier = Modifier
                            .animateItem()
                            .then(if (row.isSubtaskRow) Modifier.padding(start = 28.dp) else Modifier),
                    )
                    }
                }
                item(key = "caret-end") {
                    DropCaret(
                        key = "inbox:caret:end",
                        target = DropTarget.Between(OrderedList.Tasks(null), rootIds.size, rootIds),
                    )
                }
            }
        }
    }
}
