package com.pocketqc

import com.pocketqc.batch.PersistentBatchManager
import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.utils.BatchIdGenerator
import com.pocketqc.core.utils.QrCodeGenerator
import com.pocketqc.ui.components.InspectionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BatchPersistenceAndQrTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBatchIdFormatAndCollisionSafety() {
        BatchIdGenerator.resetCounter(1)
        val id1 = BatchIdGenerator.generate(prefix = "PQ")
        assertTrue("Expected PQ- format, got: $id1", id1.matches(Regex("^PQ-\\d{8}-\\d{4}$")))

        // Test collision safety across 100 IDs
        val ids = mutableSetOf<String>()
        for (i in 1..100) {
            val id = BatchIdGenerator.generate(prefix = "PQ")
            val added = ids.add(id)
            assertTrue("Duplicate batch ID generated: $id", added)
        }
        assertEquals(100, ids.size)
    }

    @Test
    fun testQrCodePayloadJsonStructure() {
        val payload = QrCodeGenerator.createPayloadJson(
            app = "PocketQC",
            id = "PQ-20261007-0099",
            type = "2D",
            total = 50,
            pass = 47,
            defectOrFlagged = 3,
            rate = 6.00f,
            result = "PASS"
        )

        val json = JSONObject(payload)
        assertEquals("PocketQC", json.getString("app"))
        assertEquals("PQ-20261007-0099", json.getString("id"))
        assertEquals("2D", json.getString("type"))
        assertEquals(50, json.getInt("total"))
        assertEquals(47, json.getInt("pass"))
        assertEquals(3, json.getInt("defect"))
        assertEquals(6.0, json.getDouble("rate"), 0.01)
        assertEquals("PASS", json.getString("result"))
    }

    @Test
    fun testPersistentBatchManagerCleanStartHasZeroBatches() {
        val dir = tempFolder.newFolder("batch_storage_clean")
        val manager = PersistentBatchManager(baseDir = dir, fileName = "batches.json")

        val all = manager.getRecentBatches()
        assertEquals(0, all.size)
        org.junit.Assert.assertNull(manager.currentBatch.value)
    }

    @Test
    fun testPersistentBatchManagerExplicitSeeding() {
        val dir = tempFolder.newFolder("batch_storage_seeded")
        val manager = PersistentBatchManager(baseDir = dir, fileName = "batches.json")
        manager.seedDefaultBatches()

        val all = manager.getRecentBatches()
        assertEquals(3, all.size)

        val batch2D = all.find { it.mode == InspectionMode.DEFECT_SORT_2D }
        assertNotNull(batch2D)
        assertEquals("Biscuit", batch2D?.productName)

        val batchUv = all.find { it.mode == InspectionMode.UV_AFLATOXIN }
        assertNotNull(batchUv)

        val batch3D = all.find { it.mode == InspectionMode.VOLUME_CHECK_3D }
        assertNotNull(batch3D)
    }

    @Test
    fun testPersistentBatchManagerSavesAndSurvivesReload() {
        val dir = tempFolder.newFolder("batch_storage_2")
        val manager1 = PersistentBatchManager(baseDir = dir, fileName = "batches.json")

        val customBatch = Batch(
            batchId = "PQ-20261007-9999",
            productName = "Cold Pressed Sesame Oil",
            inspectionCount = 100,
            passCount = 98,
            failCount = 2,
            defectRate = 0.02f,
            timestamp = 1728300000000L,
            status = BatchStatus.APPROVED,
            mode = InspectionMode.VOLUME_CHECK_3D,
            requiredFillPercent = 85f,
            averageDetectedFillPercent = 84.9f,
            qrPayload = """{"app":"PocketQC","id":"PQ-20261007-9999","type":"3D","total":100,"pass":98,"defect":2,"rate":2.00,"result":"PASS"}"""
        )

        manager1.saveBatch(customBatch)

        val retrieved1 = manager1.getBatchById("PQ-20261007-9999")
        assertNotNull(retrieved1)
        assertEquals("Cold Pressed Sesame Oil", retrieved1?.productName)

        // Instantiate a second manager on the exact same directory (simulating app relaunch)
        val manager2 = PersistentBatchManager(baseDir = dir, fileName = "batches.json")
        val all2 = manager2.getRecentBatches()
        assertTrue(all2.any { it.batchId == "PQ-20261007-9999" })

        val retrieved2 = manager2.getBatchById("PQ-20261007-9999")
        assertNotNull(retrieved2)
        assertEquals(100, retrieved2?.inspectionCount)
        assertEquals(85f, retrieved2?.requiredFillPercent ?: 0f, 0.001f)
        assertEquals(InspectionMode.VOLUME_CHECK_3D, retrieved2?.mode)
    }
}
