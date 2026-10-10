package com.pocketqc.sensors

/**
 * Optical sensor readings providing ambient illuminance and UV illumination screening state.
 * Used for inspection gating to reject under-lit or overexposed frames.
 */
data class LightingCondition(
    val illuminanceLux: Float,
    val isAdequate: Boolean,
    val isUvActive: Boolean = false
)
