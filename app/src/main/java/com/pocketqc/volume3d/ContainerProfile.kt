package com.pocketqc.volume3d

/**
 * Geometric classification of container cross-section profile.
 */
enum class ContainerGeometryType {
    /** Constant cross-sectional area: Fill Height % == Volume % */
    UNIFORM_CYLINDER,

    /** Non-uniform neck/shoulder: Fill Height % measured directly; volume is non-linear */
    TAPERED_BOTTLE,

    /** Custom user-calibrated container with user-defined reference height */
    CUSTOM_CONTAINER
}

/**
 * Specification profile for container fill-level inspection and dimensional scaling.
 */
data class ContainerProfile(
    val id: String,
    val name: String,
    val geometryType: ContainerGeometryType,
    val nominalCapacityMl: Float?,
    val referenceHeightMm: Float,   // Container base to neck/top reference height in mm
    val targetFillPercent: Float = 80.0f,
    val tolerancePercent: Float = 3.0f,
    val innerRadiusMm: Float? = null, // For uniform cylinder volume calculation (pi * r^2 * h)
    val containerWidthPercent: Float = 50.0f // Screen width fraction percentage for ROI reticle [20%..80%]
) {
    /**
     * Calculates estimated liquid volume in mL from measured fill height in mm.
     * Returns null if container geometry is non-uniform or dimensions are unspecified.
     */
    fun calculateEstimatedVolumeMl(fillHeightMm: Float): Float? {
        if (geometryType != ContainerGeometryType.UNIFORM_CYLINDER) return null
        if (fillHeightMm <= 0f) return 0f

        // When inner radius is specified: V = pi * r^2 * h (in mm^3; 1000 mm^3 = 1 mL)
        if (innerRadiusMm != null && innerRadiusMm > 0f) {
            val volumeMm3 = Math.PI * innerRadiusMm * innerRadiusMm * fillHeightMm
            return (volumeMm3 / 1000.0).toFloat()
        }

        // When nominal capacity is specified and container is uniform cylinder:
        if (nominalCapacityMl != null && referenceHeightMm > 0f) {
            return nominalCapacityMl * (fillHeightMm / referenceHeightMm)
        }

        return null
    }

    companion object {
        val CYLINDER_500ML = ContainerProfile(
            id = "cyl_500ml",
            name = "500ml Cylindrical Bottle / Jar",
            geometryType = ContainerGeometryType.UNIFORM_CYLINDER,
            nominalCapacityMl = 500f,
            referenceHeightMm = 160f,
            targetFillPercent = 80.0f,
            tolerancePercent = 3.0f,
            innerRadiusMm = 31.5f
        )

        val CYLINDER_250ML = ContainerProfile(
            id = "cyl_250ml",
            name = "250ml Cylindrical Vial / Can",
            geometryType = ContainerGeometryType.UNIFORM_CYLINDER,
            nominalCapacityMl = 250f,
            referenceHeightMm = 120f,
            targetFillPercent = 85.0f,
            tolerancePercent = 3.0f,
            innerRadiusMm = 25.7f
        )

        val BEVERAGE_BOTTLE_500ML = ContainerProfile(
            id = "bottle_500ml",
            name = "500ml Contoured Bottle",
            geometryType = ContainerGeometryType.TAPERED_BOTTLE,
            nominalCapacityMl = 500f,
            referenceHeightMm = 210f,
            targetFillPercent = 80.0f,
            tolerancePercent = 3.0f,
            innerRadiusMm = null // Non-uniform cross section: volume is non-linear
        )

        val CUSTOM = ContainerProfile(
            id = "custom",
            name = "Custom Container",
            geometryType = ContainerGeometryType.CUSTOM_CONTAINER,
            nominalCapacityMl = null,
            referenceHeightMm = 100f,
            targetFillPercent = 80.0f,
            tolerancePercent = 3.0f,
            innerRadiusMm = null
        )

        val ALL_PROFILES = listOf(
            CYLINDER_500ML,
            CYLINDER_250ML,
            BEVERAGE_BOTTLE_500ML,
            CUSTOM
        )
    }
}
