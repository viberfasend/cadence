package de.andi1984.cadence.desktop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The desktop's *workspace* file — where the window was and how wide the sidebar is, kept apart
 * from `settings.json` because none of it means anything on a phone.
 *
 * The cases worth pinning are the ones where the file is not what this build wrote: a hand-edited
 * width, a window position from a monitor that is no longer plugged in, a truncated write. Every
 * one of them is silent, and the failure a user sees is a window they cannot find.
 */
class DesktopWorkspaceStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("cadence-workspace").toFile()

    private fun workspaceFile(dir: File) = File(dir, "workspace.json")

    @Test
    fun `a fresh install starts at the defaults and writes nothing`() {
        val dir = tempDir()

        val workspace = DesktopWorkspaceStore(dir).state.value

        assertEquals(268f, workspace.sidebarWidth, 0.01f)
        assertFalse(workspace.sidebarCollapsed)
        assertTrue(workspace.collapsedProjects.isEmpty())
        assertNull(workspace.windowX)
        assertFalse(workspaceFile(dir).exists())
    }

    @Test
    fun `the sidebar, the folded projects and the window all round-trip`() {
        val dir = tempDir()

        DesktopWorkspaceStore(dir).apply {
            setSidebarWidth(320f)
            setSidebarCollapsed(true)
            toggleProjectCollapsed("home")
            setWindowBounds(width = 1400f, height = 900f, x = 40f, y = 60f, maximized = true)
        }

        val reopened = DesktopWorkspaceStore(dir).state.value
        assertEquals(320f, reopened.sidebarWidth, 0.01f)
        assertTrue(reopened.sidebarCollapsed)
        assertEquals(setOf("home"), reopened.collapsedProjects)
        assertEquals(1400f, reopened.windowWidth, 0.01f)
        assertEquals(40f, reopened.windowX!!, 0.01f)
        assertTrue(reopened.maximized)
    }

    @Test
    fun `folding a project twice unfolds it`() {
        val store = DesktopWorkspaceStore(tempDir())

        store.toggleProjectCollapsed("home")
        store.toggleProjectCollapsed("home")

        assertTrue(store.state.value.collapsedProjects.isEmpty())
    }

    @Test
    fun `a sidebar width outside the range is clamped rather than obeyed`() {
        val store = DesktopWorkspaceStore(tempDir())

        store.setSidebarWidth(4000f)
        assertEquals(420f, store.state.value.sidebarWidth, 0.01f)

        store.setSidebarWidth(10f)
        assertEquals(200f, store.state.value.sidebarWidth, 0.01f)
    }

    @Test
    fun `a window position from a screen that is gone is dropped, not restored`() {
        val dir = tempDir()
        // What a second monitor to the left of the primary one leaves behind once it is unplugged.
        workspaceFile(dir).writeText("""{"windowX":-1800.0,"windowY":-200.0,"windowWidth":1200.0}""")

        val workspace = DesktopWorkspaceStore(dir).state.value

        // Null means "let the platform place it", which is the only safe answer.
        assertNull(workspace.windowX)
        assertNull(workspace.windowY)
        assertEquals(1200f, workspace.windowWidth, 0.01f)
    }

    @Test
    fun `a window smaller than the app can draw is grown back to the minimum`() {
        val dir = tempDir()
        workspaceFile(dir).writeText("""{"windowWidth":80.0,"windowHeight":40.0}""")

        val workspace = DesktopWorkspaceStore(dir).state.value

        assertEquals(640f, workspace.windowWidth, 0.01f)
        assertEquals(480f, workspace.windowHeight, 0.01f)
    }

    @Test
    fun `a truncated or unknown file falls back to the defaults and can still be rewritten`() {
        val dir = tempDir()
        workspaceFile(dir).writeText("""{"sidebarWidth":300.0,"sideba""")

        val store = DesktopWorkspaceStore(dir)
        assertEquals(268f, store.state.value.sidebarWidth, 0.01f)

        store.setSidebarWidth(300f)
        assertEquals(300f, DesktopWorkspaceStore(dir).state.value.sidebarWidth, 0.01f)
    }

    @Test
    fun `a key this version has never heard of is ignored`() {
        val dir = tempDir()
        workspaceFile(dir).writeText("""{"sidebarWidth":300.0,"paneSplit":0.4,"future":true}""")

        assertEquals(300f, DesktopWorkspaceStore(dir).state.value.sidebarWidth, 0.01f)
    }

    @Test
    fun `writing the value that is already stored does not touch the file`() {
        val dir = tempDir()
        val store = DesktopWorkspaceStore(dir)
        store.setSidebarWidth(300f)
        val writtenAt = workspaceFile(dir).lastModified()

        store.setSidebarWidth(300f)

        assertEquals(writtenAt, workspaceFile(dir).lastModified())
    }
}
