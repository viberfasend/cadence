package de.andi1984.cadence.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.DayHeader
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ScreenHeader
import de.andi1984.cadence.ui.components.TaskRow
import de.andi1984.cadence.ui.format.currentLocale
import de.andi1984.cadence.ui.format.dayHeader
import de.andi1984.cadence.ui.format.formatDate
import de.andi1984.cadence.ui.format.pluralTasks
import de.andi1984.cadence.ui.sortedFor
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle

private sealed interface AgendaItem {
    data class Header(val date: LocalDate, val count: Int) : AgendaItem

    data class Entry(val task: Task) : AgendaItem
}

@Composable
fun UpcomingScreen(
    state: CadenceUiState,
    today: LocalDate,
    onTaskClick: (Task) -> Unit,
    onToggle: (Task) -> Unit,
) {
    val upcoming = state.tasks
        .filter { task -> !task.isDone && task.dueDate?.isAfter(today) == true }
        .sortedFor(state.settings.sortMode)
    val byDay = upcoming.groupBy { it.dueDate!! }.toSortedMap()

    val agenda = buildList {
        byDay.forEach { (date, tasks) ->
            add(AgendaItem.Header(date, tasks.size))
            tasks.forEach { add(AgendaItem.Entry(it)) }
        }
    }
    val headerIndex = agenda
        .mapIndexedNotNull { index, item -> (item as? AgendaItem.Header)?.let { it.date to index } }
        .toMap()

    val weekDays = (1L..7L).map { today.plusDays(it) }
    val nextWeekCount = upcoming.count { !it.dueDate!!.isAfter(today.plusDays(7)) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.upcoming_title),
            subtitle = stringResource(R.string.upcoming_subtitle, pluralTasks(nextWeekCount)),
        )

        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            weekDays.forEach { date ->
                DayChip(
                    date = date,
                    hasTasks = byDay.containsKey(date),
                    highlighted = date == today.plusDays(1),
                    onClick = {
                        headerIndex[date]?.let { index ->
                            scope.launch { listState.animateScrollToItem(index) }
                        }
                    },
                )
            }
        }

        if (agenda.isEmpty()) {
            EmptyState(
                title = stringResource(R.string.upcoming_empty_title),
                supporting = stringResource(R.string.upcoming_empty_supporting),
            )
            return@Column
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(
                count = agenda.size,
                key = { index ->
                    when (val item = agenda[index]) {
                        is AgendaItem.Header -> "h-${item.date}"
                        is AgendaItem.Entry -> "t-${item.task.id}"
                    }
                },
            ) { index ->
                when (val item = agenda[index]) {
                    // "Tomorrow" is the one header that does not already say its date.
                    is AgendaItem.Header -> DayHeader(
                        title = dayHeader(item.date, today),
                        trailing = if (item.date == today.plusDays(1)) {
                            "${formatDate(item.date)} · ${item.count}"
                        } else {
                            "${item.count}"
                        },
                    )

                    is AgendaItem.Entry -> TaskRow(
                        task = item.task,
                        projectLabel = state.projectLabel(item.task),
                        today = today,
                        onToggle = { onToggle(item.task) },
                        onClick = { onTaskClick(item.task) },
                        parentTitle = state.parentOf(item.task)?.title,
                        subtaskProgress = state.subtaskProgress(item.task.id),
                    )
                }
            }
        }
    }
}

@Composable
private fun DayChip(
    date: LocalDate,
    hasTasks: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .width(44.dp)
            .height(64.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (highlighted) {
                    Modifier.background(scheme.primary)
                } else {
                    Modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
                },
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = date.dayOfWeek
                .getDisplayName(TextStyle.SHORT, currentLocale())
                .uppercase(currentLocale()),
            style = MaterialTheme.typography.labelSmall,
            color = if (highlighted) scheme.onPrimary else scheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = date.dayOfMonth.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = if (highlighted) scheme.onPrimary else scheme.onSurface,
        )
        Spacer(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !hasTasks -> androidx.compose.ui.graphics.Color.Transparent
                        highlighted -> scheme.onPrimary
                        else -> scheme.primary
                    },
                ),
        )
    }
}
