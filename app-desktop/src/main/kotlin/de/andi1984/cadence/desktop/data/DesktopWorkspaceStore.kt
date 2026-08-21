package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.desktop.platform.PlatformDirs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Where the desktop window and its chrome were when it was last closed.
 *
 * Deliberately *not* part of `CadenceSettings`. That is the shared, ported settings object every
 * screen reads and both shells implement; none of what is here means anything on a phone, and
 * adding "how wide is the sidebar" to it would make Android's `SharedPrefsSettingsStore` carry a
 * field it can never set. This is a second, desktop-only file next to `settings.json`.
 *
 * Nothing here is synced, for the same reason nothing in `CadenceSettings` is: a window position
 * describes a machine, not a task list (ADR 0001, decision 9).
 */
@Serializable
data class DesktopWorkspace(
    /** Sidebar width in dp, clamped to [MIN_SIDEBAR_WIDTH]…[MAX_SIDEBAR_WIDTH] when read. */
    val sidebarWidth: Float = 268f,
    /** Collapsed to an icon rail (`Ctrl`/`Cmd`+B). */
    val sidebarCollapsed: Boolean = false,
    /** Projects whose subprojects are folded away in the sidebar. Collapsed rather than expanded
     *  ids, so a brand-new project shows its children without anyone having to record that. */
    val collapsedProjects: Set<String> = emptySet(),
    val windowWidth: Float = 1180f,
    val windowHeight: Float = 820f,
    /** Null until the window has been moved — the first launch is centred by the platform. */
    val windowX: Float? = null,
    val windowY: Float? = null,
    val maximized: Boolean = false,
) {
    companion object {
        const val MIN_SIDEBAR_WIDTH = 200f
        const val MAX_SIDEBAR_WIDTH = 420f
        const val MIN_WINDOW_WIDTH = 640f
        const val MIN_WINDOW_HEIGHT = 480f
    }
}

/**
 * Reads the workspace file once and rewrites it whole on change, the same shape
 * [DesktopSettingsStore] uses.
 *
 * Every read is clamped rather than trusted: the file is editable by hand, a monitor can be
 * unplugged between two runs, and a window restored 3000px to the right of a laptop screen is a
 * window nobody can find. A file that fails to parse at all falls back to the defaults — losing a
 * sidebar width is not worth refusing to start over.
 */
class DesktopWorkspaceStore(dataDir: File = PlatformDirs.dataDir()) {

    private val file = File(dataDir, "workspace.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(clamp(load()))
    val state: StateFlow<DesktopWorkspace> = _state.asStateFlow()

    private fun load(): DesktopWorkspace = runCatching {
        if (file.exists()) json.decodeFromString<DesktopWorkspace>(file.readText()) else null
    }.getOrNull() ?: DesktopWorkspace()

    /** Applies [change] and writes the result. Failing to write is not worth a crash: the app
     *  works perfectly with a stale workspace file, and there is nothing to tell the user. */
    fun update(change: (DesktopWorkspace) -> DesktopWorkspace) {
        val next = clamp(change(_state.value))
        if (next == _state.value) return
        _state.value = next
        runCatching { file.writeText(json.encodeToString(DesktopWorkspace.serializer(), next)) }
    }

    fun setSidebarWidth(width: Float) = update { it.copy(sidebarWidth = width) }

    fun setSidebarCollapsed(collapsed: Boolean) = update { it.copy(sidebarCollapsed = collapsed) }

    fun toggleProjectCollapsed(projectId: String) = update { workspace ->
        val collapsed = workspace.collapsedProjects
        workspace.copy(
            collapsedProjects = if (projectId in collapsed) collapsed - projectId else collapsed + projectId,
        )
    }

    fun setWindowBounds(width: Float, height: Float, x: Float?, y: Float?, maximized: Boolean) =
        update {
            it.copy(
                windowWidth = width,
                windowHeight = height,
                windowX = x,
                windowY = y,
                maximized = maximized,
            )
        }

    private fun clamp(workspace: DesktopWorkspace): DesktopWorkspace = workspace.copy(
        sidebarWidth = workspace.sidebarWidth.coerceIn(
            DesktopWorkspace.MIN_SIDEBAR_WIDTH,
            DesktopWorkspace.MAX_SIDEBAR_WIDTH,
        ),
        windowWidth = workspace.windowWidth.coerceAtLeast(DesktopWorkspace.MIN_WINDOW_WIDTH),
        windowHeight = workspace.windowHeight.coerceAtLeast(DesktopWorkspace.MIN_WINDOW_HEIGHT),
        // A negative coordinate is how a window ends up off the top-left of every screen; the
        // platform centres a window whose position is unset, which is the better failure.
        windowX = workspace.windowX?.takeIf { it >= 0f },
        windowY = workspace.windowY?.takeIf { it >= 0f },
    )
}
