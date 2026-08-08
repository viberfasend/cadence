package de.andi1984.cadence.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 8.dp),
    )
}

@Composable
fun DayHeader(title: String, trailing: String?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Outlined or filled pill used for the sort chips and the recurrence options. */
@Composable
fun CadenceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) {
                    Modifier.background(scheme.secondaryContainer)
                } else {
                    Modifier.border(1.dp, scheme.outline, RoundedCornerShape(10.dp))
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selected) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = scheme.onSecondaryContainer,
                modifier = Modifier.size(17.dp),
            )
        } else if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(17.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

/** The four-way importance selector on the task detail screen. */
@Composable
fun SegmentedRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, scheme.outline, RoundedCornerShape(20.dp)),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .background(if (selected) scheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(index) },
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selected) {
                    Icon(
                        imageVector = AppIcons.Check,
                        contentDescription = null,
                        tint = scheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) scheme.onSecondaryContainer else scheme.onSurface,
                )
            }
            if (index != options.lastIndex) {
                Spacer(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(scheme.outline),
                )
            }
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    supporting: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A label that shrinks instead of wrapping.
 *
 * The bottom bar is four fixed columns, so a long translation ("Demnächst" is twice the width of
 * "Today") wrapped onto a second line and clipped. Shrinking keeps the whole word readable and the
 * bar exactly one row tall in every language and at every font scale; below [minScale] the text
 * ellipsises rather than becoming unreadable.
 */
@Composable
fun FittedLabel(
    text: String,
    modifier: Modifier = Modifier,
    minScale: Float = 0.6f,
) {
    val measurer = rememberTextMeasurer()
    val base = LocalTextStyle.current.let {
        if (it.fontSize.isSpecified) it else it.copy(fontSize = 12.sp)
    }

    // The width comes from the slot itself — the bar's own padding makes any outside estimate
    // too generous, and the label ellipsises instead of shrinking.
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val available = constraints.maxWidth
        val fitted = remember(text, base, available, minScale, measurer) {
            var scale = 1f
            var candidate = base
            while (scale > minScale) {
                val width = measurer
                    .measure(AnnotatedString(text), style = candidate, maxLines = 1, softWrap = false)
                    .size.width
                if (width <= available) break
                scale -= 0.05f
                candidate = base.copy(
                    fontSize = base.fontSize * scale,
                    lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * scale else base.lineHeight,
                )
            }
            candidate
        }

        Text(
            text = text,
            style = fitted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** The small square colour swatch that identifies a project. */
@Composable
fun ProjectSwatch(colorHex: String, size: Int = 12, modifier: Modifier = Modifier) {
    Spacer(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 4).coerceAtLeast(2).dp))
            .background(parseColor(colorHex)),
    )
}

fun parseColor(hex: String): Color = runCatching {
    val cleaned = hex.removePrefix("#")
    val value = cleaned.toLong(16)
    if (cleaned.length == 6) Color(value or 0xFF000000L) else Color(value)
}.getOrElse { Color(0xFF006A60) }
