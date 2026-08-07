package de.andi1984.cadence

import de.andi1984.cadence.domain.backup.AutoBackupPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The decision behind automatic import. Getting it wrong loses data in one direction (an old
 * file replaces newer tasks) or leaves the feature useless in the other, so each case is
 * spelled out rather than inferred from the one comparison.
 */
class AutoBackupPolicyTest {

    private val noon = Instant.parse("2026-08-07T12:00:00Z")

    @Test
    fun `a file written after the last sync is read back`() {
        assertTrue(AutoBackupPolicy.shouldImport(fileExportedAt = noon, lastSyncedAt = noon.minusSeconds(60)))
    }

    @Test
    fun `the app's own last export is not read back`() {
        assertFalse(AutoBackupPolicy.shouldImport(fileExportedAt = noon, lastSyncedAt = noon))
    }

    @Test
    fun `an older file never replaces what is on the device`() {
        assertFalse(AutoBackupPolicy.shouldImport(fileExportedAt = noon.minusSeconds(60), lastSyncedAt = noon))
    }

    @Test
    fun `a file this device has never synced with is left alone`() {
        assertFalse(AutoBackupPolicy.shouldImport(fileExportedAt = noon, lastSyncedAt = null))
    }

    @Test
    fun `a file without a usable timestamp cannot be ordered, so it is not imported`() {
        assertFalse(AutoBackupPolicy.shouldImport(fileExportedAt = null, lastSyncedAt = noon))
        assertFalse(AutoBackupPolicy.shouldImport(fileExportedAt = null, lastSyncedAt = null))
    }
}
