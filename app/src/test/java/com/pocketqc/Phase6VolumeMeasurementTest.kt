package com.pocketqc

import com.pocketqc.batch.MockBatchManager
import com.pocketqc.batch.PersistentBatchManager
import com.pocketqc.core.model.BatchStatus
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.volume3d.ArrayPixelSource
import com.pocketqc.volume3d.CalibrationRoi
import com.pocketqc.volume3d.ContainerGeometryType
import com.pocketqc.volume3d.ContainerProfile
import com.pocketqc.volume3d.MeasurementStatus
import com.pocketqc.volume3d.RealGeometricFillAnalyzer
import com.pocketqc.volume3d.Volume3dInspectionEngine
import com.pocketqc.volume3d.Volume3dPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.abs

/**
 * Comprehensive verification suite for Phase 6:
 * Calibrated Container Fill-Level Measurement & Volumetric Estimation.
 */
class Phase6VolumeMeasurementTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ------------------------------------------------------------------------
    // 1. Geometry and Dimension Calculations
    // ------------------------------------------------------------------------

    @Test
    fun test_uniform_cylinder_volume_calculation() {
        val profile = ContainerProfile.CYLINDER_500ML
        assertEquals(ContainerGeometryType.UNIFORM_CYLINDER, profile.geometryType)
        assertEquals(160f, profile.referenceHeightMm, 0.001f)
        assertEquals(31.5f, profile.innerRadiusMm!!, 0.001f)

        // For a full height fill (160mm): V = pi * r^2 * h / 1000
        val expectedFullVolume = (Math.PI * 31.5 * 31.5 * 160.0 / 1000.0).toFloat()
        val calculatedFull = profile.calculateEstimatedVolumeMl(160f)
        assertNotNull(calculatedFull)
        assertEquals(expectedFullVolume, calculatedFull!!, 0.5f)
        assertTrue("Expected ~498 mL", calculatedFull in 490f..510f)

        // Half fill (80mm) should be exactly 50%
        val halfVolume = profile.calculateEstimatedVolumeMl(80f)
        assertNotNull(halfVolume)
        assertEquals(expectedFullVolume * 0.5f, halfVolume!!, 0.5f)

        // Zero and negative height
        assertEquals(0f, profile.calculateEstimatedVolumeMl(0f)!!, 0.001f)
        assertEquals(0f, profile.calculateEstimatedVolumeMl(-10f)!!, 0.001f)
    }

    @Test
    fun test_tapered_bottle_rejects_uncalibrated_volume_estimation() {
        val tapered = ContainerProfile.BEVERAGE_BOTTLE_500ML
        assertEquals(ContainerGeometryType.TAPERED_BOTTLE, tapered.geometryType)

        // Tapered neck bottles must return null for linear volume calculation
        // to prevent fabricating non-linear liquid volumes
        val calculatedVol = tapered.calculateEstimatedVolumeMl(150f)
        assertNull("Tapered bottle volume must be null (non-linear geometry)", calculatedVol)
    }

    @Test
    fun test_custom_container_profile_scaling() {
        val custom = ContainerProfile(
            id = "test_custom",
            name = "Test Custom Cylinder",
            geometryType = ContainerGeometryType.UNIFORM_CYLINDER,
            nominalCapacityMl = 300f,
            referenceHeightMm = 150f,
            targetFillPercent = 80.0f,
            tolerancePercent = 2.5f,
            innerRadiusMm = null
        )

        // Nominal capacity proportional scaling: 300ml * (75mm / 150mm) = 150ml
        val halfVol = custom.calculateEstimatedVolumeMl(75f)
        assertNotNull(halfVol)
        assertEquals(150f, halfVol!!, 0.001f)
    }

    // ------------------------------------------------------------------------
    // 2. Tolerance & Acceptance Decision Logic
    // ------------------------------------------------------------------------

    @Test
    fun test_acceptance_decision_logic_boundaries() {
        val target = 80.0f
        val tolerance = 3.0f

        // Case 1: Exact match -> PASS
        val diffExact = 80.0f - target
        assertTrue(abs(diffExact) <= tolerance)

        // Case 2: Boundary lower limit (77.0%) -> PASS
        val diffLower = 77.0f - target
        assertTrue(abs(diffLower) <= tolerance)

        // Case 3: Boundary upper limit (83.0%) -> PASS
        val diffUpper = 83.0f - target
        assertTrue(abs(diffUpper) <= tolerance)

        // Case 4: Below tolerance (76.9%) -> OUT_OF_TOLERANCE
        val diffUnder = 76.9f - target
        assertFalse(abs(diffUnder) <= tolerance)

        // Case 5: Above tolerance (83.1%) -> OUT_OF_TOLERANCE
        val diffOver = 83.1f - target
        assertFalse(abs(diffOver) <= tolerance)
    }

    // ------------------------------------------------------------------------
    // 3. Image Processing & Meniscus Detection
    // ------------------------------------------------------------------------

    @Test
    fun test_real_geometric_fill_analyzer_detects_synthetic_meniscus() {
        val analyzer = RealGeometricFillAnalyzer(
            profile = ContainerProfile.CYLINDER_500ML,
            calibrationRoi = CalibrationRoi(
                topNormalizedY = 0.20f,
                baseNormalizedY = 0.80f,
                leftNormalizedX = 0.20f,
                rightNormalizedX = 0.80f
            )
        )

        // Synthetic container: 100 x 200 pixels
        // Container vertical range: yTop = 40, yBase = 160 (height = 120 pixels)
        // Meniscus placed at y = 64 (which is 24 pixels below yTop, so liquid height = 160 - 64 = 96 px)
        // Fill height ratio = 96 / 120 = 80.0%
        val syntheticImage = ArrayPixelSource.createSyntheticBottleImage(
            width = 100,
            height = 200,
            meniscusY = 64,
            headspaceLuminance = 230,
            liquidLuminance = 40
        )

        val result = analyzer.analyzePixelSource(syntheticImage, requiredFillPercent = 80.0f)

        assertEquals(MeasurementStatus.PASS, result.status)
        assertTrue("Result isPass should be true", result.isPass)
        assertEquals(80.0f, result.detectedFillPercent, 1.5f)
        assertTrue("Confidence should be high (>0.7)", result.confidence > 0.70f)
        assertNotNull(result.measuredHeightMm)
        assertEquals(160f * 0.80f, result.measuredHeightMm!!, 3.0f)
        assertNotNull(result.estimatedVolumeMl)
        assertTrue(result.diagnostics.contains("Meniscus detected"))
    }

    @Test
    fun test_real_geometric_fill_analyzer_out_of_tolerance_detection() {
        val analyzer = RealGeometricFillAnalyzer(
            profile = ContainerProfile.CYLINDER_500ML.copy(tolerancePercent = 3.0f),
            calibrationRoi = CalibrationRoi(
                topNormalizedY = 0.20f,
                baseNormalizedY = 0.80f,
                leftNormalizedX = 0.20f,
                rightNormalizedX = 0.80f
            )
        )

        // Liquid height = 160 - 88 = 72 px out of 120 px -> 60.0% fill (Underfilled, Target 80%)
        val underfilledImage = ArrayPixelSource.createSyntheticBottleImage(
            width = 100,
            height = 200,
            meniscusY = 88,
            headspaceLuminance = 230,
            liquidLuminance = 40
        )

        val result = analyzer.analyzePixelSource(underfilledImage, requiredFillPercent = 80.0f)

        assertEquals(MeasurementStatus.OUT_OF_TOLERANCE, result.status)
        assertFalse(result.isPass)
        assertEquals(60.0f, result.detectedFillPercent, 1.5f)
        assertTrue("Difference should be around -20%", result.differencePercent < -15.0f)
    }

    @Test
    fun test_real_geometric_fill_analyzer_rejects_flat_occluded_image() {
        val analyzer = RealGeometricFillAnalyzer()

        // Flat uniform grey image (zero contrast, e.g. lens covered or blank scene)
        val flatImage = ArrayPixelSource.createSyntheticBottleImage(
            width = 100,
            height = 200,
            meniscusY = 100,
            headspaceLuminance = 120,
            liquidLuminance = 120 // Identical luminance -> contrast span = 0
        )

        val result = analyzer.analyzePixelSource(flatImage, requiredFillPercent = 80.0f)

        assertEquals(MeasurementStatus.INCONCLUSIVE, result.status)
        assertFalse("Inconclusive must not pass", result.isPass)
        assertTrue(result.confidence < 0.5f)
        assertTrue(result.diagnostics.contains("Low contrast"))
    }

    @Test
    fun test_real_geometric_fill_analyzer_handles_small_or_invalid_dimensions() {
        val analyzer = RealGeometricFillAnalyzer()
        val tinyImage = ArrayPixelSource(10, 10, IntArray(100))

        val result = analyzer.analyzePixelSource(tinyImage, requiredFillPercent = 80.0f)

        assertEquals(MeasurementStatus.ERROR, result.status)
        assertFalse(result.isPass)
        assertTrue(result.diagnostics.contains("too small"))
    }

    // ------------------------------------------------------------------------
    // 4. Inspection Engine State & Immediate Scan
    // ------------------------------------------------------------------------

    @Test
    fun test_engine_state_and_immediate_scan() {
        runBlocking {
            val mockBatchManager = MockBatchManager()
            val analyzer = RealGeometricFillAnalyzer()
            val engine = Volume3dInspectionEngine(
                volumeAnalyzer = analyzer,
                batchManager = mockBatchManager,
                interBottleWaitSeconds = 0
            )

            assertEquals(Volume3dPhase.IDLE, engine.state.value.phase)
            assertEquals(80.0f, engine.state.value.requiredFillPercent, 0.001f)

            // Select profile
            engine.selectProfile(ContainerProfile.CYLINDER_250ML)
            assertEquals("cyl_250ml", engine.state.value.selectedProfile.id)
            assertEquals(85.0f, engine.state.value.requiredFillPercent, 0.001f)

            // Test alignment immediate scan before starting batch
            val testFrame = ArrayPixelSource.createSyntheticBottleImage(
                width = 100,
                height = 200,
                meniscusY = 60, // ~85% fill on 40..170 range (110/130 = 84.6%)
                headspaceLuminance = 230,
                liquidLuminance = 50
            )
            // Test analyzer directly
            val previewResult = analyzer.analyzePixelSource(testFrame, requiredFillPercent = 85.0f)
            assertEquals(MeasurementStatus.PASS, previewResult.status)

            // Start batch
            val batchId = engine.startBatch(requiredFillPercent = 85.0f)
            assertTrue(batchId.startsWith("PQ-"))
            assertEquals(Volume3dPhase.STARTING, engine.state.value.phase)

            // Stop batch
            val completedBatch = engine.stopBatch()
            assertEquals(Volume3dPhase.COMPLETED, engine.state.value.phase)
            assertEquals(InspectionMode.VOLUME_CHECK_3D, completedBatch.mode)
            assertTrue(completedBatch.qrPayload.contains("\"type\":\"3D\""))
            assertTrue(completedBatch.qrPayload.contains(batchId))
        }
    }

    // ------------------------------------------------------------------------
    // 5. Persistence, Batch QR Code & History Integrity
    // ------------------------------------------------------------------------

    @Test
    fun test_persistent_batch_manager_preserves_volume_measurements() {
        val storageDir = tempFolder.newFolder("batch_test")
        val manager = PersistentBatchManager(baseDir = storageDir, fileName = "volume_batches.json")

        val batchId = "PQ-20261009-VOL01"
        val now = System.currentTimeMillis()
        val originalBatch = com.pocketqc.core.model.Batch(
            batchId = batchId,
            productName = "500ml Cylindrical Bottle",
            inspectionCount = 20,
            passCount = 19,
            failCount = 1,
            defectRate = 0.05f,
            timestamp = now,
            status = BatchStatus.APPROVED,
            mode = InspectionMode.VOLUME_CHECK_3D,
            requiredFillPercent = 80.0f,
            averageDetectedFillPercent = 80.2f,
            qrPayload = """{"app":"PocketQC","id":"$batchId","type":"3D","total":20,"pass":19,"defect":1,"rate":5.00,"result":"PASS"}"""
        )

        manager.saveBatch(originalBatch)

        // Reload manager from storage file
        val reloadedManager = PersistentBatchManager(baseDir = storageDir, fileName = "volume_batches.json")
        val recovered = reloadedManager.getBatchById(batchId)

        assertNotNull("Batch should be recovered from persistent storage", recovered)
        assertEquals(batchId, recovered!!.batchId)
        assertEquals(InspectionMode.VOLUME_CHECK_3D, recovered.mode)
        assertEquals(80.0f, recovered.requiredFillPercent, 0.001f)
        assertEquals(80.2f, recovered.averageDetectedFillPercent, 0.01f)
        assertEquals(19, recovered.passCount)
        assertEquals(1, recovered.failCount)
        assertTrue(recovered.qrPayload.contains("\"type\":\"3D\""))
    }
}
