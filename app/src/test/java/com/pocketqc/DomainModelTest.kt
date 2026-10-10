package com.pocketqc

import com.pocketqc.core.model.Batch
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection
import com.pocketqc.core.model.InspectionResult
import com.pocketqc.core.model.InspectionStatus
import com.pocketqc.core.utils.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainModelTest {

    @Test
    fun testBoundingBoxDimensionsAndArea() {
        val box = BoundingBox(left = 0.1f, top = 0.2f, right = 0.5f, bottom = 0.8f)
        assertEquals(0.4f, box.width, 0.001f)
        assertEquals(0.6f, box.height, 0.001f)
        assertEquals(0.24f, box.area, 0.001f)
    }

    @Test
    fun testInspectionResultValidation() {
        val result = InspectionResult(
            productName = "Cashew Nut W320",
            totalInspected = 100,
            passed = 95,
            failed = 5,
            defectRate = 0.05f,
            timestamp = 1700000000000L,
            status = InspectionStatus.PASSED,
            detections = listOf(
                Detection("Discoloration", 0.92f, BoundingBox(0f, 0f, 0.1f, 0.1f))
            )
        )

        assertEquals("Cashew Nut W320", result.productName)
        assertEquals(100, result.totalInspected)
        assertEquals(95, result.passed)
        assertEquals(5, result.failed)
        assertEquals(0.05f, result.defectRate, 0.001f)
        assertEquals(InspectionStatus.PASSED, result.status)
        assertEquals(1, result.detections.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInspectionResultRejectsInvalidCounts() {
        // passed + failed != totalInspected should fail
        InspectionResult(
            productName = "Test",
            totalInspected = 50,
            passed = 40,
            failed = 5,
            defectRate = 0.1f,
            timestamp = 1000L,
            status = InspectionStatus.FAILED
        )
    }

    @Test
    fun testBatchPassRateCalculation() {
        val batch = Batch(
            batchId = "B-001",
            productName = "Arabica Green Coffee",
            inspectionCount = 200,
            passCount = 190,
            failCount = 10,
            defectRate = 0.05f,
            timestamp = 1700000000000L,
            status = BatchStatus.IN_PROGRESS
        )

        assertEquals(0.95f, batch.passRate, 0.001f)
    }

    @Test
    fun testFormatters() {
        assertEquals("5.0%", Formatters.formatPercent(0.05f))
        assertEquals("94%", Formatters.formatConfidence(0.942f))
    }
}
