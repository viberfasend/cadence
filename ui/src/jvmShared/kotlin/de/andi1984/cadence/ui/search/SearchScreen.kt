package de.andi1984.cadence.ui.search

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import de.andi1984.cadence.ui.components.TaskRow
import java.time.LocalDate

@Composable
fun SearchScreen(
    state: CadenceUiState,
    today: LocalDate,
    onBack: () -> Unit,
    onTaskClick: (Task) -> Unit,
    onToggle: (Task) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val results = state.taskList(TaskView.Search(query), today).tasks

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back))
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(Res.string.search_placeholder)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }

        if (query.isBlank()) {
            EmptyState(
                title = stringResource(Res.string.search_title),
                supporting = stringResource(Res.string.search_supporting),
            )
            return@Column
        }
        if (results.isEmpty()) {
            EmptyState(
                title = stringResource(Res.string.search_no_matches_title),
                supporting = stringResource(Res.string.search_no_matches_supporting, query),
            )
            return@Column
        }

        Text(
            text = pluralStringResource(Res.plurals.search_results, results.size, results.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(results, key = { it.id }) { task ->
                val parentTask = state.parentOf(task)
                TaskRow(
                    task = task,
                    projectLabel = state.projectLabel(task),
                    today = today,
                    onToggle = { onToggle(task) },
                    onClick = { onTaskClick(task) },
                    parentTitle = parentTask?.title,
                    subtaskProgress = state.subtaskProgress(task.id),
                    // For subtasks in search, show the parent context more prominently
                    showProject = parentTask != null, // Always show project for subtasks
                )
            }
        }
    }
}
