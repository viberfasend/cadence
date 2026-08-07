package de.andi1984.cadence.domain.backup

import java.time.Instant

/**
 * When automatic sync may read a backup file back over the app's own data.
 *
 * Importing replaces every task and project, so the one thing this must never do is pull in a
 * file the device has already seen — that would undo whatever was added since. The file's
 * `exportedAt` is the only ordering the format carries, so a file is only read back when it was
 * written *after* the last export or import this device took part in.
 *
 * A device that has never synced with the file refuses to import it: on the first run after the
 * feature is switched on the app's own data is the truth, and a file written by someone else is
 * only ever pulled in by an explicit manual import (which then records the timestamp and lets
 * every later open follow along).
 */
object AutoBackupPolicy {

    fun shouldImport(fileExportedAt: Instant?, lastSyncedAt: Instant?): Boolean {
        if (fileExportedAt == null || lastSyncedAt == null) return false
        return fileExportedAt.isAfter(lastSyncedAt)
    }
}
