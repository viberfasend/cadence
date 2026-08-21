package de.andi1984.cadence.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import de.andi1984.cadence.domain.model.Priority
import de.andi1984.cadence.domain.model.Task
import de.andi1984.cadence.ui.CadenceUiState
import java.time.LocalDate

/**
 * What a row can do beyond being tapped — supplied by the shell, read by the row.
 *
 * Right-click menus and drag-and-drop are things the *desktop* shell wants on every list, and
 * threading a dozen callbacks through seven screens to reach `TaskRow` would change every
 * signature in the module for a feature only one shell uses. A composition local instead: the
 * desktop wraps its content in [ProvideRowInteractions] and every row in every screen — including
 * ones written later — picks the behaviour up. `:app-android` provides nothing, [enabled] stays
 * false, and its screens are byte-for-byte the rows they were.
 *
 * The callbacks are the ViewModel's own methods; nothing here is a second implementation of
 * anything. [state] and [today] are here because the menus need them to build their submenus
 * (which projects exist, what "tomorrow" means) and a row is not otherwise given either.
 */
@Immutable
data class RowInteractions(
    /** False disables the menu and the drag gesture entirely — the default, and Android's. */
    val enabled: Boolean = false,
    val state: CadenceUiState = CadenceUiState(),
    val today: LocalDate = LocalDate.EPOCH,
    val onOpenTask: (Task) -> Unit = {},
    val onToggleTask: (Task) -> Unit = {},
    val onSetPriority: (Task, Priority) -> Unit = { _, _ -> },
    val onSetDueDate: (Task, LocalDate?) -> Unit = { _, _ -> },
    val onMoveTaskToProject: (Task, String?) -> Unit = { _, _ -> },
    val onMoveTaskToSection: (Task, String?) -> Unit = { _, _ -> },
    val onDuplicateTask: (Task) -> Unit = {},
    val onDeleteTask: (Task) -> Unit = {},
)

val LocalRowInteractions = staticCompositionLocalOf { RowInteractions() }

/** Wraps a shell's content so every row inside it gains its menu and its drag gesture. */
@Composable
fun ProvideRowInteractions(interactions: RowInteractions, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalRowInteractions provides interactions,
        content = content,
    )
}
