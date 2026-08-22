package de.andi1984.cadence.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Tag

/**
 * A tag, drawn the way tags are drawn everywhere in the app: `@name`, in the tag's own colour,
 * over a tint of it.
 *
 * **The `@` is not decoration.** Colour alone never carries meaning here (the `PrioritySpine`
 * rule), and a tag chip and a project chip are otherwise the same shape in different colours —
 * a distinction that vanishes for anyone who cannot tell teal from slate. The prefix is also
 * exactly what the quick-add line takes, so the chip teaches the syntax.
 */
@Composable
fun TagChip(
    tag: Tag,
    modifier: Modifier = Modifier,
    selected: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val color = parseColor(tag.colorHex)
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) color.copy(alpha = TINT) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) Color.Transparent else scheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(role = Role.Button, onClick = onClick)
                },
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        ProjectSwatch(colorHex = tag.colorHex, size = 7)
        Text(
            text = "@${tag.handle}",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.defaultMinSize(minWidth = 0.dp),
        )
    }
}

/**
 * The tags on a row, wrapped.
 *
 * [max] keeps a task wearing eight labels from pushing everything else off a 52dp compact row; the
 * overflow is counted rather than hidden, because "+3" is the difference between a row that is
 * showing you everything and one that is not.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChipRow(
    tags: List<Tag>,
    modifier: Modifier = Modifier,
    max: Int = Int.MAX_VALUE,
    onTagClick: ((Tag) -> Unit)? = null,
) {
    if (tags.isEmpty()) return
    val shown = tags.take(max)
    val hidden = tags.size - shown.size
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        shown.forEach { tag ->
            TagChip(tag = tag, onClick = onTagClick?.let { click -> { click(tag) } })
        }
        if (hidden > 0) {
            Text(
                text = "+$hidden",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

/** A tag's colour as a small dot, for a list row that already writes the name beside it. */
@Composable
fun TagSwatch(tag: Tag, modifier: Modifier = Modifier) {
    ProjectSwatch(colorHex = tag.colorHex, size = 10, modifier = modifier.size(10.dp))
}

/** How much of a tag's own colour a chip's background carries — enough to read as that tag,
 *  light enough that the label stays legible in both themes. */
private const val TINT = 0.22f
