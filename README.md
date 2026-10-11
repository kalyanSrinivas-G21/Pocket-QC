# Pocket QC — Industrial On-Device Edge Quality Control System

[![Platform](https://img.shields.io/badge/Platform-Android%2014%2B%20(API%2028--36)-3DDC84.svg?style=flat&logo=android)](https://developer.android.com/)
[![Architecture](https://img.shields.io/badge/Architecture-ARM64--v8a%20NPU--First-0052CC.svg?style=flat)](https://www.qualcomm.com/products/mobile/snapdragon)
[![Engine](https://img.shields.io/badge/ML%20Engine-ONNX%20Runtime%20%2B%20QNN%20HTP-FF6F00.svg?style=flat)](https://onnxruntime.ai/)
[![UI](https://img.shields.io/badge/UI-Jetpack%20Compose%20%2B%20Material3-4285F4.svg?style=flat&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![Network](https://img.shields.io/badge/Network-100%25%20Offline%20Air--Gapped-success.svg?style=flat)](#air-gapped-sovereignty)
[![License](https://img.shields.io/badge/License-Proprietary%20%2F%20Industrial-grey.svg?style=flat)](#)

**Pocket QC** is an enterprise-grade, offline-first edge quality inspection system engineered for real-time food, packaging, and agricultural quality assurance directly on mobile hardware. 

Operating under complete air-gapped security (zero cloud dependencies, zero external network requirements), Pocket QC transforms Qualcomm Snapdragon-powered Android devices into calibrated, multi-spectral inspection instruments capable of sustained 30+ FPS computer vision inference with sub-5ms NPU latencies.

---

## Key Highlights & Architectural Pillars

- **100% Air-Gapped Operational Sovereignty:** No internet permissions (`android.permission.INTERNET` not required for single-node inspection). Zero telemetry leakage, zero external API latency, fully compliant with high-security manufacturing protocols.
- **Snapdragon NPU-First Acceleration:** High-throughput hardware inference powered by the Qualcomm Neural Processing SDK (QNN) Hexagon Tensor Processor (HTP) execution provider via ONNX Runtime Mobile.
- **Zero-Allocation Steady-State Processing:** Eliminates Android Garbage Collection (GC) pauses during high-speed conveyor belt inspection via preallocated memory pools (`YoloPreallocatedBuffers`), keeping per-frame steady-state churn near 0 MB.
- **Multi-Modal Inspection Capabilities:** 
  1. High-speed 2D defect classification & object tracking.
  2. Multi-spectral UV optical screening (Aflatoxin B1 fungal contamination).
  3. 3D geometric fill-level & liquid meniscus analysis.
- **Local Distributed QC (Multi-Station Mesh):** Embedded offline HTTP/WebSocket server and client coordinators allow multiple inspection devices along a line to aggregate batch metrics into a single real-time audit ledger.
- **Tamper-Evident QR Batch Passports:** Generates cryptographically verifiable QR passports encoding defect breakdown, batch metadata, and SHA-256 signature hashes for downstream supply chain verification.
- **Isolated Experimental Model Lab:** An on-device data collection, labeling, and fine-tuning sandbox with strict architectural guards protecting the immutable production models.

---

## Inspection Modalities

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│                                 POCKET QC MODES                                   │
├────────────────────────┬──────────────────────────┬───────────────────────────────┤
│    2D DEFECT SORT      │   UV OPTICAL SCREENING   │     3D GEOMETRIC VOLUME       │
│                        │                          │                               │
│  • Conveyor bisquits   │  • Aflatoxin B1 spots    │  • Liquid meniscus tracking   │
│  • Intact / Broken /   │  • 365 nm UV fluorescence│  • Fill level percentage      │
│    Fracture detection  │  • Multi-phase exposure  │  • Underfill / Overfill gate  │
│  • Centroid tracking   │  • High-contrast mask    │  • Pixel-to-mm calibration    │
│  • Bounded queues      │  • Batch safety cutoff   │  • Real-time cycle analysis   │
└────────────────────────┴──────────────────────────┴───────────────────────────────┘
```

### 1. 2D Defect Sorting (Food & Confectionery)
- **Target Products:** Biscuits, cookies, bakery items, stamped dry goods.
- **Classes:** `Intact`, `Broken`, `Fracture`.
- **Model:** YOLOv8 Nano quantized/optimized (`biscuits_final.onnx`, 640×640 input resolution).
- **Tracking Pipeline:** Centroid-distance tracking with spatial debouncing across conveyor belt frames to prevent double-counting as items traverse the camera viewport.
- **Throughput:** Capable of tracking 10–30 units per second with instant operator audio/haptic alert triggers.

### 2. Multi-Spectral UV Optical Screening (Aflatoxin B1)
- **Target Commodities:** Red chillies, maize, groundnuts, spices, tree nuts.
- **Principle:** Aflatoxin B1 fluoresces characteristic greenish-blue light under narrow-band ultraviolet excitation (365 nm UV-A).
- **Execution Pipeline:**
  1. Phased exposure sequencing to capture baseline ambient reflection followed by stimulated fluorescence.
  2. Contrast-Limited Adaptive Histogram Equalization (CLAHE) and HSV color-space thresholding to isolate fluorescence peaks.
  3. YOLOv8 Deep Learning verification (`aflatoxin.onnx`) combined with localized pixel-intensity density analysis.
  4. Real-time batch criteria evaluation with configurable safety thresholds (e.g., maximum allowable defect ratio).

### 3. 3D Geometric Volume & Fill-Level Verification
- **Target Packaging:** Bottled beverages, liquid pharmaceuticals, condiment jars, vials.
- **Measurement Mechanics:**
  - Automated edge and meniscus detection across dynamic region-of-interest (ROI).
  - Metric scaling based on reference container fiducials (pixel-to-millimeter transform).
  - Liquid column height and meniscus curvature analysis.
  - Classification into **Pass**, **Underfill**, or **Overfill** against configurable tolerance bounds.

---

## Hardware & NPU Acceleration Architecture

Pocket QC prioritizes hardware acceleration on Qualcomm Snapdragon platforms (Snapdragon 8 Elite, 8 Gen 3, 8 Gen 2, 7+ Gen 3) using Qualcomm QNN HTP (Hexagon Tensor Processor):

```
                        ┌──────────────────────────────┐
                        │   CameraX 30 FPS Stream      │
                        └──────────────┬───────────────┘
                                       │ Atomic handoff (<0.1ms)
                        ┌──────────────▼───────────────┐
                        │   YoloPreallocatedBuffers    │
                        │  (Direct Native ByteBuffers) │
                        └──────────────┬───────────────┘
                                       │ Zero-copy tensor slice
                        ┌──────────────▼───────────────┐
                        │      ONNX Runtime Mobile     │
                        │   ┌──────────────────────┐   │
                        │   │   QNN HTP Provider   │   │
                        │   └──────────┬───────────┘   │
                        └──────────────┼───────────────┘
                                       │ Hardware NPU Pipeline
                        ┌──────────────▼───────────────┐
                        │   Qualcomm Hexagon HTP NPU   │
                        │  (~2.8 ms - 4.2 ms Latency)  │
                        └──────────────┬───────────────┘
                                       │ In-place Postprocessing
                        ┌──────────────▼───────────────┐
                        │  Class-Partitioned Fast NMS  │
                        │    & Centroid Object Tracker │
                        └──────────────────────────────┘
```

### Strict NPU-First Policy
- **No Silent Fallback:** In production inspection mode, automatic CPU fallback is disabled. If the NPU hardware delegate cannot compile or schedule graph operators, the inspection halts with explicit diagnostics.
- **Performance Characteristics:**
  - **Snapdragon HTP NPU Latency:** ~2.5 ms – 4.5 ms per 640×640 frame.
  - **CPU Baseline Latency (ARM Cortex-X4/A720):** ~35 ms – 65 ms per frame.
  - **Thermal & Power Footprint:** NPU execution yields a ~4.8× reduction in device thermals and battery discharge rate during continuous 30-minute conveyor inspection runs.

---

## Zero-Allocation Memory Management

Standard mobile computer vision pipelines generate hundreds of megabytes of short-lived objects per minute (`Bitmap` allocations, `FloatBuffer` allocations, bounding-box wrappers), triggering frequent Android Dalvik/ART GC pauses (15–60 ms), dropping frames on moving conveyor belts.

Pocket QC implements `YoloPreallocatedBuffers`:
1. **Direct Native Buffer Pool:** Reusable native `ByteBuffer` instances sized specifically for `1 × 3 × 640 × 640 × 4` float tensors (4.92 MB).
2. **Reused Canvas & Letterbox Bitmaps:** Hardware-backed source and target bitmaps are allocated once during session initialization and mutated in-place.
3. **Primitive Array Pools:** Box coordinates, confidence scores, and class IDs are unpacked directly into preallocated primitive arrays, yielding **zero per-frame GC churn**.

---

## Local Distributed Inspection Network

Pocket QC enables multi-station factory line inspection without cloud infrastructure or external internet access:

```
                          ┌───────────────────────────┐
                          │   Station 0 (Master)      │
                          │ Inspection Server Manager │
                          │ (Embedded NanoHTTPD / WS) │
                          └─────────────▲─────────────┘
                                        │ Local Wi-Fi / AP Mesh
                ┌───────────────────────┼───────────────────────┐
                │                       │                       │
   ┌────────────┴──────────┐ ┌──────────┴──────────┐ ┌──────────┴──────────┐
   │  Station 1 (Node)     │ │  Station 2 (Node)     │ │  Station 3 (Node)     │
   │ 2D Biscuit Inspection │ │  UV Aflatoxin Check   │ │ 3D Fill-Level Check   │
   └───────────────────────┘ └───────────────────────┘ └───────────────────────┘
```

- **Inspection Server Manager:** Runs an embedded `NanoHTTPD` HTTP and WebSocket server on the designated line supervisor device.
- **Inspection Node Coordinator:** Worker devices communicate over local Wi-Fi / Hotspot using `OkHttp`, publishing live heartbeats, frame counts, defect alerts, and batch tallies.
- **Consolidated Batch Ledgers:** The server aggregates cross-station telemetry in real-time, providing plant managers with end-to-end quality visibility across multiple sequential check points.

---

## Tamper-Evident QR Batch Passports

At the completion of a production run, Pocket QC compiles a tamper-evident digital certificate rendered as a high-density QR code (ZXing):

```
┌─────────────────────────────────────────────────────────────┐
│                    POCKET QC BATCH PASSPORT                 │
├─────────────────────────────────────────────────────────────┤
│ Batch ID:          BATCH-20261011-8842                      │
│ Inspection Type:   2D_BISCUIT_DEFECT                        │
│ Total Units:       4,250                                    │
│ Defect Breakdown:  Broken: 42 (0.98%) | Fracture: 21 (0.49%)│
│ Pass Yield:        98.53%                                   │
│ Station ID:        STATION_ALPHA_01                         │
│ Supervisor ID:     QC_OP_4402                               │
│ Timestamp:         2026-10-11T13:00:00Z                     │
│ SHA-256 Digest:    e3b0c44298fc1c149afbf4c8996fb92427ae41e4 │
└─────────────────────────────────────────────────────────────┘
```

Downstream warehouse, logistics, or retail personnel can scan the batch passport on any standard QR scanner or Pocket QC reader to verify batch authenticity and audit logs completely offline.

---

## Isolated Experimental Model Lab

To enable continuous improvement without risking production line safety, Pocket QC contains an isolated **Model Lab**:

- **Immutable Production Model:** `app/src/main/assets/biscuits_final.onnx` is strictly read-only and sealed. It cannot be overwritten, hot-swapped, or modified by experimental features.
- **Dedicated Experimental Workspace:** Captured calibration images, custom operator annotations, training manifests, and experimental checkpoints reside in a segregated private sandbox (`context.filesDir/experimental_lab/`).
- **Interactive Calibration Workflow:**
  1. Capture sample images directly on the production line.
  2. Assign target defect classes.
  3. Run on-device feature extraction and validation sweeps.
  4. Compare experimental precision/recall curves side-by-side against baseline metrics without affecting production operations.

---

## Technology Stack

| Component | Technology | Rationale |
|:---|:---|:---|
| **OS & SDK** | Android 14+ (minSdk 28, targetSdk 35, compileSdk 36) | Latest platform security, modern camera capabilities, and NDK performance. |
| **Language** | Kotlin 2.0+ (JVM 17) | Concise, null-safe language with first-class coroutine concurrency. |
| **UI Framework** | Jetpack Compose & Material 3 | Declarative, reactive UI architecture supporting real-time HUD rendering at 60 FPS. |
| **Camera Layer** | AndroidX CameraX (Core, Camera2, Lifecycle, View) | Robust lifecycle management, zero-copy frame analysis, lockable exposure & focus. |
| **Neural Inference**| ONNX Runtime Mobile (`onnxruntime-android`) | Native cross-platform ML engine with direct hardware execution provider bindings. |
| **NPU Acceleration**| Qualcomm QNN HTP Execution Provider | Maximum throughput and minimum power consumption on Qualcomm Snapdragon hardware. |
| **Computer Vision** | OpenCV Mobile / Android Native Graphics | Color-space transformations, CLAHE enhancement, contour analysis, and edge detection. |
| **QR Generation** | ZXing Core | Offline, high-density 2D barcode generation for tamper-evident batch passports. |
| **Networking** | NanoHTTPD & NanoHTTPD-WebSocket, OkHttp | Lightweight embedded HTTP/WebSocket server for local mesh multi-node synchronization. |
| **Persistence** | Local JSON File Storage (`PersistentBatchManager`) | Zero-dependency, air-gapped, atomic file operations on app-private storage. |

---

## Project Directory Structure

```
Pocket-QC/
├── app/
│   ├── build.gradle.kts                # Application build script & ABI filtering
│   ├── proguard-rules.pro              # R8 minification & JNI keep rules
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml     # Permissions & hardware feature declarations
│       │   ├── assets/
│       │   │   ├── biscuits_final.onnx # Production 2D biscuit inspection model
│       │   │   ├── aflatoxin.onnx      # Production UV Aflatoxin screening model
│       │   │   └── test_images/        # Reference test images for static diagnostics
│       │   └── java/com/pocketqc/
│       │       ├── batch/              # Batch persistence, audit records & state
│       │       ├── camera/             # CameraX source, preview & frame analysis
│       │       ├── core/               # Domain models, detections & bounding boxes
│       │       ├── distributed/        # Multi-node server (NanoHTTPD) & client
│       │       ├── inference/          # ONNX Runtime engine, pre/postprocessors, buffers
│       │       ├── lab/                # Isolated experimental model training sandbox
│       │       ├── ui/                 # Jetpack Compose UI screens, themes & navigation
│       │       ├── uv/                 # UV multi-spectral optical screening engine
│       │       └── volume3d/           # 3D geometric fill-level measurement engine
│       └── test/                       # Unit tests & engine validation suites
├── gradle/                             # Gradle wrapper and version catalog
├── ARCHITECTURE_AND_WORKFLOW.md        # Comprehensive system specification & MVI design
├── build.gradle.kts                    # Root build script
├── gradle.properties                   # JVM heap and build flags
└── README.md                           # Project documentation
```

---

## Building and Running

### Prerequisites
1. **Android Studio:** Ladybug (2024.2.1+) or newer.
2. **JDK:** OpenJDK 17.
3. **Android SDK:** API Level 34+ installed with NDK (`arm64-v8a`).
4. **Target Device:** Qualcomm Snapdragon device (e.g., iQOO 15 / Snapdragon 8 Elite, Galaxy S24 / Snapdragon 8 Gen 3) running Android 14+ with USB debugging enabled.

### 1. Clone the Repository
```bash
git clone https://github.com/kalyanSrinivas-G21/Pocket-QC.git
cd Pocket-QC
```

### 2. Run Verification & Unit Tests
```bash
./gradlew testDebugUnitTest
```

### 3. Assemble Debug APK
```bash
./gradlew assembleDebug
```
The compiled APK will be located at:
`app/build/outputs/apk/debug/app-debug.apk`

### 4. Install onto Connected Device via ADB
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 5. Launch the Application
```bash
adb shell am start -n com.pocketqc/.MainActivity
```

---

## Static Model Diagnostics

Pocket QC includes built-in offline diagnostics to verify neural network accuracy and execution provider registration against bundled reference samples:

```kotlin
// Example programmatic invocation of the diagnostic test suite:
val report = StaticModelDiagnostic.runDiagnostic(
    context = applicationContext,
    modelConfig = ModelConfig.BISCUITS_FINAL,
    targetBackend = HardwareBackend.QNN_NPU
)

println("Total Tested: ${report.totalSamplesTested}")
println("Average NPU Inference: ${report.averageInferenceMs} ms")
```

---

## Security & Privacy Guarantee

- **Zero Cloud Tracking:** Pocket QC sends zero telemetry, analytics, or crash reports to external endpoints.
- **Air-Gapped Operation:** All neural network weights, configuration files, and batch audit records remain exclusively within protected device sandbox storage (`/data/user/0/com.pocketqc/`).
- **Cryptographic Auditability:** Batch summary records are hashed using SHA-256 for non-repudiation across manufacturing and logistics handoffs.

---

## License

This software and its associated model artifacts are proprietary and confidential.  
Copyright &copy; 2026 Pocket QC Project Contributors. All rights reserved.
