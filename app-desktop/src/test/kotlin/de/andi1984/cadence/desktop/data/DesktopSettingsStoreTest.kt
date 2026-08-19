package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The desktop's settings file: the same handful of scalars Android keeps in `SharedPreferences`,
 * with a JSON file standing in for it.
 *
 * The cases that matter are the ones where the file is not what this version of the app wrote —
 * a key from a newer build, or a truncated write — because the fallback there is silent and the
 * user notices only that their theme is gone.
 */
class DesktopSettingsStoreTest {

    private fun tempDir(): File = Files.createTempDirectory("cadence-settings").toFile()

    private fun settingsFile(dir: File) = File(dir, "settings.json")

    @Test
    fun `a fresh install starts at the desktop defaults, reminders off`() {
        val store = DesktopSettingsStore(tempDir())

        val state = store.state.value
        assertEquals(ThemeChoice.SYSTEM, state.theme)
        assertEquals(Density.COMFORTABLE, state.density)
        assertEquals(SortMode.IMPORTANCE, state.sortMode)
        assertTrue(state.showCompleted)
        // Off here and on for Android: with the task list shared, both devices would otherwise
        // fire for the same task at the same minute.
        assertFalse(state.remindersEnabled)
    }

    @Test
    fun `nothing is written until something is set`() {
        val dir = tempDir()

        DesktopSettingsStore(dir)

        assertFalse(settingsFile(dir).exists())
    }

    @Test
    fun `every setting round-trips through the file`() {
        val dir = tempDir()

        DesktopSettingsStore(dir).apply {
            setTheme(ThemeChoice.DARK)
            setDensity(Density.COMPACT)
            setSortMode(SortMode.MANUAL)
            setShowCompleted(false)
            setRemindersEnabled(true)
        }

        val reopened = DesktopSettingsStore(dir).state.value
        assertEquals(ThemeChoice.DARK, reopened.theme)
        assertEquals(Density.COMPACT, reopened.density)
        assertEquals(SortMode.MANUAL, reopened.sortMode)
        assertFalse(reopened.showCompleted)
        assertTrue(reopened.remindersEnabled)
    }

    @Test
    fun `setting one value leaves the others where they were`() {
        val dir = tempDir()
        val store = DesktopSettingsStore(dir)

        store.setTheme(ThemeChoice.LIGHT)
        store.setDensity(Density.COMPACT)

        assertEquals(ThemeChoice.LIGHT, store.state.value.theme)
        assertEquals(ThemeChoice.LIGHT, DesktopSettingsStore(dir).state.value.theme)
        assertEquals(Density.COMPACT, DesktopSettingsStore(dir).state.value.density)
    }

    @Test
    fun `the state flow reports the new value straight away`() {
        val store = DesktopSettingsStore(tempDir())

        store.setSortMode(SortMode.DATE)

        assertEquals(SortMode.DATE, store.state.value.sortMode)
    }

    @Test
    fun `a key this version has never heard of is ignored, not fatal`() {
        val dir = tempDir()
        settingsFile(dir).writeText(
            """
            {
              "theme": "DARK",
              "density": "COMPACT",
              "sortMode": "DATE",
              "showCompleted": false,
              "remindersEnabled": true,
              "language": "de",
              "futureFlag": 42
            }
            """.trimIndent(),
        )

        val state = DesktopSettingsStore(dir).state.value

        assertEquals(ThemeChoice.DARK, state.theme)
        assertEquals(Density.COMPACT, state.density)
        assertEquals(SortMode.DATE, state.sortMode)
        assertTrue(state.remindersEnabled)
    }

    @Test
    fun `a value no enum answers to falls back to that one setting's default`() {
        val dir = tempDir()
        settingsFile(dir).writeText("""{"theme":"NEON","density":"COMPACT","sortMode":"DATE"}""")

        val state = DesktopSettingsStore(dir).state.value

        assertEquals(ThemeChoice.SYSTEM, state.theme)
        // The readable keys beside it survive — one bad value does not cost the whole file.
        assertEquals(Density.COMPACT, state.density)
        assertEquals(SortMode.DATE, state.sortMode)
    }

    @Test
    fun `a truncated file falls back to the defaults rather than throwing`() {
        val dir = tempDir()
        // What a crash mid-write leaves behind, since the write is not atomic (see issue #104).
        settingsFile(dir).writeText("""{"theme":"DARK","densi""")

        val state = DesktopSettingsStore(dir).state.value

        assertEquals(ThemeChoice.SYSTEM, state.theme)
        assertEquals(Density.COMFORTABLE, state.density)
        assertFalse(state.remindersEnabled)
    }

    @Test
    fun `a store opened on a corrupt file can still write a good one`() {
        val dir = tempDir()
        settingsFile(dir).writeText("not json at all")

        DesktopSettingsStore(dir).setTheme(ThemeChoice.DARK)

        assertEquals(ThemeChoice.DARK, DesktopSettingsStore(dir).state.value.theme)
    }

    @Test
    fun `an empty file reads as defaults`() {
        val dir = tempDir()
        settingsFile(dir).writeText("")

        assertEquals(ThemeChoice.SYSTEM, DesktopSettingsStore(dir).state.value.theme)
    }
}
