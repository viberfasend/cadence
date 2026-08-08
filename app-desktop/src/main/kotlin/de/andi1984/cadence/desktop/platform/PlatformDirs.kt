package de.andi1984.cadence.desktop.platform

import java.io.File

/**
 * Where Cadence keeps its database and settings on each desktop OS (ADR 0001 §8). Android has
 * no counterpart — `Context.filesDir` already answers this there — so this lives here rather
 * than in `:core`.
 */
object PlatformDirs {

    fun dataDir(): File {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val dir = when {
            os.contains("win") ->
                File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "Cadence")
            os.contains("mac") ->
                File(home, "Library/Application Support/Cadence")
            else ->
                File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "cadence")
        }
        dir.mkdirs()
        return dir
    }
}
