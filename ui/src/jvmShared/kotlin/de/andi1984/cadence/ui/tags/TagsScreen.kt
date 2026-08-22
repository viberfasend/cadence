package de.andi1984.cadence.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.domain.model.Tag
import de.andi1984.cadence.ui.CadenceUiState
import de.andi1984.cadence.ui.components.AppIcons
import de.andi1984.cadence.ui.components.EmptyState
import de.andi1984.cadence.ui.components.ScreenHeader
import de.andi1984.cadence.ui.components.TagChip
import de.andi1984.cadence.ui.dnd.DragPayload
import de.andi1984.cadence.ui.dnd.DropCaret
import de.andi1984.cadence.ui.dnd.DropTarget
import de.andi1984.cadence.ui.dnd.OrderedList
import de.andi1984.cadence.ui.dnd.cadenceDragSource
import de.andi1984.cadence.ui.dnd.dragSourceAlpha
import de.andi1984.cadence.ui.resources.Res
import de.andi1984.cadence.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Every tag, with how much work carries it — the place tags are created, renamed, recoloured and
 * deleted.
 *
 * Deliberately not a fifth destination in the bottom bar. Tags are a way of *finding* work, not a
 * place work lives, so this hangs off Projects — the screen that is already about how the library
 * is organised — and the lists people actually work from stay four taps wide.
 */
@Composable
fun TagsScreen(
    state: CadenceUiState,
    onBack: () -> Unit,
    onTagClick: (Tag) -> Unit,
    onCreateTag: (name: String, colorHex: String) -> Unit,
    onEditTag: (Tag, name: String, colorHex: String) -> Unit,
    onDeleteTag: (Tag) -> Unit,
    onReorder: (List<String>) -> Unit,
    /**
     * Whether rows can be dragged into a new order.
     *
     * The one platform flag on this screen, and it exists because the drag kernel needs a host:
     * `DragAndDropHost` wraps the desktop window and nothing on Android, so a drag source there
     * would be a gesture that swallows the list's scroll and then does nothing. Android reorders
     * from the row menu instead, which is the same write either way.
     */
    reorderable: Boolean = false,
) {
    var editing by remember { mutableStateOf<TagDialogState?>(null) }
    var deleting by remember { mutableStateOf<Tag?>(null) }
    val tagIds = state.tags.map { it.id }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(AppIcons.ArrowBack, contentDescription = stringResource(Res.string.action_back))
            }
            ScreenHeader(
                title = stringResource(Res.string.tags_title),
                subtitle = stringResource(Res.string.tags_subtitle),
                modifier = Modifier.weight(1f),
            ) {
                IconButton(onClick = { editing = TagDialogState.Create }) {
                    Icon(AppIcons.Add, contentDescription = stringResource(Res.string.tags_new))
                }
            }
        }

        if (state.tags.isEmpty()) {
            EmptyState(
                title = stringResource(Res.string.tags_empty_title),
                supporting = stringResource(Res.string.tags_empty_body),
            )
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Hand-ordered, like the project tree and a project's bands: `sortOrder` is a column
            // the user writes, and this screen is the only place they can write it.
            itemsIndexed(state.tags, key = { _, tag -> tag.id }) { index, tag ->
                Column {
                    if (reorderable) {
                        DropCaret(
                            key = "tags:$index",
                            target = DropTarget.Between(OrderedList.Tags, index, tagIds),
                        )
                    }
                    TagRow(
                        tag = tag,
                        reorderable = reorderable,
                        // The total, not the open count: this screen is about the label itself,
                        // and a tag whose work is all finished is still a tag someone has to
                        // decide about.
                        taskCount = state.tasksWithTag(tag.id).size,
                        onClick = { onTagClick(tag) },
                        onEdit = { editing = TagDialogState.Edit(tag) },
                        onDelete = { deleting = tag },
                        // Null at the ends, so the menu never offers a move that would do
                        // nothing — the same reason `resolveDrop` refuses a drop onto the gap a
                        // row already sits in.
                        onMoveUp = { onReorder(tagIds.swapping(index, index - 1)) }
                            .takeIf { index > 0 },
                        onMoveDown = { onReorder(tagIds.swapping(index, index + 1)) }
                            .takeIf { index < tagIds.lastIndex },
                    )
                }
            }

            // The gap below the last row, which is the only way to drag a tag to the end.
            if (reorderable) {
                item {
                    DropCaret(
                        key = "tags:end",
                        target = DropTarget.Between(OrderedList.Tags, state.tags.size, tagIds),
                    )
                }
            }
        }
    }

    when (val dialog = editing) {
        null -> Unit
        TagDialogState.Create -> TagEditorDialog(
            tag = null,
            onDismiss = { editing = null },
            onConfirm = onCreateTag,
        )
        is TagDialogState.Edit -> TagEditorDialog(
            tag = dialog.tag,
            onDismiss = { editing = null },
            onConfirm = { name, color -> onEditTag(dialog.tag, name, color) },
        )
    }

    deleting?.let { tag ->
        DeleteTagDialog(
            tag = tag,
            taskCount = state.tasksWithTag(tag.id).size,
            onDismiss = { deleting = null },
            onConfirm = { onDeleteTag(tag) },
        )
    }
}

/** Which editor dialog is open, if any — the same shape `ProjectDialogState` has. */
private sealed interface TagDialogState {
    data object Create : TagDialogState
    data class Edit(val tag: Tag) : TagDialogState
}

@Composable
private fun TagRow(
    tag: Tag,
    taskCount: Int,
    reorderable: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The ghost is the chip rather than the whole row: what is being moved is the label,
            // and the count beside it belongs to the list, not to the thing in your hand.
            .cadenceDragSource(DragPayload.TagDrag(tag).takeIf { reorderable }) { TagChip(tag = tag) }
            .dragSourceAlpha(tag.id)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TagChip(tag = tag)
        Text(
            text = pluralStringResource(Res.plurals.tags_task_count, taskCount, taskCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    AppIcons.MoreVert,
                    contentDescription = stringResource(Res.string.tags_actions, tag.name),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.tags_edit)) },
                    onClick = {
                        menuOpen = false
                        onEdit()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.tags_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
                onMoveUp?.let { move ->
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.tags_move_up)) },
                        onClick = {
                            menuOpen = false
                            move()
                        },
                    )
                }
                onMoveDown?.let { move ->
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.tags_move_down)) },
                        onClick = {
                            menuOpen = false
                            move()
                        },
                    )
                }
            }
        }
    }
}

/** [this] with the entries at [a] and [b] exchanged — one step of the row menu's reordering. */
private fun List<String>.swapping(a: Int, b: Int): List<String> =
    toMutableList().apply {
        val held = this[a]
        this[a] = this[b]
        this[b] = held
    }
