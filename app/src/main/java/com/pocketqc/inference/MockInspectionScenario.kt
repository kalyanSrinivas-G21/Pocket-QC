package com.pocketqc.inference

import com.pocketqc.core.model.BoundingBox
import com.pocketqc.core.model.Detection

/**
 * Encapsulates a single sample result in the mock inspection pipeline.
 */
data class MockSample(
    val sampleIndex: Int,
    val isDefective: Boolean,
    val detections: List<Detection> = emptyList()
)

/**
 * Deterministic mock inspection scenario for Pocket QC food quality inspection.
 *
 * Sequence specification:
 * - 24 samples in total
 * - 21 PASS samples
 * - 3 DEFECT samples
 * - Defect breakdown: 2 Broken Edge, 1 Burnt Surface
 * - Defect rate: 12.5% (3/24)
 * - Passing rate: 87.5% (21/24)
 */
object MockInspectionScenario {

    const val TOTAL_SAMPLES = 24
    const val EXPECTED_PASSED = 21
    const val EXPECTED_DEFECTIVE = 3
    const val EXPECTED_DEFECT_RATE = 0.125f
    const val EXPECTED_PASS_RATE = 0.875f

    private val samples: List<MockSample> = listOf(
        // Samples 1 - 4: PASS
        MockSample(sampleIndex = 0, isDefective = false),
        MockSample(sampleIndex = 1, isDefective = false),
        MockSample(sampleIndex = 2, isDefective = false),
        MockSample(sampleIndex = 3, isDefective = false),

        // Sample 5: DEFECT (Broken Edge)
        MockSample(
            sampleIndex = 4,
            isDefective = true,
            detections = listOf(
                Detection(
                    className = "Broken Edge",
                    confidence = 0.94f,
                    boundingBox = BoundingBox(left = 0.16f, top = 0.22f, right = 0.40f, bottom = 0.46f)
                )
            )
        ),

        // Samples 6 - 10: PASS
        MockSample(sampleIndex = 5, isDefective = false),
        MockSample(sampleIndex = 6, isDefective = false),
        MockSample(sampleIndex = 7, isDefective = false),
        MockSample(sampleIndex = 8, isDefective = false),
        MockSample(sampleIndex = 9, isDefective = false),

        // Sample 11: DEFECT (Burnt Surface)
        MockSample(
            sampleIndex = 10,
            isDefective = true,
            detections = listOf(
                Detection(
                    className = "Burnt Surface",
                    confidence = 0.89f,
                    boundingBox = BoundingBox(left = 0.54f, top = 0.40f, right = 0.78f, bottom = 0.64f)
                )
            )
        ),

        // Samples 12 - 17: PASS
        MockSample(sampleIndex = 11, isDefective = false),
        MockSample(sampleIndex = 12, isDefective = false),
        MockSample(sampleIndex = 13, isDefective = false),
        MockSample(sampleIndex = 14, isDefective = false),
        MockSample(sampleIndex = 15, isDefective = false),
        MockSample(sampleIndex = 16, isDefective = false),

        // Sample 18: DEFECT (Broken Edge)
        MockSample(
            sampleIndex = 17,
            isDefective = true,
            detections = listOf(
                Detection(
                    className = "Broken Edge",
                    confidence = 0.92f,
                    boundingBox = BoundingBox(left = 0.20f, top = 0.48f, right = 0.44f, bottom = 0.70f)
                )
            )
        ),

        // Samples 19 - 24: PASS
        MockSample(sampleIndex = 18, isDefective = false),
        MockSample(sampleIndex = 19, isDefective = false),
        MockSample(sampleIndex = 20, isDefective = false),
        MockSample(sampleIndex = 21, isDefective = false),
        MockSample(sampleIndex = 22, isDefective = false),
        MockSample(sampleIndex = 23, isDefective = false)
    )

    fun getSample(index: Int): MockSample {
        require(index in 0 until TOTAL_SAMPLES) {
            "Sample index $index out of bounds (0 until $TOTAL_SAMPLES)"
        }
        return samples[index]
    }

    fun getAllSamples(): List<MockSample> = samples
}
