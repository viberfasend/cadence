package de.andi1984.cadence.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.ui.format.label
import de.andi1984.cadence.ui.theme.LocalCadenceColors

@Composable
fun priorityColor(priority: Priority): Color {
    val colors = LocalCadenceColors.current
    return when (priority) {
        Priority.P1 -> colors.priorityCritical
        Priority.P2 -> colors.priorityHigh
        Priority.P3 -> colors.priorityNormal
        Priority.P4 -> MaterialTheme.colorScheme.outline
    }
}

/**
 * The segmented importance spine. Always rendered next to a text label — colour alone never
 * carries the meaning.
 */
@Composable
fun PrioritySpine(
    priority: Priority,
    modifier: Modifier = Modifier,
    barHeight: Int = 12,
    overrideColor: Color? = null,
    overrideTrack: Color? = null,
) {
    val filled = overrideColor ?: priorityColor(priority)
    val track = overrideTrack ?: LocalCadenceColors.current.priorityTrack

    if (priority == Priority.P4) {
        Spacer(
            modifier = modifier
                .size(width = 11.dp, height = 3.dp)
                .background(filled, RoundedCornerShape(2.dp)),
        )
        return
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(1.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            Spacer(
                modifier = Modifier
                    .size(width = 3.dp, height = barHeight.dp)
                    .background(
                        color = if (index < priority.filledBars) filled else track,
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

/** Spine plus the "P2" text, the pairing used on every row. */
@Composable
fun PriorityBadge(
    priority: Priority,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    spineColor: Color? = null,
    trackColor: Color? = null,
) {
    val spoken = priority.label()
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrioritySpine(
            priority = priority,
            overrideColor = spineColor,
            overrideTrack = trackColor,
        )
        Text(
            text = priority.shortLabel,
            style = MaterialTheme.typography.bodySmall,
            color = labelColor,
        )
    }
}
