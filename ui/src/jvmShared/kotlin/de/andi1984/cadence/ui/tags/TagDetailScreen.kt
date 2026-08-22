package de.andi1984.cadence.ui.tags

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.TaskView
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ScreenHeader
import de.andi1984.cadence.ui.components.TaskRow
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.ui.taskList
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import java.time.LocalDate

/**
 * Everything carrying one tag, in one flat list.
 *
 * No bands, unlike a project's screen: a tag cuts across projects, so there is no heading
 * structure to borrow. Rows keep their project label for exactly that reason — "which project is
 * this in" is the question this list raises, so it always answers it.
 *
 * The row's own tag chips are left out ([TaskRow]'s `tags` is not passed): every row here wears
 * the tag in the header, so drawing it on all of them says nothing. A task with *other* labels
 * still shows them.
 */
@Composable
fun TagDetailScreen(
    tagId: String?,
    state: CadenceUiState,
    today: LocalDate,
    onBack: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggle: (Task) -> Unit,
) {
    val tag = state.tag(tagId)
    if (tag == null) {
        Column(modifier = Modifier.fillMaxSize()) {
            BackRow(onBack)
            EmptyState(
                title = stringResource(Res.string.tags_title),
                supporting = stringResource(Res.string.deleted_supporting),
            )
        }
        return
    }

    val rows = state.taskList(TaskView.Tag(tag.id), today).rows

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back))
            }
            ScreenHeader(
                title = "@${tag.handle}",
                subtitle = pluralStringResource(
                    Res.plurals.tags_task_count,
                    rows.size,
                    rows.size,
                ),
                modifier = Modifier.weight(1f),
            )
        }

        if (rows.isEmpty()) {
            EmptyState(
                title = "@${tag.handle}",
                supporting = stringResource(Res.string.tags_task_empty),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(rows, key = { it.task.id }) { row ->
                val task = row.task
                TaskRow(
                    task = task,
                    projectLabel = state.projectLabel(task),
                    today = today,
                    onToggle = { onToggle(task) },
                    onClick = { onTaskClick(task) },
                    parentTitle = state.parentOf(task)?.title,
                    subtaskProgress = state.subtaskProgress(task.id),
                    // Every other label the task carries, but not this list's own.
                    tags = state.tagsOf(task).filter { it.id != tag.id },
                )
            }
        }
    }
}

@Composable
private fun BackRow(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back))
        }
    }
}
