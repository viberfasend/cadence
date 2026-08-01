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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ScreenHeader
import de.andi1984.cadence.ui.components.TaskRow
import de.andi1984.cadence.ui.sortedFor
import java.time.LocalDate

@Composable
fun InboxScreen(
    state: CadenceUiState,
    today: LocalDate,
    onTaskClick: (Task) -> Unit,
    onToggle: (Task) -> Unit,
    onTriage: () -> Unit,
) {
    val inbox = state.tasks
        .filter { it.isInbox && (state.settings.showCompleted || !it.isDone) }
        .sortedFor(state.settings.sortMode)
    val open = inbox.count { !it.isDone }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Inbox",
            subtitle = if (open == 0) "Empty" else "$open to sort out",
        ) {
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
                        text = "Triage",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }

        if (inbox.isEmpty()) {
            EmptyState(
                title = "Inbox is clear",
                supporting = "Anything you capture without a project waits here.",
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(inbox, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    projectLabel = null,
                    today = today,
                    onToggle = { onToggle(task) },
                    onClick = { onTaskClick(task) },
                    showProject = false,
                )
            }
        }
    }
}
