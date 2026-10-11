package com.pocketqc.ui.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pocketqc.inference.HardwareBackend
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.pocketqc.batch.BatchManager
import com.pocketqc.batch.DefaultInspectionSession
import com.pocketqc.batch.InspectionSession
import com.pocketqc.batch.PersistentBatchManager
import com.pocketqc.camera.CameraSource
import com.pocketqc.camera.CameraXCameraSource
import com.pocketqc.camera.MockCameraSource
import com.pocketqc.core.model.InspectionResult
import com.pocketqc.core.model.InspectionStatus
import com.pocketqc.inference.InferenceEngine
import com.pocketqc.inference.MockInferenceEngine
import com.pocketqc.inference.ModelConfig
import com.pocketqc.inference.YoloInferenceEngine
import com.pocketqc.passport.BatchPassport
import com.pocketqc.passport.MockPassportGenerator
import com.pocketqc.passport.PassportGenerator
import com.pocketqc.sensors.MockSensorProvider
import com.pocketqc.sensors.SensorProvider
import com.pocketqc.settings.SettingsManager
import com.pocketqc.settings.TtsManager
import com.pocketqc.ui.components.InspectionMode
import com.pocketqc.ui.screens.BatchDetailScreen
import com.pocketqc.ui.screens.BatchHistoryScreen
import com.pocketqc.ui.screens.BatchPassportScreen
import com.pocketqc.ui.screens.DistributedLandingScreen
import com.pocketqc.ui.screens.DistributedNodeScreen
import com.pocketqc.ui.screens.DistributedServerScreen
import com.pocketqc.ui.screens.HomeScreen
import com.pocketqc.ui.screens.InspectionScreen
import com.pocketqc.ui.screens.InspectionScreen2D
import com.pocketqc.ui.screens.ResultsScreen
import com.pocketqc.ui.screens.SettingsScreen
import com.pocketqc.ui.screens.UvInspectionScreen
import com.pocketqc.ui.screens.Volume3dInspectionScreen
import com.pocketqc.distributed.client.InspectionNodeCoordinator
import com.pocketqc.distributed.server.InspectionServerManager
import com.pocketqc.distributed.store.PersistentDistributedStore
import com.pocketqc.uv.MockUvLightController
import com.pocketqc.uv.UsbSerialUvLightController
import com.pocketqc.uv.UvInspectionEngine
import com.pocketqc.volume3d.RealGeometricFillAnalyzer
import com.pocketqc.volume3d.Volume3dInspectionEngine

