package de.andi1984.cadence.ui.dnd

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import de.andi1984.cadence.ui.CadenceUiState
import kotlinx.coroutines.delay

/**
 * The gesture half of the drag kernel: what is being dragged, where the pointer is, which zones
 * are on screen, and the ghost that follows the cursor. Every *rule* lives next door in
 * `DragModel.kt`; nothing here decides whether a drop is allowed.
 *
 * Coordinates are **root** coordinates throughout — a source converts its local drag position with
 * `localToRoot`, a zone registers `boundsInRoot()`, and the overlay draws in the root's space. One
 * space, no conversions at the comparison.
 */
@Stable
class DragAndDropState internal constructor() {

    /** What is currently being dragged, or null. Reading this is how a row dims itself. */
    var payload: DragPayload? by mutableStateOf(null)
        private set

    /** Pointer position in root coordinates. */
    var pointer: Offset by mutableStateOf(Offset.Zero)
        private set

    internal var ghost: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    private val zones = mutableStateMapOf<Any, DropZone>()

    /** The zone the pointer is over that would do something, with the intent it would run. */
    internal var hovered: Pair<DropZone, DropIntent>? by mutableStateOf(null)
        private set

    /** Whether [key]'s zone is the one a drop would land in — a row reads this to highlight. */
    fun isHovered(key: Any): Boolean = hovered?.first?.key == key

    /** True while [id]'s own row is the thing being dragged. */
    fun isDragging(id: String): Boolean = payload?.id == id

    internal fun register(key: Any, bounds: Rect, target: DropTarget) {
        zones[key] = DropZone(key, bounds, target)
    }

    internal fun unregister(key: Any) {
        zones.remove(key)
    }

    internal fun start(payload: DragPayload, ghost: @Composable () -> Unit, at: Offset) {
        this.payload = payload
        this.ghost = ghost
        pointer = at
        hovered = null
    }

    internal fun moveBy(delta: Offset, hitTest: (Offset, DragPayload) -> Pair<DropZone, DropIntent>?) {
        val current = payload ?: return
        pointer += delta
        hovered = hitTest(pointer, current)
    }

    /** Ends the drag and returns what it resolved to, or null if it landed nowhere. */
    internal fun finish(): DropIntent? {
        val intent = hovered?.second
        cancel()
        return intent
    }

    internal fun cancel() {
        payload = null
        ghost = null
        hovered = null
    }

    /** The zones on screen, for the auto-scroll and for tests of the host. */
    internal fun zones(): Collection<DropZone> = zones.values
}

/**
 * The drag state of the composition.
 *
 * The default is a real but *detached* state: no host means no zones are ever registered, so a
 * drag can start and simply never resolve to anything. That is deliberate — `:app-android` draws
 * the same screens without a [DragAndDropHost], and a `CompositionLocal` that threw would make a
 * shared screen refuse to compose on the platform that does not use the feature.
 */
val LocalDragAndDrop = staticCompositionLocalOf { DragAndDropState() }

/**
 * Wraps the app once: owns the drag state, resolves every drop against [state], hands the result
 * to [onIntent], and draws the ghost above everything.
 *
 * The host is deliberately the *only* thing that runs an intent. A screen registers a zone and
 * says nothing about what dropping there does; a shell decides how an intent reaches the
 * ViewModel. That is what keeps the same drag working in a sidebar the `:ui` module has never
 * heard of.
 */
@Composable
fun DragAndDropHost(
    state: CadenceUiState,
    onIntent: (DropIntent) -> Unit,
    content: @Composable () -> Unit,
) {
    val dragState = remember { DragAndDropState() }
    val latestState = rememberUpdatedState(state)
    val latestOnIntent = rememberUpdatedState(onIntent)

    CompositionLocalProvider(
        LocalDragAndDrop provides dragState,
        LocalDropDispatcher provides remember {
            DropDispatcher(
                hitTest = { position, payload ->
                    hitTest(dragState.zones(), position, payload, latestState.value)
                },
                run = { intent -> latestOnIntent.value(intent) },
            )
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            DragGhost(dragState)
        }
    }
}

/** The two things a drag source needs from the host, so the source itself stays a modifier. */
internal class DropDispatcher(
    val hitTest: (Offset, DragPayload) -> Pair<DropZone, DropIntent>?,
    val run: (DropIntent) -> Unit,
)

/** No host, nothing to hit and nothing to run — see [LocalDragAndDrop]. */
internal val LocalDropDispatcher = staticCompositionLocalOf {
    DropDispatcher(hitTest = { _, _ -> null }, run = {})
}

/**
 * Makes this composable draggable.
 *
 * [payload] null means "not draggable right now", which is how a list turns the gesture off
 * without changing its layout. The gesture is a plain drag with Compose's touch slop rather than a
 * long press: this is a pointer-first shell, and a mouse that has to be held down for half a
 * second to move a row feels broken. A click still clicks — the slop is what separates them.
 */
