package de.andi1984.cadence.ui.format

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import de.andi1984.cadence.domain.model.Priority

/**
 * The spoken side of a priority. [Priority.shortLabel] ("P2") is language-neutral and stays on
 * the model; everything with words in it lives here.
 */

/** "Critical", "High", "Normal", "Low". */
@Composable
fun Priority.title(): String = stringResource(
    when (this) {
        Priority.P1 -> Res.string.priority_p1_title
        Priority.P2 -> Res.string.priority_p2_title
        Priority.P3 -> Res.string.priority_p3_title
        Priority.P4 -> Res.string.priority_p4_title
    },
)

/** "P2 · High" — the pairing screen readers announce, so colour never carries the meaning. */
@Composable
fun Priority.label(): String = stringResource(Res.string.priority_label, shortLabel, title())

/** The sentence under the segmented control on the task detail screen. */
@Composable
fun Priority.explanation(): String = stringResource(
    when (this) {
        Priority.P1 -> Res.string.priority_p1_explanation
        Priority.P2 -> Res.string.priority_p2_explanation
        Priority.P3 -> Res.string.priority_p3_explanation
        Priority.P4 -> Res.string.priority_p4_explanation
    },
)
