package com.pocketqc.ui.navigation

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Inspection : Screen("inspection")
    data object Results : Screen("results")
    data object BatchPassport : Screen("batch_passport")

    // Dedicated Mode Inspection Routes
    data object Inspection2D : Screen("inspection_2d")
    data object UvInspection : Screen("uv_inspection")
    data object Volume3dInspection : Screen("volume_3d_inspection")

    // Batch Audit & Settings Routes
    data object BatchHistory : Screen("batch_history")
    data object BatchDetail : Screen("batch_detail/{batchId}") {
        fun createRoute(batchId: String): String = "batch_detail/$batchId"
    }
    data object Settings : Screen("settings")
    data object ExperimentalLab : Screen("experimental_lab")
}
