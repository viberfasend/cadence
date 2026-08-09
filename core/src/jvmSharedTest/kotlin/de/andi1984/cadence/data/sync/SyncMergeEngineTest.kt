package de.andi1984.cadence.data.sync

import de.andi1984.cadence.domain.id.UuidV7
import de.andi1984.cadence.domain.model.Priority
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncMergeEngineTest {

    @Test
    fun `test device file name generation`() {
        val deviceId = "01917f3c-45b2-7000-8000-000000000000"
        val fileName = deviceSyncFileName(deviceId)
        assertEquals("cadence-sync-01917f3c-45b2-7000-8000-000000000000.json", fileName)
    }

    @Test
    fun `test parse device ID from file name`() {
        val fileName = "cadence-sync-01917f3c-45b2-7000-8000-000000000000.json"
        val deviceId = parseDeviceIdFromFileName(fileName)
        assertEquals("01917f3c-45b2-7000-8000-000000000000", deviceId)
    }

    @Test
    fun `test parse device ID from invalid file name`() {
        val fileName = "invalid-file.json"
        val deviceId = parseDeviceIdFromFileName(fileName)
        assertEquals(null, deviceId)
    }

    @Test
    fun `test merge empty files`() {
        val now = Instant.now()
        val files = emptyMap<String, String>()
        
        val result = SyncMergeEngine.mergeFiles(files, now)
        
        assertTrue(result.projects.isEmpty())
        assertTrue(result.tasks.isEmpty())
        assertTrue(result.mergedDeviceIds.isEmpty())
        assertTrue(result.flaggedFiles.isEmpty())
    }

    @Test
    fun `test merge single device file`() {
        val deviceId = UuidV7.random().toString()
        val deviceLabel = "Test Device"
        val now = Instant.now()
        
        val state = DeviceSyncState(
            deviceId = deviceId,
            deviceLabel = deviceLabel,
            exportedAt = now.toString(),
            version = 1,
            projects = listOf(
                SyncProject(
                    id = UuidV7.random().toString(),
                    name = "Test Project",
                    colorHex = "#FF0000",
                    updatedAt = now.toString(),
                    deletedAt = null,
                )
            ),
            tasks = listOf(
                SyncTask(
                    id = UuidV7.random().toString(),
                    title = "Test Task",
                    updatedAt = now.toString(),
                    createdAt = now.toString(),
                    deletedAt = null,
                )
            )
        )
        
        val content = SyncCodec.encode(state)
        val files = mapOf(deviceSyncFileName(deviceId) to content)
        
        val result = SyncMergeEngine.mergeFiles(files, now)
        
        assertEquals(1, result.projects.size)
        assertEquals(1, result.tasks.size)
        assertEquals(setOf(deviceId), result.mergedDeviceIds)
        assertTrue(result.flaggedFiles.isEmpty())
    }

    @Test
    fun `test merge conflict resolution - newer timestamp wins`() {
        val deviceId1 = UuidV7.random().toString()
        val deviceId2 = UuidV7.random().toString()
        val taskId = UuidV7.random().toString()
        val now = Instant.now()
        
        // Device 1 has an older version of the task
        val state1 = DeviceSyncState(
            deviceId = deviceId1,
            deviceLabel = "Device 1",
            exportedAt = now.toString(),
            version = 1,
            tasks = listOf(
                SyncTask(
                    id = taskId,
                    title = "Old Title",
                    updatedAt = now.minusSeconds(3600).toString(), // 1 hour older
                    createdAt = now.minusSeconds(7200).toString(),
                    deletedAt = null,
                )
            )
        )
        
        // Device 2 has a newer version of the same task
        val state2 = DeviceSyncState(
            deviceId = deviceId2,
            deviceLabel = "Device 2",
            exportedAt = now.toString(),
            version = 1,
            tasks = listOf(
                SyncTask(
                    id = taskId,
                    title = "New Title",
                    updatedAt = now.toString(), // Current time
                    createdAt = now.minusSeconds(3600).toString(),
                    deletedAt = null,
                )
            )
        )
        
        val files = mapOf(
            deviceSyncFileName(deviceId1) to SyncCodec.encode(state1),
            deviceSyncFileName(deviceId2) to SyncCodec.encode(state2)
        )
        
        val result = SyncMergeEngine.mergeFiles(files, now)
        
        assertEquals(1, result.tasks.size)
        assertEquals("New Title", result.tasks[0].title) // Newer version should win
    }

    @Test
    fun `test merge tombstone handling`() {
        val deviceId1 = UuidV7.random().toString()
        val deviceId2 = UuidV7.random().toString()
        val taskId = UuidV7.random().toString()
        val now = Instant.now()
        
        // Device 1 has the task
        val state1 = DeviceSyncState(
            deviceId = deviceId1,
            deviceLabel = "Device 1",
            exportedAt = now.toString(),
            version = 1,
            tasks = listOf(
                SyncTask(
                    id = taskId,
                    title = "Task to delete",
                    updatedAt = now.minusSeconds(3600).toString(),
                    createdAt = now.minusSeconds(7200).toString(),
                    deletedAt = null,
                )
            )
        )
        
        // Device 2 has deleted the task (tombstone)
        val state2 = DeviceSyncState(
            deviceId = deviceId2,
            deviceLabel = "Device 2",
            exportedAt = now.toString(),
            version = 1,
            tasks = listOf(
                SyncTask(
                    id = taskId,
                    title = "Task to delete",
                    updatedAt = now.toString(), // Newer timestamp
                    createdAt = now.minusSeconds(7200).toString(),
                    deletedAt = now.toString(), // Tombstone
                )
            )
        )
        
        val files = mapOf(
            deviceSyncFileName(deviceId1) to SyncCodec.encode(state1),
            deviceSyncFileName(deviceId2) to SyncCodec.encode(state2)
        )
        
        val result = SyncMergeEngine.mergeFiles(files, now)
        
        // Task should be filtered out because it's a tombstone
        assertTrue(result.tasks.isEmpty())
    }
}