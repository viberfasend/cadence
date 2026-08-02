package de.andi1984.cadence.ui.recurrence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.R
import de.andi1984.cadence.domain.model.MonthlyMode
import de.andi1984.cadence.domain.model.RecurrenceMode
import de.andi1984.cadence.domain.model.RecurrenceRule
import de.andi1984.cadence.domain.model.RecurrenceUnit
import de.andi1984.cadence.domain.recurrence.RecurrenceEngine
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.CadenceChip
import de.andi1984.cadence.ui.components.SegmentedRow
import de.andi1984.cadence.ui.format.formatDateWithYear
import de.andi1984.cadence.ui.format.ordinal
import de.andi1984.cadence.ui.format.unitWord
import de.andi1984.cadence.ui.format.weekdayShort
import java.time.DayOfWeek
import java.time.LocalDate

/** The repeat editor: calendar rules on one side, "after I finish" on the other. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurrenceSheet(
    initial: RecurrenceRule?,
    taskTitle: String,
    anchorDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (RecurrenceRule?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var rule by remember {
        mutableStateOf(
            initial ?: RecurrenceRule(
                mode = RecurrenceMode.SCHEDULE,
                interval = 1,
                unit = RecurrenceUnit.WEEK,
                daysOfWeek = setOf(anchorDate.dayOfWeek),
            ),
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.repeat_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = taskTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 20.dp),
            )

            SegmentedRow(
                options = listOf(
                    stringResource(R.string.repeat_mode_schedule),
                    stringResource(R.string.repeat_mode_after_completion),
                ),
                selectedIndex = if (rule.mode == RecurrenceMode.SCHEDULE) 0 else 1,
                onSelect = { index ->
                    rule = rule.copy(
                        mode = if (index == 0) {
                            RecurrenceMode.SCHEDULE
                        } else {
                            RecurrenceMode.AFTER_COMPLETION
                        },
                    )
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.repeat_every),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownField(
                    value = rule.interval.toString(),
                    options = (1..30).map { it.toString() },
                    onSelect = { rule = rule.copy(interval = it.toInt()) },
                )
                // Resolved up front so the (non-composable) callback can map back to a unit.
                val unitOptions = RecurrenceUnit.entries.map { unitWord(it, rule.interval) }
                DropdownField(
                    value = unitWord(rule.unit, rule.interval),
                    options = unitOptions,
                    modifier = Modifier.weight(1f),
                    fillWidth = true,
                    onSelect = { label ->
                        unitOptions.indexOf(label)
                            .takeIf { it >= 0 }
                            ?.let { rule = rule.copy(unit = RecurrenceUnit.entries[it]) }
                    },
                )
            }

            if (rule.mode == RecurrenceMode.SCHEDULE) {
                when (rule.unit) {
                    RecurrenceUnit.WEEK -> {
                        SheetLabel(stringResource(R.string.repeat_on))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 20.dp),
                        ) {
                            DayOfWeek.entries.forEach { day ->
                                CadenceChip(
                                    label = weekdayShort(day),
                                    selected = day in rule.daysOfWeek,
                                    onClick = {
                                        val days = rule.daysOfWeek.toMutableSet()
                                        if (!days.add(day)) days.remove(day)
                                        rule = rule.copy(daysOfWeek = days)
                                    },
                                )
                            }
                        }
                    }

                    RecurrenceUnit.MONTH, RecurrenceUnit.YEAR -> {
                        SheetLabel(stringResource(R.string.repeat_on))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 20.dp),
                        ) {
                            CadenceChip(
                                label = stringResource(
                                    R.string.repeat_day_of_month,
                                    rule.dayOfMonth ?: anchorDate.dayOfMonth,
                                ),
                                selected = rule.monthlyMode == MonthlyMode.DAY_OF_MONTH,
                                onClick = {
                                    rule = rule.copy(
                                        monthlyMode = MonthlyMode.DAY_OF_MONTH,
                                        dayOfMonth = rule.dayOfMonth ?: anchorDate.dayOfMonth,
                                    )
                                },
                            )
                            CadenceChip(
                                label = stringResource(R.string.repeat_last_day),
                                selected = rule.monthlyMode == MonthlyMode.LAST_DAY,
                                onClick = { rule = rule.copy(monthlyMode = MonthlyMode.LAST_DAY) },
                            )
                            CadenceChip(
                                label = stringResource(R.string.repeat_last_weekday),
                                selected = rule.monthlyMode == MonthlyMode.LAST_WEEKDAY,
                                onClick = {
                                    rule = rule.copy(monthlyMode = MonthlyMode.LAST_WEEKDAY)
                                },
                            )
                            CadenceChip(
                                label = nthWeekdayLabel(rule, anchorDate),
                                selected = rule.monthlyMode == MonthlyMode.NTH_WEEKDAY,
                                leadingIcon = AppIcons.Edit,
                                onClick = {
                                    rule = rule.copy(
                                        monthlyMode = MonthlyMode.NTH_WEEKDAY,
                                        nthWeek = rule.nthWeek
                                            ?: ((anchorDate.dayOfMonth - 1) / 7 + 1),
                                        nthDayOfWeek = rule.nthDayOfWeek ?: anchorDate.dayOfWeek,
                                    )
                                },
                            )
                        }
                    }

                    RecurrenceUnit.DAY -> Unit
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = if (rule.mode == RecurrenceMode.SCHEDULE) {
                        stringResource(R.string.repeat_preview_next_three)
                    } else {
                        stringResource(R.string.repeat_preview_after_completion)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val preview = if (rule.mode == RecurrenceMode.SCHEDULE) {
                    RecurrenceEngine.nextOccurrences(rule, anchorDate, 3)
                } else {
                    listOf(RecurrenceEngine.nextAfter(rule, LocalDate.now()))
                }
                preview.forEach { date ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = AppIcons.Event,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = formatDateWithYear(date),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Switch(
                    checked = rule.keepMissed,
                    onCheckedChange = { rule = rule.copy(keepMissed = it) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.repeat_keep_missed),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.repeat_keep_missed_supporting),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SheetButton(
                    label = if (initial == null) {
                        stringResource(R.string.action_cancel)
                    } else {
                        stringResource(R.string.repeat_turn_off)
                    },
                    filled = false,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (initial == null) onDismiss() else onSave(null)
                    },
                )
                SheetButton(
                    label = stringResource(R.string.repeat_save),
                    filled = true,
                    modifier = Modifier.weight(1f),
                    onClick = { onSave(rule) },
                )
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun DropdownField(
    value: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .height(52.dp)
                .defaultMinSize(minWidth = 72.dp)
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable { open = true }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = if (fillWidth) Modifier.weight(1f) else Modifier,
            )
            Icon(
                imageVector = AppIcons.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (filled) {
                    Modifier.background(scheme.primary)
                } else {
                    Modifier.border(1.dp, scheme.outline, RoundedCornerShape(16.dp))
                },
            )
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) scheme.onPrimary else scheme.onSurface,
            fontWeight = if (filled) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun nthWeekdayLabel(rule: RecurrenceRule, anchorDate: LocalDate): String {
    val nth = rule.nthWeek ?: ((anchorDate.dayOfMonth - 1) / 7 + 1)
    val day = rule.nthDayOfWeek ?: anchorDate.dayOfWeek
    return stringResource(R.string.repeat_nth_weekday, ordinal(nth), weekdayShort(day))
}
