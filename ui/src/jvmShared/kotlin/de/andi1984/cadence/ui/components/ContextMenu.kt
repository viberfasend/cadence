package de.andi1984.cadence.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * Right-click menus, in shared code.
 *
 * `ContextMenuArea` is a desktop-only API, but everything it is built out of is common:
 * `PointerEvent.buttons.isSecondaryPressed` is common, and Material 3's `DropdownMenu` anchors
 * wherever it is told to. So this lives in `:ui` beside every other component, fires on the
 * desktop, and is simply inert on a touchscreen that has no secondary button — Android keeps its
 * long-press sheets and loses nothing.
 */

/**
 * Calls [onSecondaryClick] with the press position, in this composable's own coordinates.
 *
 * The event is watched in [PointerEventPass.Initial] so a row's `clickable` underneath never sees
 * it: a secondary press that also selected the row would make every right-click a navigation.
 */
fun Modifier.secondaryClickable(
    enabled: Boolean = true,
    onSecondaryClick: (Offset) -> Unit,
): Modifier = if (!enabled) this else pointerInput(onSecondaryClick) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val isSecondaryPress = event.type == PointerEventType.Press &&
                event.buttons.isSecondaryPressed
            if (isSecondaryPress) {
                event.changes.forEach { it.consume() }
                onSecondaryClick(event.changes.first().position)
            }
        }
    }
}

/**
 * Wraps [content] in a right-click menu.
 *
 * [menu] is handed a `dismiss` so an item can close the menu itself — which every item that does
 * something should, and an item that opens a submenu page must not.
 */
@Composable
fun CadenceContextMenu(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menu: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var at by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current

    Box(
        modifier = modifier.secondaryClickable(enabled) { position ->
            at = with(density) { DpOffset(position.x.toDp(), position.y.toDp()) }
            open = true
        },
    ) {
        content()
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            offset = at,
        ) {
            menu { open = false }
        }
    }
}

/** One row of a context menu. [trailing] carries a shortcut hint or a submenu chevron. */
@Composable
fun CadenceMenuItem(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    trailing: String? = null,
) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = trailing?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** A menu item that opens a page of the same menu rather than doing something. */
@Composable
fun CadenceSubmenuItem(text: String, icon: ImageVector? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        trailingIcon = { Icon(AppIcons.ChevronRight, contentDescription = null) },
    )
}

/** The header of a submenu page: where you are, and the way back. */
@Composable
fun CadenceMenuBack(text: String, onBack: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.labelLarge) },
        onClick = onBack,
        leadingIcon = { Icon(AppIcons.ArrowBack, contentDescription = null) },
    )
    HorizontalDivider()
}
