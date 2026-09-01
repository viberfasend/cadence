package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
        // Empty on both shells: a fresh install, or an existing task that already has a due time,
        // must not suddenly start notifying for something nobody configured.
        assertEquals(emptyList<Int>(), state.reminderLeadMinutes)
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
            setReminderLeadMinutes(listOf(20, 10, 5))
            // The setters answer before the file does (#104), so anything reading the file back
            // has to wait for it — which is the same call `main()` makes on the way out.
            flush()
        }

        val reopened = DesktopSettingsStore(dir).state.value
        assertEquals(ThemeChoice.DARK, reopened.theme)
        assertEquals(Density.COMPACT, reopened.density)
        assertEquals(SortMode.MANUAL, reopened.sortMode)
        assertFalse(reopened.showCompleted)
        assertTrue(reopened.remindersEnabled)
        assertEquals(listOf(20, 10, 5), reopened.reminderLeadMinutes)
    }

    @Test
    fun `setting one value leaves the others where they were`() {
        val dir = tempDir()
        val store = DesktopSettingsStore(dir)

        store.setTheme(ThemeChoice.LIGHT)
        store.setDensity(Density.COMPACT)
        store.flush()

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
        // A file from before lead times existed has no such key at all — not even absent-with-
        // null, simply never written — and must still decode rather than refuse the whole file.
        assertEquals(emptyList<Int>(), state.reminderLeadMinutes)
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

        DesktopSettingsStore(dir).apply {
            setTheme(ThemeChoice.DARK)
            flush()
        }

        assertEquals(ThemeChoice.DARK, DesktopSettingsStore(dir).state.value.theme)
    }

    // ── The write is off the caller's thread, and lands whole (#104) ─────────────────

    @Test
    fun `a setter answers the state flow before the file is written`() {
        val dir = tempDir()
        // A dispatcher that runs nothing until told to: whatever the setter does synchronously
        // has happened by the first assert, and whatever it handed off has not.
        val scheduler = TestCoroutineScheduler()
        val store = DesktopSettingsStore(dir, CoroutineScope(StandardTestDispatcher(scheduler)))

        store.setTheme(ThemeChoice.DARK)

        assertEquals(ThemeChoice.DARK, store.state.value.theme)
        assertFalse(settingsFile(dir).exists())

        scheduler.advanceUntilIdle()

        assertTrue(settingsFile(dir).exists())
        assertEquals(ThemeChoice.DARK, DesktopSettingsStore(dir).state.value.theme)
    }

    @Test
    fun `the last value set is the one on disk, whichever write wins the race`() {
        val dir = tempDir()
        // The real dispatcher on purpose: writes launched in order do not reach the lock in
        // order on a thread pool, and a write carrying the value that started it would then let
        // an older one rename a stale file over a newer one. Each write reads the state instead,
        // so the order stops mattering. Caught by the round-trip test above, once.
        val store = DesktopSettingsStore(dir)

        repeat(50) { store.setSortMode(if (it % 2 == 0) SortMode.DATE else SortMode.MANUAL) }
        store.setSortMode(SortMode.IMPORTANCE)
        store.flush()

        assertEquals(SortMode.IMPORTANCE, DesktopSettingsStore(dir).state.value.sortMode)
    }

    @Test
    fun `the temp file the write goes through is not left behind`() {
        val dir = tempDir()

        DesktopSettingsStore(dir).apply {
            setTheme(ThemeChoice.DARK)
            flush()
        }

        // Written elsewhere and renamed into place, so a crash mid-write cannot truncate the
        // real file — and the staging file is gone once it has.
        assertFalse(File(dir, "settings.json.tmp").exists())
        assertTrue(settingsFile(dir).exists())
    }

    @Test
    fun `an empty file reads as defaults`() {
        val dir = tempDir()
        settingsFile(dir).writeText("")

        assertEquals(ThemeChoice.SYSTEM, DesktopSettingsStore(dir).state.value.theme)
    }
}