@Composable
fun PocketQcNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    batchManager: BatchManager? = null,
    settingsManager: SettingsManager? = null,
    cameraSource: CameraSource? = null,
    inferenceEngine: InferenceEngine? = null,
    sensorProvider: SensorProvider = remember { MockSensorProvider() },
    passportGenerator: PassportGenerator = remember { MockPassportGenerator() },
    session: InspectionSession? = null,
    startDestination: String = Screen.Home.route
) {
    val context = LocalContext.current

    val effectiveBatchManager: BatchManager = remember(batchManager) {
        batchManager ?: runCatching {
            PersistentBatchManager(context)
        }.getOrElse {
            com.pocketqc.batch.MockBatchManager()
        }
    }

    val effectiveSettingsManager = remember(settingsManager) {
        settingsManager ?: SettingsManager(context)
    }
    val currentSettings by effectiveSettingsManager.settings.collectAsState()
    val ttsManager = remember(effectiveSettingsManager) { TtsManager(context, effectiveSettingsManager) }
    val hapticManager = remember(effectiveSettingsManager) { com.pocketqc.settings.HapticManager(context, effectiveSettingsManager) }

    DisposableEffect(ttsManager) {
        onDispose {
            ttsManager.shutdown()
        }
    }

    var npuInitError by remember { mutableStateOf<String?>(null) }

    val effectiveInferenceEngine: InferenceEngine = remember(inferenceEngine) {
        inferenceEngine ?: YoloInferenceEngine(
            context = context,
            modelConfig = ModelConfig.BISCUITS_FINAL,
            confidenceThreshold = currentSettings.confidenceThreshold,
            targetBackend = HardwareBackend.AUTO
        )
    }

    LaunchedEffect(currentSettings.confidenceThreshold, effectiveInferenceEngine) {
        if (effectiveInferenceEngine is YoloInferenceEngine) {
            effectiveInferenceEngine.confidenceThreshold = currentSettings.confidenceThreshold
        }
    }

    LaunchedEffect(effectiveInferenceEngine) {
        if (!effectiveInferenceEngine.isInitialized) {
            val result = effectiveInferenceEngine.initialize()
            if (result.isFailure) {
                val err = result.exceptionOrNull()?.message ?: "2D Inspection initialization failed"
                Log.e("PocketQcNavHost", "2D Defect Sort initialization failed: $err")
                npuInitError = "2D Inspection Error: $err"
            }
        }
    }

    val effectiveSession: InspectionSession = remember(session, effectiveBatchManager, effectiveInferenceEngine) {
        session ?: DefaultInspectionSession(effectiveBatchManager, effectiveInferenceEngine)
    }

    val effectiveCameraSource: CameraSource = remember(cameraSource, effectiveInferenceEngine, effectiveSession) {
        cameraSource ?: runCatching {
            CameraXCameraSource(context, effectiveInferenceEngine) { detections, fps, latencyMs ->
                effectiveSession.updateLiveDetections(detections, fps, latencyMs)
            }
        }.getOrElse {
            MockCameraSource()
        }
    }

    // Specialized Inspection Engines: Real UV Aflatoxin Model with AUTO hardware fallback
    val uvInferenceEngine: InferenceEngine = remember {
        YoloInferenceEngine(
            context = context,
            modelConfig = ModelConfig.AFLATOXIN,
            targetBackend = HardwareBackend.AUTO
        )
    }

    // UV Aflatoxin inference engine initialized lazily upon navigating to Screen.UvInspection

    val uvEngine = remember(effectiveBatchManager, uvInferenceEngine) {
        UvInspectionEngine(
            batchManager = effectiveBatchManager,
            inferenceEngine = uvInferenceEngine
        )
    }

    val uvCameraSource: CameraSource = remember(uvInferenceEngine, uvEngine) {
        runCatching {
            CameraXCameraSource(context, uvInferenceEngine) { detections, fps, latencyMs ->
                uvEngine.updateLiveTelemetry(detections, fps, latencyMs)
            }
        }.getOrElse {
            MockCameraSource()
        }
    }

    val volumeAnalyzer = remember {
        RealGeometricFillAnalyzer()
    }

    val volumeEngine = remember(effectiveBatchManager, volumeAnalyzer) {
        Volume3dInspectionEngine(volumeAnalyzer, effectiveBatchManager)
    }

    LaunchedEffect(currentSettings.confidenceThreshold, uvInferenceEngine) {
        if (uvInferenceEngine is YoloInferenceEngine) {
            uvInferenceEngine.confidenceThreshold = currentSettings.confidenceThreshold
        }
    }

    LaunchedEffect(currentSettings.bottleIntervalSeconds, volumeEngine) {
        volumeEngine.interBottleWaitSeconds = currentSettings.bottleIntervalSeconds
    }

    LaunchedEffect(currentSettings.sampleIntervalSeconds, uvEngine) {
        uvEngine.interSampleWaitSeconds = currentSettings.sampleIntervalSeconds
    }

    val volumeCameraSource: CameraSource = remember(effectiveInferenceEngine) {
        runCatching {
            CameraXCameraSource(context, effectiveInferenceEngine) { _, _, _ -> }
        }.getOrElse {
            MockCameraSource()
        }
    }

    val lastResult by effectiveSession.lastResult.collectAsState()
    val currentBatch by effectiveBatchManager.currentBatch.collectAsState()

    val fallbackResult = remember(currentBatch) {
        val b = currentBatch ?: effectiveBatchManager.getRecentBatches().firstOrNull()
        if (b != null) {
            InspectionResult(
                productName = b.productName,
                totalInspected = b.inspectionCount,
                passed = b.passCount,
                failed = b.failCount,
                defectRate = b.defectRate,
                timestamp = b.timestamp,
                status = if (b.status == com.pocketqc.core.model.BatchStatus.APPROVED) InspectionStatus.PASSED else InspectionStatus.FAILED
            )
        } else {
            InspectionResult(
                productName = "No Batches Recorded",
                totalInspected = 0,
                passed = 0,
                failed = 0,
                defectRate = 0f,
                timestamp = System.currentTimeMillis(),
                status = InspectionStatus.PASSED
            )
        }
    }

    val distributedStore = remember {
        PersistentDistributedStore(context)
    }
    val serverManager = remember(distributedStore, effectiveBatchManager) {
        InspectionServerManager(distributedStore, batchManager = effectiveBatchManager)
    }
    val nodeCoordinator = remember {
        InspectionNodeCoordinator(context)
    }

    DisposableEffect(serverManager) {
        onDispose {
            serverManager.stopServer()
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                batchManager = effectiveBatchManager,
                onStartInspection = { selectedMode ->
                    when (selectedMode) {
                        InspectionMode.DEFECT_SORT_2D -> navController.navigate(Screen.Inspection2D.route)
                        InspectionMode.UV_AFLATOXIN -> navController.navigate(Screen.UvInspection.route)
                        InspectionMode.VOLUME_CHECK_3D -> navController.navigate(Screen.Volume3dInspection.route)
                    }
                },
                onViewPassport = {
                    navController.navigate(Screen.BatchHistory.route)
                },
                onNavigateHistory = {
                    navController.navigate(Screen.BatchHistory.route)
                },
                onNavigateSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateLab = {
                    navController.navigate(Screen.ExperimentalLab.route)
                },
                onNavigateAutoLabel = {
                    navController.navigate(Screen.AutoLabelPoC.route)
                },
                onNavigateDistributed = {
                    navController.navigate(Screen.DistributedLanding.route)
                }
            )
        }

        // Mode 1: 2D Defect Sort (Conveyor continuous batch)
        composable(Screen.Inspection2D.route) {
            InspectionScreen2D(
                session = effectiveSession,
                cameraSource = effectiveCameraSource,
                sensorProvider = sensorProvider,
                batchManager = effectiveBatchManager,
                ttsManager = ttsManager,
                hapticManager = hapticManager,
                onBatchCompleted = { completedBatch ->
                    navController.navigate(Screen.BatchDetail.createRoute(completedBatch.batchId))
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Mode 2: UV Aflatoxin Scan (Real Camera + Aflatoxin Model)
        composable(Screen.UvInspection.route) {
            LaunchedEffect(uvInferenceEngine) {
                if (!uvInferenceEngine.isInitialized) {
                    val result = uvInferenceEngine.initialize()
                    if (result.isFailure) {
                        val err = result.exceptionOrNull()?.message ?: "UV Aflatoxin NPU initialization failed"
                        Log.e("PocketQcNavHost", "UV Aflatoxin NPU initialization failed: $err")
                        npuInitError = "UV Aflatoxin NPU Error: $err"
                    }
                }
            }

            UvInspectionScreen(
                uvEngine = uvEngine,
                cameraSource = uvCameraSource,
                batchManager = effectiveBatchManager,
                ttsManager = ttsManager,
                hapticManager = hapticManager,
                onBatchCompleted = { completedBatch ->
                    navController.navigate(Screen.BatchDetail.createRoute(completedBatch.batchId))
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Mode 3: Calibrated Fill-Level & Volume Check
        composable(Screen.Volume3dInspection.route) {
            Volume3dInspectionScreen(
                volumeEngine = volumeEngine,
                cameraSource = volumeCameraSource,
                batchManager = effectiveBatchManager,
                ttsManager = ttsManager,
                hapticManager = hapticManager,
                onBatchCompleted = { completedBatch ->
                    navController.navigate(Screen.BatchDetail.createRoute(completedBatch.batchId))
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Batch History & Audit Log
        composable(Screen.BatchHistory.route) {
            BatchHistoryScreen(
                batchManager = effectiveBatchManager,
                onSelectBatch = { batchId ->
                    navController.navigate(Screen.BatchDetail.createRoute(batchId))
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Batch Detail with Prominent Machine-Readable QR Code
        composable(
            route = Screen.BatchDetail.route,
            arguments = listOf(navArgument("batchId") { type = NavType.StringType })
        ) { backStackEntry ->
            val batchId = backStackEntry.arguments?.getString("batchId") ?: ""
            val selectedBatch = effectiveBatchManager.getBatchById(batchId)
                ?: currentBatch
                ?: effectiveBatchManager.getRecentBatches().firstOrNull()

            BatchDetailScreen(
                batch = selectedBatch,
                onReturnHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Operator Settings Screen
        composable(Screen.Settings.route) {
            SettingsScreen(
                settingsManager = effectiveSettingsManager,
                ttsManager = ttsManager,
                hapticManager = hapticManager,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Experimental Model Lab (On-Device AI Training Demo)
        composable(Screen.ExperimentalLab.route) {
            com.pocketqc.ui.screens.ExperimentalLabScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Experimental Auto-Labeling PoC (Dataset Creation & Review)
        composable(Screen.AutoLabelPoC.route) {
            com.pocketqc.autolabel.ui.AutoLabelPoCScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Backward compatibility routes for existing tests
        composable(Screen.Inspection.route) {
            InspectionScreen(
                session = effectiveSession,
                cameraSource = effectiveCameraSource,
                sensorProvider = sensorProvider,
                onCompleteInspection = {
                    navController.navigate(Screen.Results.route)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Results.route) {
            val displayResult = lastResult ?: fallbackResult
            ResultsScreen(
                result = displayResult,
                onGeneratePassport = {
                    navController.navigate(Screen.BatchPassport.route)
                },
                onInspectMore = {
                    effectiveSession.startSession(mode = effectiveSession.state.value.mode)
                    navController.navigate(Screen.Inspection.route)
                },
                onReturnHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.BatchPassport.route) {
            val batchForPassport = currentBatch ?: effectiveBatchManager.getRecentBatches().firstOrNull()
            val passport: BatchPassport = remember(batchForPassport) {
                if (batchForPassport != null) {
                    passportGenerator.generate(batchForPassport)
                } else {
                    passportGenerator.generate(
                        com.pocketqc.core.model.Batch(
                            batchId = "EMPTY-PASSPORT",
                            productName = "No Batches Recorded",
                            inspectionCount = 0,
                            passCount = 0,
                            failCount = 0,
                            defectRate = 0f,
                            timestamp = System.currentTimeMillis(),
                            status = com.pocketqc.core.model.BatchStatus.REJECTED
                        )
                    )
                }
            }

            BatchPassportScreen(
                passport = passport,
                onReturnHome = {
                    navController.navigate(Screen.Home.route) {
                        popUpTo(Screen.Home.route) { inclusive = true }
                    }
                }
            )
        }

        // Local Distributed Inspection Landing
        composable(Screen.DistributedLanding.route) {
            DistributedLandingScreen(
                onSelectServerMode = {
                    navController.navigate(Screen.DistributedServer.route)
                },
                onSelectNodeMode = {
                    navController.navigate(Screen.DistributedNode.route)
                },
                onSelectStandaloneMode = {
                    navController.navigate(Screen.Inspection2D.route)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Mode A: Server Mode Dashboard (Device 1)
        composable(Screen.DistributedServer.route) {
            DistributedServerScreen(
                serverManager = serverManager,
                onViewBatchDetail = { batchId ->
                    navController.navigate(Screen.BatchDetail.createRoute(batchId))
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Mode B: Inspection Node Mode (Devices 2 & 3)
        composable(Screen.DistributedNode.route) {
            DistributedNodeScreen(
                coordinator = nodeCoordinator,
                cameraSource = effectiveCameraSource,
                session = effectiveSession,
                cameraSourceUv = uvCameraSource,
                uvEngine = uvEngine,
                volumeEngine = volumeEngine,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
