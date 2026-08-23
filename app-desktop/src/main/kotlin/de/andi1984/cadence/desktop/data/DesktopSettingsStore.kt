package de.andi1984.cadence.desktop.data

import de.andi1984.cadence.desktop.platform.PlatformDirs
import de.andi1984.cadence.ui.settings.CadenceSettings
import de.andi1984.cadence.ui.settings.Density
import de.andi1984.cadence.ui.settings.SettingsStore
import de.andi1984.cadence.ui.settings.SortMode
import de.andi1984.cadence.ui.settings.ThemeChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The on-disk shape, kept separate from [CadenceSettings] so the app's own types never need a
 *  `@Serializable` annotation just to be written to a file. */
@Serializable
private data class SettingsFile(
    val theme: String = ThemeChoice.SYSTEM.name,
    val density: String = Density.COMFORTABLE.name,
    val sortMode: String = SortMode.IMPORTANCE.name,
    val showCompleted: Boolean = true,
    // Off by default on the desktop — see CadenceSettings.remindersEnabled. The default lives in
    // this file rather than in the shared data class precisely because it differs per shell.
    val remindersEnabled: Boolean = false,
)

/**
 * The desktop's answer to [SettingsStore]: a JSON file under [PlatformDirs], read once at
 * startup and rewritten whole on every change — the same handful of scalars as Android's
 * `SharedPrefsSettingsStore`, just with a file instead of `SharedPreferences`.
 *
 * **The setters answer the caller and write the file behind them** (#104). `SettingsStore`'s
 * setters are not `suspend` — `SharedPreferences` needs no such thing — and
 * `CadenceViewModel.setTheme` calls straight through, so a synchronous `writeText` here put a
 * disk write on the Compose UI thread every time someone flipped a switch. The state flow is
 * still updated on the spot, so the screen answers in the same frame; only the file catches up
 * afterwards, on [scope].
 *
 * The write itself is **temp file plus atomic rename**, the same shape `BlobStore` uses: a crash
 * halfway through the old in-place `writeText` left a truncated `settings.json`, which
 * [load]'s `runCatching` then read as "no settings at all" — the user's theme and sort mode gone
 * with no error anywhere.
 */
class DesktopSettingsStore(
    dataDir: File = PlatformDirs.dataDir(),
    /**
     * Where the file write lands. Defaults to a scope of this store's own so a test — or
     * anything else that wants one setting store and nothing else — can construct it with a
     * directory alone; `AppContainer` hands over the process-wide one instead, because a write
     * started as the user quits must not hang off a scope that is already being torn down.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : SettingsStore {

    private val file = File(dataDir, "settings.json")
    private val tmp = File(dataDir, "settings.json.tmp")
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    override val state: StateFlow<CadenceSettings> = _state.asStateFlow()

    /**
     * Holds the writes to one at a time — two of them would otherwise stage through the same
     * temp file and rename each other's half-written bytes into place.
     *
     * **Each one writes whatever the state says when it gets the lock, not the value that
     * started it.** Coroutines launched in order do not reach a lock in order: on a thread pool
     * the newest can win the race and an older one then renames a stale file over it, which is
     * exactly what "set five things and read them back" caught. Writing the current state makes
     * the order stop mattering — every write lands the newest settings, so whichever runs last
     * still leaves the file correct.
     */
    private val writeLock = Mutex()

    /** Parents every write, so [flush] can wait for this store's own jobs without waiting on
     *  everything else sharing [scope] — a sync round, on the container's scope, is not this
     *  store's business to block on. */
    private val writes = SupervisorJob(scope.coroutineContext[Job])

    /**
     * Read on the calling thread, deliberately, and it is the one read that may be.
     *
     * It happens once, while `main()` is building the container and before any window exists, and
     * the first frame has to be drawn in the user's own theme: loading asynchronously would paint
     * the defaults and then swap, which is a flash on every start to save one small file read at
     * a moment when nothing is on screen to be blocked.
     */
    private fun load(): CadenceSettings {
        val onDisk = runCatching {
            if (file.exists()) json.decodeFromString<SettingsFile>(file.readText()) else null
        }.getOrNull() ?: SettingsFile()
        return CadenceSettings(
            theme = ThemeChoice.entries.firstOrNull { it.name == onDisk.theme } ?: ThemeChoice.SYSTEM,
            density = Density.entries.firstOrNull { it.name == onDisk.density } ?: Density.COMFORTABLE,
            sortMode = SortMode.entries.firstOrNull { it.name == onDisk.sortMode } ?: SortMode.IMPORTANCE,
            showCompleted = onDisk.showCompleted,
            remindersEnabled = onDisk.remindersEnabled,
        )
    }

    private fun persist(settings: CadenceSettings) {
        // The screen is answered first and unconditionally: the setting is a property of this
        // process, and a file that fails to write should cost the next start, never this frame.
        _state.value = settings
        scope.launch(writes) {
            writeLock.withLock { write(_state.value) }
        }
    }

    /**
     * Writes somewhere else and then *renames* into place, so `settings.json` is only ever the
     * whole of one version of the settings or the whole of the previous one.
     *
     * `ATOMIC_MOVE` is asked for and not insisted on: it is a guarantee only within one
     * filesystem, and the fallback still beats writing in place, since the rename remains a
     * single operation even where it is not certified atomic.
     */
    private fun write(settings: CadenceSettings) {
        val onDisk = SettingsFile(
            theme = settings.theme.name,
            density = settings.density.name,
            sortMode = settings.sortMode.name,
            showCompleted = settings.showCompleted,
            remindersEnabled = settings.remindersEnabled,
        )
        runCatching {
            tmp.parentFile?.mkdirs()
            tmp.writeText(json.encodeToString(SettingsFile.serializer(), onDisk))
            runCatching {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { tmp.delete() }
    }

    /**
     * Blocks until everything set so far has reached the disk.
     *
     * `main()` calls this on the way out. Writes run on daemon threads, so `exitApplication()`
     * takes any that are still in flight with it — and the toggle a user flipped a second before
     * quitting is exactly the one worth not losing. It is a few hundred bytes, at the one moment
     * where there is nothing left to block.
     */
    fun flush() {
        runBlocking { writes.children.toList().forEach { it.join() } }
    }

    override fun setTheme(theme: ThemeChoice) = persist(_state.value.copy(theme = theme))

    override fun setDensity(density: Density) = persist(_state.value.copy(density = density))

    override fun setSortMode(sortMode: SortMode) = persist(_state.value.copy(sortMode = sortMode))

    override fun setShowCompleted(show: Boolean) = persist(_state.value.copy(showCompleted = show))

    override fun setRemindersEnabled(enabled: Boolean) =
        persist(_state.value.copy(remindersEnabled = enabled))
}