@Composable
fun Modifier.cadenceDragSource(
    payload: DragPayload?,
    ghost: @Composable () -> Unit,
): Modifier {
    val drag = LocalDragAndDrop.current
    val dispatcher = LocalDropDispatcher.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val latestGhost = rememberUpdatedState(ghost)

    return this
        .onGloballyPositioned { origin = it.boundsInRoot().topLeft }
        .pointerInput(payload) {
            if (payload == null) return@pointerInput
            detectDragGestures(
                onDragStart = { local -> drag.start(payload, { latestGhost.value() }, origin + local) },
                onDrag = { change, amount ->
                    change.consume()
                    drag.moveBy(amount, dispatcher.hitTest)
                },
                onDragEnd = { drag.finish()?.let(dispatcher.run) },
                onDragCancel = { drag.cancel() },
            )
        }
}

/**
 * Registers this composable's bounds as somewhere a drag can land.
 *
 * [key] identifies the zone across recompositions and is what [DragAndDropState.isHovered] is
 * asked about. The registration is dropped when the composable leaves, so a scrolled-away row
 * cannot answer for a position it no longer occupies.
 */
@Composable
fun Modifier.cadenceDropTarget(key: Any, target: DropTarget): Modifier {
    val drag = LocalDragAndDrop.current
    val latestTarget = rememberUpdatedState(target)

    DisposableEffect(key) {
        onDispose { drag.unregister(key) }
    }

    return this.onGloballyPositioned { drag.register(key, it.boundsInRoot(), latestTarget.value) }
}

/** The standard highlight for a zone a drop would land in — one look, every list. */
@Composable
fun Modifier.dropHighlight(key: Any, shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    val drag = LocalDragAndDrop.current
    val hovered = drag.isHovered(key)
    val strength by animateFloatAsState(if (hovered) 1f else 0f, label = "dropHighlight")
    if (strength == 0f) return this
    return this
        .clip(shape)
        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f * strength))
}

/** Dims the row that is currently in the air, so the ghost is the copy that reads as real. */
@Composable
fun Modifier.dragSourceAlpha(id: String): Modifier {
    val drag = LocalDragAndDrop.current
    return if (drag.isDragging(id)) this.alpha(0.35f) else this
}

/**
 * The caret drawn in the gap a row would land in.
 *
 * Put one between every pair of rows in a hand-ordered list; it is 2dp of nothing until a drag
 * hovers its zone, so it costs no layout when nobody is dragging.
 */
@Composable
fun DropCaret(key: Any, target: DropTarget, modifier: Modifier = Modifier) {
    val drag = LocalDragAndDrop.current
    val dragging = drag.payload != null
    val hovered = drag.isHovered(key)
    val thickness by animateFloatAsState(if (hovered) 3f else 0f, label = "dropCaret")

    // The gap only exists while something is in the air: a list at rest must not carry 8dp of
    // nothing between every pair of rows.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(if (dragging) 10.dp else 2.dp)
            .cadenceDropTarget(key, target),
        contentAlignment = Alignment.Center,
    ) {
        if (thickness > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(thickness.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Scrolls a list while a drag hovers near its edge.
 *
 * Without this a list taller than the window can only be reordered as far as one screen: there is
 * no way to reach a row that is not drawn, and the pointer is already held down. 48dp of margin
 * and a fixed step per frame — a speed that scales with how far past the edge the pointer is reads
 * as the list snatching itself away.
 */
@Composable
fun Modifier.dragAutoScroll(listState: LazyListState): Modifier {
    val drag = LocalDragAndDrop.current
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val density = LocalDensity.current
    val margin = with(density) { 48.dp.toPx() }
    val step = with(density) { 12.dp.toPx() }
    val active by remember { derivedStateOf { drag.payload != null } }

    LaunchedEffect(active, bounds) {
        val area = bounds ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        while (true) {
            val y = drag.pointer.y
            val delta = when {
                y < area.top + margin -> -step
                y > area.bottom - margin -> step
                else -> 0f
            }
            if (delta != 0f) listState.scrollBy(delta)
            // One step per frame is what a scroll *is*; 16ms is the frame this is standing in for.
            delay(16)
        }
    }

    return this.onGloballyPositioned { bounds = it.boundsInRoot() }
}

/** The dragged row, drawn under the cursor above everything else. */
@Composable
private fun DragGhost(state: DragAndDropState) {
    val ghost = state.ghost ?: return
    val density = LocalDensity.current
    val nudge = with(density) { 12.dp.toPx() }

    Box(
        modifier = Modifier
            .graphicsLayer {
                translationX = state.pointer.x + nudge
                translationY = state.pointer.y + nudge
                alpha = 0.92f
                shadowElevation = with(density) { 12.dp.toPx() }
            }
            .widthIn(max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        ghost()
    }
}

