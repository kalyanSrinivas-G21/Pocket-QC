package com.pocketqc

import com.pocketqc.core.model.BoundingBox
import com.pocketqc.inference.LetterboxInfo
import com.pocketqc.inference.YoloConfig
import com.pocketqc.inference.YoloPostprocessor
import com.pocketqc.inference.YoloPreallocatedBuffers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class Phase3PerformanceAndPipelineTest {

    private val numAnchors = YoloConfig.NUM_ANCHORS
    private val numClasses = YoloConfig.NUM_CLASSES
    private val totalOutputElements = (4 + numClasses) * numAnchors

    /**
     * Test double simulating ImageProxy lifecycle to verify strict single-closure guarantee.
     */
    private class MockFrameProxy(val id: Int) {
        val isClosed = AtomicBoolean(false)
        val closeCount = AtomicInteger(0)

        fun close() {
            closeCount.incrementAndGet()
            isClosed.set(true)
        }
    }

    @Test
    fun testPreallocatedBuffersReuseSafety() {
        val buffers = YoloPreallocatedBuffers()

        // 1. Verify exact sizing matching YOLOv8n specifications
        assertEquals(4915200, buffers.byteBuffer.capacity()) // 1 * 3 * 640 * 640 * 4 bytes = 4.9152 MB
        assertEquals(1228800, buffers.floatBuffer.capacity()) // 1 * 3 * 640 * 640 floats
        assertEquals(409600, buffers.pixels.size) // 640 * 640 ints = 1.6384 MB
        assertEquals(58800, buffers.outputFloatArray.size) // 7 * 8400 floats = 235.2 KB

        // 2. Verify buffer re-write across multiple simulated inference cycles without reallocating
        for (cycle in 0 until 10) {
            val fb = buffers.floatBuffer
            fb.clear()
            assertEquals(0, fb.position())
            assertEquals(1228800, fb.limit())

            // Write test pattern
            fb.put(0, cycle.toFloat())
            fb.put(100, (cycle * 2).toFloat())
            fb.rewind()

            assertEquals(cycle.toFloat(), fb.get(0), 0.0001f)
            assertEquals((cycle * 2).toFloat(), fb.get(100), 0.0001f)

            // Verify output array reuse
            buffers.outputFloatArray[0] = cycle * 10f
            assertEquals(cycle * 10f, buffers.outputFloatArray[0], 0.0001f)
        }

        buffers.release()
    }

    @Test
    fun testPostprocessorNumericalEquivalenceAndClassPartitioning() {
        val output = FloatArray(totalOutputElements)

        // Anchor 10: Broken (0.91)
        output[0 * numAnchors + 10] = 320f // cx
        output[1 * numAnchors + 10] = 320f // cy
        output[2 * numAnchors + 10] = 160f // w
        output[3 * numAnchors + 10] = 160f // h
        output[4 * numAnchors + 10] = 0.91f // score Broken
        output[5 * numAnchors + 10] = 0.02f
        output[6 * numAnchors + 10] = 0.01f

        // Anchor 50: Fracture (0.87)
        output[0 * numAnchors + 50] = 150f
        output[1 * numAnchors + 50] = 150f
        output[2 * numAnchors + 50] = 80f
        output[3 * numAnchors + 50] = 80f
        output[4 * numAnchors + 50] = 0.03f
        output[5 * numAnchors + 50] = 0.87f // score Fracture
        output[6 * numAnchors + 50] = 0.01f

        // Anchor 51: Fracture overlapping Anchor 50 (0.75, high IoU ~0.85) -> Must be suppressed
        output[0 * numAnchors + 51] = 152f
        output[1 * numAnchors + 51] = 152f
        output[2 * numAnchors + 51] = 80f
        output[3 * numAnchors + 51] = 80f
        output[4 * numAnchors + 51] = 0.02f
        output[5 * numAnchors + 51] = 0.75f // score Fracture
        output[6 * numAnchors + 51] = 0.01f

        // Anchor 120: Intact (0.94)
        output[0 * numAnchors + 120] = 480f
        output[1 * numAnchors + 120] = 480f
        output[2 * numAnchors + 120] = 120f
        output[3 * numAnchors + 120] = 120f
        output[4 * numAnchors + 120] = 0.01f
        output[5 * numAnchors + 120] = 0.01f
        output[6 * numAnchors + 120] = 0.94f // score Intact

        // Anchor 500: Below threshold (0.22 < 0.25) -> Must be discarded
        output[0 * numAnchors + 500] = 300f
        output[1 * numAnchors + 500] = 300f
        output[2 * numAnchors + 500] = 100f
        output[3 * numAnchors + 500] = 100f
        output[4 * numAnchors + 500] = 0.22f
        output[5 * numAnchors + 500] = 0.10f
        output[6 * numAnchors + 500] = 0.05f

        val letterbox = LetterboxInfo(scale = 1f, padX = 0f, padY = 0f, origW = 640, origH = 640)
        val detections = YoloPostprocessor.parseAndFilter(
            rawOutput = output,
            letterboxInfo = letterbox,
            confidenceThreshold = 0.25f,
            iouThreshold = 0.45f
        )

        // Exactly 3 detections: Broken (0.91), Fracture (0.87), Intact (0.94)
        assertEquals(3, detections.size)

        val brokenDet = detections.firstOrNull { it.className == "Broken" }
        assertNotNull(brokenDet)
        assertEquals(0.91f, brokenDet!!.confidence, 0.001f)

        val fractureDet = detections.firstOrNull { it.className == "Fracture" }
        assertNotNull(fractureDet)
        assertEquals(0.87f, fractureDet!!.confidence, 0.001f)

        val intactDet = detections.firstOrNull { it.className == "Intact" }
        assertNotNull(intactDet)
        assertEquals(0.94f, intactDet!!.confidence, 0.001f)

        // Verify bounding box for Anchor 10: [320 - 80, 320 - 80, 320 + 80, 320 + 80] = [240, 240, 400, 400]
        assertEquals(240f / 640f, brokenDet.boundingBox.left, 0.005f)
        assertEquals(240f / 640f, brokenDet.boundingBox.top, 0.005f)
        assertEquals(400f / 640f, brokenDet.boundingBox.right, 0.005f)
        assertEquals(400f / 640f, brokenDet.boundingBox.bottom, 0.005f)
    }

    @Test
    fun testBoundedLatestFrameGatingUnderBurst() = runBlocking {
        val totalFramesSent = 50
        val pendingHolder = AtomicReference<MockFrameProxy?>(null)
        val signalChannel = Channel<Unit>(Channel.CONFLATED)

        val framesReceived = AtomicInteger(0)
        val framesSkipped = AtomicInteger(0)
        val framesProcessed = AtomicInteger(0)

        val createdFrames = mutableListOf<MockFrameProxy>()

        // Launch slow consumer worker (5 ms per frame)
        val workerJob = launch(Dispatchers.Default) {
            for (signal in signalChannel) {
                val frame = pendingHolder.getAndSet(null) ?: continue
                try {
                    delay(5) // Simulate inference work
                    framesProcessed.incrementAndGet()
                } finally {
                    frame.close()
                }
            }
        }

        // Producer rapidly sends 50 frames
        launch(Dispatchers.Default) {
            for (i in 1..totalFramesSent) {
                val frame = MockFrameProxy(i).also { createdFrames.add(it) }
                framesReceived.incrementAndGet()

                val stale = pendingHolder.getAndSet(frame)
                if (stale != null) {
                    framesSkipped.incrementAndGet()
                    stale.close()
                }
                signalChannel.trySend(Unit)
                delay(1) // Fast 1000 FPS frame burst
            }
        }.join()

        // Allow worker to drain final frame
        signalChannel.close()
        workerJob.join()

        // Clean up any lingering pending frame
        pendingHolder.getAndSet(null)?.let { lingering ->
            framesSkipped.incrementAndGet()
            lingering.close()
        }

        assertEquals(totalFramesSent, framesReceived.get())
        assertTrue("Under burst conditions, frames must be skipped", framesSkipped.get() > 0)
        assertTrue("Some frames must have been processed", framesProcessed.get() > 0)

        // Strict conservation law: Received = Skipped + Processed
        assertEquals(totalFramesSent, framesSkipped.get() + framesProcessed.get())

        // Strict ImageProxy ownership: Every single frame must be closed exactly once!
        for (f in createdFrames) {
            assertTrue("Frame ${f.id} must be closed", f.isClosed.get())
            assertEquals("Frame ${f.id} must be closed exactly once", 1, f.closeCount.get())
        }
    }

    @Test
    fun testImageProxyOwnershipGuaranteedClosureOnAllPaths() {
        val pendingHolder = AtomicReference<MockFrameProxy?>(null)
        val frames = (1..10).map { MockFrameProxy(it) }

        var skippedCount = 0

        // Ingest frames sequentially into atomic holder
        for (f in frames) {
            val old = pendingHolder.getAndSet(f)
            if (old != null) {
                skippedCount++
                old.close()
            }
        }

        // Final pending frame extracted and processed
        val lastFrame = pendingHolder.getAndSet(null)
        assertNotNull(lastFrame)
        lastFrame?.close()

        assertEquals(9, skippedCount)

        // Every frame must be closed exactly once
        for (f in frames) {
            assertTrue("Frame ${f.id} must be closed", f.isClosed.get())
            assertEquals("Frame ${f.id} must be closed exactly once", 1, f.closeCount.get())
        }
    }

    @Test
    fun testRotationLetterboxMatrixCalculationEquivalence() {
        // Verify mathematically that portrait rotation (90 deg) of 640x480 yields 480x640 effective
        val rawW = 640
        val rawH = 480
        val rotationDegrees = 90

        val isRotated = (rotationDegrees == 90 || rotationDegrees == 270)
        val effW = if (isRotated) rawH else rawW
        val effH = if (isRotated) rawW else rawH

        assertEquals(480, effW)
        assertEquals(640, effH)

        val targetSize = 640
        val scale = minOf(targetSize.toFloat() / effW, targetSize.toFloat() / effH)
        val scaledW = (effW * scale).toInt()
        val scaledH = (effH * scale).toInt()
        val padX = (targetSize - scaledW) / 2f
        val padY = (targetSize - scaledH) / 2f

        assertEquals(1.0f, scale, 0.001f)
        assertEquals(480, scaledW)
        assertEquals(640, scaledH)
        assertEquals(80f, padX, 0.001f)
        assertEquals(0f, padY, 0.001f)

        val letterboxInfo = LetterboxInfo(
            scale = scale,
            padX = padX,
            padY = padY,
            origW = effW,
            origH = effH
        )

        // Invert a point at center of letterbox target (320, 320)
        val origX = letterboxInfo.invertX(320f)
        val origY = letterboxInfo.invertY(320f)

        // origX = (320 - 80) / 1.0 = 240
        // origY = (320 - 0) / 1.0 = 320
        assertEquals(240f, origX, 0.01f)
        assertEquals(320f, origY, 0.01f)
        assertEquals(240f / 480f, origX / effW, 0.01f) // Exactly 0.5 (center)
        assertEquals(320f / 640f, origY / effH, 0.01f) // Exactly 0.5 (center)
    }
}
