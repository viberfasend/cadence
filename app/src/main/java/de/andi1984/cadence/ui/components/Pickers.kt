package de.andi1984.cadence.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.Project
import de.andi1984.cadence.domain.model.projectPath
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadenceDatePickerDialog(
    initial: LocalDate?,
    onDismiss: () -> Unit,
    onPick: (LocalDate?) -> Unit,
) {
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initial
            ?.atStartOfDay(ZoneOffset.UTC)
            ?.toInstant()
            ?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val picked = pickerState.selectedDateMillis?.let { millis ->
                        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    onPick(picked)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_set)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onPick(null)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.picker_no_date)) }
        },
    ) {
        DatePicker(state = pickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CadenceTimePickerDialog(
    initial: LocalTime?,
    title: String,
    onDismiss: () -> Unit,
    onPick: (LocalTime?) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial?.hour ?: 9,
        initialMinute = initial?.minute ?: 0,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TimePicker(state = pickerState)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onPick(LocalTime.of(pickerState.hour, pickerState.minute))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_set)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onPick(null)
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_clear)) }
        },
    )
}

/** Picks the project a task is filed under, Inbox included. */
@Composable
fun ProjectPickerDialog(
    projects: List<Project>,
    selectedId: Long?,
    onDismiss: () -> Unit,
    onPick: (Long?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_move_to)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ProjectOption(
                    label = stringResource(R.string.inbox_title),
                    colorHex = null,
                    selected = selectedId == null,
                    onClick = {
                        onPick(null)
                        onDismiss()
                    },
                )
                projects.sortedBy { projectPath(it, projects) }.forEach { project ->
                    ProjectOption(
                        label = projectPath(project, projects).orEmpty(),
                        colorHex = project.colorHex,
                        selected = project.id == selectedId,
                        onClick = {
                            onPick(project.id)
                            onDismiss()
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ProjectOption(
    label: String,
    colorHex: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (colorHex != null) {
            ProjectSwatch(colorHex = colorHex, size = 10)
        } else {
            ProjectSwatch(colorHex = "#6F7976", size = 10)
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}
