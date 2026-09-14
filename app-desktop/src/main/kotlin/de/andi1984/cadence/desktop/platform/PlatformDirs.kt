package de.andi1984.cadence.desktop.platform

import java.io.File

/**
 * Where Primico keeps its database and settings on each desktop OS (ADR 0001 §8). Android has
 * no counterpart — `Context.filesDir` already answers this there — so this lives here rather
 * than in `:core`.
 */
object PlatformDirs {

    fun dataDir(): File {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val parent: File
        val name: String
        val legacyName: String
        when {
            os.contains("win") -> {
                parent = File(System.getenv("APPDATA") ?: "$home/AppData/Roaming")
                name = "Primico"; legacyName = "Cadence"
            }
            os.contains("mac") -> {
                parent = File(home, "Library/Application Support")
                name = "Primico"; legacyName = "Cadence"
            }
            else -> {
                parent = File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share")
                name = "primico"; legacyName = "cadence"
            }
        }
        val dir = File(parent, name)
        // The app was called Cadence until 3.x and kept its database under that name. An install
        // that still has the old directory and not yet the new one is moved over once — same
        // parent, so the rename is atomic — and left where it is if the move fails, rather than
        // greeting the user with an empty list next to a full database.
        val legacy = File(parent, legacyName)
        if (!dir.exists() && legacy.isDirectory && !legacy.renameTo(dir)) return legacy
        dir.mkdirs()
        return dir
    }
}
