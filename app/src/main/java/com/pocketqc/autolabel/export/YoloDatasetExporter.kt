package com.pocketqc.autolabel.export

import com.pocketqc.autolabel.model.AnnotationStatus
import com.pocketqc.autolabel.model.AutoLabelClass
import com.pocketqc.autolabel.model.AutoLabelImageItem
import com.pocketqc.autolabel.model.AutoLabelQualityReport
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Disjoint dataset partitions for YOLO object detection training.
 */
enum class DatasetSplit(val dirName: String) {
    TRAIN("train"),
    VAL("val"),
    TEST("test")
}

/**
 * Configuration for deterministic, leakage-safe dataset splitting.
 */
data class DatasetSplitConfig(
    val trainRatio: Float = 0.70f,
    val valRatio: Float = 0.20f,
    val testRatio: Float = 0.10f,
    val minSamplesForVal: Int = 3,
    val minSamplesForTest: Int = 10,
    val burstWindowMs: Long = 3000L
)

/**
 * Summary metrics for a single partition.
 */
data class SplitSummary(
    val split: DatasetSplit,
    val imageCount: Int,
    val boxCount: Int,
    val classDistribution: Map<Int, Int> = emptyMap(),
    val groupIds: Set<String> = emptySet()
)

/**
 * Result of an export operation.
 */
data class ExportResult(
    val exportedImageCount: Int,
    val exportedBoxCount: Int,
    val skippedUnapprovedCount: Int,
    val datasetYamlFile: File,
    val manifestJsonFile: File,
    val qualityReportFile: File,
    val exportDir: File,
    val splitSummaries: Map<DatasetSplit, SplitSummary> = emptyMap(),
    val warnings: List<String> = emptyList(),
    val validationErrors: List<String> = emptyList(),
    val isStructurallyValid: Boolean = true,
    val backupDir: File? = null
) {
    // Backward-compatibility properties
    val imagesDir: File get() = File(exportDir, "images")
    val labelsDir: File get() = File(exportDir, "labels")

    fun getSplitImagesDir(split: DatasetSplit): File = File(imagesDir, split.dirName)
    fun getSplitLabelsDir(split: DatasetSplit): File = File(labelsDir, split.dirName)
}

/**
 * Represents a group of images that MUST remain in the same partition to prevent data leakage.
 * Grouping is based on identical content hashes and/or capture session bursts.
 */
data class DatasetGroup(
    val groupId: String,
    val items: List<AutoLabelImageItem>,
    val fileHashes: Set<String>
)

/**
 * Self-validation report for an exported dataset.
 */
data class DatasetValidationReport(
    val isValid: Boolean,
    val errors: List<String>,
    val warnings: List<String>,
    val splitImageCounts: Map<DatasetSplit, Int> = emptyMap(),
    val splitLabelCounts: Map<DatasetSplit, Int> = emptyMap()
)

class DatasetExportValidationException(message: String, val errors: List<String>) : RuntimeException(message)

/**
 * Exporter for YOLO-compatible object detection datasets.
 *
 * Guarantees:
 * 1. Strict approval gating: exports ONLY approved samples with human-verified annotations.
 * 2. Complete partition isolation: disjoint `images/train`, `images/val`, `images/test` directories.
 * 3. Zero data leakage: groups identical content hashes and capture bursts into a single partition.
 * 4. Deterministic, reproducible partition splitting.
 * 5. Pre- and post-export structural validation of YOLO files, coordinates, and YAML pointers.
 * 6. Non-destructive backup of existing exports.
 */
object YoloDatasetExporter {

    /**
     * Converts coordinates `(x1, y1, x2, y2)` in pixel space to normalized YOLO coordinates:
     * `x_center = ((x1 + x2) / 2) / image_width`
     * `y_center = ((y1 + y2) / 2) / image_height`
     * `width = (x2 - x1) / image_width`
     * `height = (y2 - y1) / image_height`
     */
    fun convertPixelToYolo(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        imageWidth: Int,
        imageHeight: Int
    ): FloatArray {
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive" }
        val cx = ((x1 + x2) / 2f) / imageWidth.toFloat()
        val cy = ((y1 + y2) / 2f) / imageHeight.toFloat()
        val w = (x2 - x1) / imageWidth.toFloat()
        val h = (y2 - y1) / imageHeight.toFloat()
        return floatArrayOf(
            cx.coerceIn(0f, 1f),
            cy.coerceIn(0f, 1f),
            w.coerceIn(0f, 1f),
            h.coerceIn(0f, 1f)
        )
    }

    /**
     * Groups dataset items by content hash and capture session burst to prevent data leakage.
     * Any two images with matching SHA-256 hashes OR from the same capture source within
     * `burstWindowMs` are placed in the same group.
     */
    fun groupItems(
        items: List<AutoLabelImageItem>,
        burstWindowMs: Long = 3000L
    ): List<DatasetGroup> {
        if (items.isEmpty()) return emptyList()

        val n = items.size
        val parent = IntArray(n) { it }

        fun find(i: Int): Int {
            var root = i
            while (root != parent[root]) {
                root = parent[root]
            }
            var curr = i
            while (curr != root) {
                val next = parent[curr]
                parent[curr] = root
                curr = next
            }
            return root
        }

        fun union(i: Int, j: Int) {
            val rootI = find(i)
            val rootJ = find(j)
            if (rootI != rootJ) {
                parent[rootI] = rootJ
            }
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val itemA = items[i]
                val itemB = items[j]

                // 1. Duplicate content hash isolation
                val isDuplicate = itemA.fileHash.isNotBlank() &&
                        itemA.fileHash.equals(itemB.fileHash, ignoreCase = true)

                // 2. Capture session / burst metadata isolation
                val isBurst = itemA.source.isNotBlank() &&
                        itemA.source.equals(itemB.source, ignoreCase = true) &&
                        abs(itemA.timestamp - itemB.timestamp) <= burstWindowMs

                if (isDuplicate || isBurst) {
                    union(i, j)
                }
            }
        }

        val groupMap = mutableMapOf<Int, MutableList<AutoLabelImageItem>>()
        for (i in 0 until n) {
            val root = find(i)
            groupMap.computeIfAbsent(root) { mutableListOf() }.add(items[i])
        }

        return groupMap.values.map { groupItemsList ->
            val sortedItems = groupItemsList.sortedBy { it.id }
            val primaryHash = sortedItems.map { it.fileHash.lowercase(Locale.ROOT) }.firstOrNull { it.isNotBlank() } ?: "nohash"
            val minId = sortedItems.minOfOrNull { it.id } ?: "unknown"
            val groupId = "grp_${primaryHash}_${minId}"
            DatasetGroup(
                groupId = groupId,
                items = sortedItems,
                fileHashes = sortedItems.map { it.fileHash.lowercase(Locale.ROOT) }.toSet()
            )
        }.sortedBy { it.groupId }
    }

    /**
     * Deterministically partitions grouped items into TRAIN, VAL, and TEST splits.
     * Enforces small-dataset safety policies and prevents group cross-contamination.
     */
    fun partitionGroups(
        groups: List<DatasetGroup>,
        config: DatasetSplitConfig = DatasetSplitConfig()
    ): Pair<Map<DatasetSplit, List<AutoLabelImageItem>>, List<String>> {
        val warnings = mutableListOf<String>()
        val totalItems = groups.sumOf { it.items.size }

        val trainItems = mutableListOf<AutoLabelImageItem>()
        val valItems = mutableListOf<AutoLabelImageItem>()
        val testItems = mutableListOf<AutoLabelImageItem>()

        when {
            // Case 1: Dataset is empty
            totalItems == 0 -> {
                // Return empty splits
            }

            // Case 2: Insufficient samples for validation partition (< minSamplesForVal)
            totalItems < config.minSamplesForVal -> {
                warnings.add(
                    "Dataset contains only $totalItems approved sample(s) (< ${config.minSamplesForVal}). " +
                            "All samples assigned to 'train'; 'val' and 'test' are empty. " +
                            "Minimum ${config.minSamplesForVal} samples required for meaningful validation partition."
                )
                for (g in groups) {
                    trainItems.addAll(g.items)
                }
            }

            // Case 3: All samples belong to a single cluster/group
            groups.size == 1 -> {
                warnings.add(
                    "All $totalItems samples belong to a single group (${groups[0].groupId}) due to duplicate hashes " +
                            "or burst capture. Splitting would cause 100% data leakage; all samples assigned to 'train'."
                )
                trainItems.addAll(groups[0].items)
            }

            // Case 4: Small dataset (< minSamplesForTest) -> 2-way split (train + val only)
            totalItems < config.minSamplesForTest -> {
                warnings.add(
                    "Dataset contains $totalItems approved sample(s) (< ${config.minSamplesForTest}). " +
                            "Split between 'train' and 'val' only; 'test' partition is omitted."
                )
                // Deterministic allocation: guarantee at least 1 group in val
                val sortedGroups = groups.sortedWith(
                    compareByDescending<DatasetGroup> { it.items.size }.thenBy { it.groupId }
                )
                val targetVal = ((totalItems * config.valRatio) / (config.trainRatio + config.valRatio))
                    .roundToInt().coerceAtLeast(1)

                var currentValCount = 0
                for (g in sortedGroups) {
                    if (currentValCount < targetVal && (currentValCount + g.items.size <= targetVal || currentValCount == 0)) {
                        valItems.addAll(g.items)
                        currentValCount += g.items.size
                    } else {
                        trainItems.addAll(g.items)
                    }
                }
                // Safety check: ensure train is never empty if we have >= 2 groups
                if (trainItems.isEmpty() && valItems.isNotEmpty()) {
                    val moved = valItems.removeAt(0)
                    trainItems.add(moved)
                }
            }

            // Case 5: Large dataset -> full 3-way split (train, val, test)
            else -> {
                val targetTest = (totalItems * config.testRatio).roundToInt().coerceAtLeast(1)
                val targetVal = (totalItems * config.valRatio).roundToInt().coerceAtLeast(1)

                val sortedGroups = groups.sortedWith(
                    compareByDescending<DatasetGroup> { it.items.size }.thenBy { it.groupId }
                )

                var currentTestCount = 0
                var currentValCount = 0

                for (g in sortedGroups) {
                    val size = g.items.size
                    when {
                        currentTestCount + size <= targetTest -> {
                            testItems.addAll(g.items)
                            currentTestCount += size
                        }
                        currentValCount + size <= targetVal -> {
                            valItems.addAll(g.items)
                            currentValCount += size
                        }
                        else -> {
                            trainItems.addAll(g.items)
                        }
                    }
                }

                // If test or val was starved due to coarse group granularity, rebalance safely
                if (testItems.isEmpty() && sortedGroups.size >= 3) {
                    val candidate = sortedGroups.firstOrNull { it.items !in listOf(trainItems, valItems) }
                    if (candidate != null) {
                        trainItems.removeAll(candidate.items)
                        testItems.addAll(candidate.items)
                    }
                }
                if (valItems.isEmpty() && sortedGroups.size >= 2) {
                    val candidate = sortedGroups.firstOrNull { it.items !in listOf(testItems) }
                    if (candidate != null) {
                        trainItems.removeAll(candidate.items)
                        valItems.addAll(candidate.items)
                    }
                }
            }
        }

        // Check for missing classes in train partition
        val allBoxes = (trainItems + valItems + testItems).flatMap { it.annotations }
        val overallClassIds = allBoxes.map { it.assignedClass.classId }.toSet()
        val trainClassIds = trainItems.flatMap { it.annotations }.map { it.assignedClass.classId }.toSet()

        for (cid in overallClassIds) {
            if (cid !in trainClassIds) {
                val name = AutoLabelClass.fromId(cid)?.displayName ?: "Class $cid"
                warnings.add("Class '$name' (ID $cid) has zero annotations in the 'train' partition.")
            }
        }

        val splitMap = mapOf(
            DatasetSplit.TRAIN to trainItems.sortedBy { it.id },
            DatasetSplit.VAL to valItems.sortedBy { it.id },
            DatasetSplit.TEST to testItems.sortedBy { it.id }
        )

        return Pair(splitMap, warnings)
    }

    /**
     * Exports approved dataset items to the specified directory with disjoint YOLO partitions.
     */
    fun exportToYolo(
        items: List<AutoLabelImageItem>,
        exportDir: File,
        qualityReport: AutoLabelQualityReport? = null,
        splitConfig: DatasetSplitConfig = DatasetSplitConfig()
    ): ExportResult {
        val validationErrors = mutableListOf<String>()

        // 1. Non-destructive backup of existing exports if present
        var backupDir: File? = null
        if (exportDir.exists() && exportDir.list()?.isNotEmpty() == true) {
            backupDir = File(exportDir.parentFile, "${exportDir.name}_backup_${System.currentTimeMillis()}")
            exportDir.copyRecursively(backupDir, overwrite = true)
        }

        // Clean and prepare directory structure
        if (exportDir.exists()) {
            exportDir.deleteRecursively()
        }
        exportDir.mkdirs()

        val imagesDir = File(exportDir, "images").apply { mkdirs() }
        val labelsDir = File(exportDir, "labels").apply { mkdirs() }

        // Create disjoint subdirectories for all three partitions
        for (split in DatasetSplit.entries) {
            File(imagesDir, split.dirName).mkdirs()
            File(labelsDir, split.dirName).mkdirs()
        }

        // 2. Strict approval gating
        val approvedItems = items.filter { it.reviewStatus == AnnotationStatus.APPROVED }
        val skippedCount = items.size - approvedItems.size

        // 3. Prevent data leakage via grouping and deterministic partitioning
        val groups = groupItems(approvedItems, splitConfig.burstWindowMs)
        val (splits, splitWarnings) = partitionGroups(groups, splitConfig)
        val allWarnings = splitWarnings.toMutableList()

        // Map item ID to its assigned group
        val itemToGroupMap = mutableMapOf<String, DatasetGroup>()
        for (g in groups) {
            for (item in g.items) {
                itemToGroupMap[item.id] = g
            }
        }

        var totalExportedBoxes = 0
        val splitSummaries = mutableMapOf<DatasetSplit, SplitSummary>()
        val manifestItems = JSONArray()

        // 4. Export files per partition
        for ((split, splitItemList) in splits) {
            val splitImagesDir = File(imagesDir, split.dirName)
            val splitLabelsDir = File(labelsDir, split.dirName)

            var splitBoxCount = 0
            val classDistribution = mutableMapOf<Int, Int>()
            val groupIdsInSplit = mutableSetOf<String>()

            for (item in splitItemList) {
                val group = itemToGroupMap[item.id]
                if (group != null) {
                    groupIdsInSplit.add(group.groupId)
                }

                val sourceImgFile = File(item.filePath)
                if (!sourceImgFile.exists()) {
                    validationErrors.add("Source image missing on disk: ${item.filePath}")
                    continue
                }

                // Copy image file to images/<split>/
                val destImgFile = File(splitImagesDir, item.fileName)
                copyFile(sourceImgFile, destImgFile)

                // Generate YOLO label file (.txt) in labels/<split>/
                val baseName = item.fileName.substringBeforeLast('.')
                val labelFile = File(splitLabelsDir, "$baseName.txt")
                val labelLines = mutableListOf<String>()
                val itemManifestBoxes = JSONArray()

                for (box in item.annotations) {
                    // Strict verification: only human-verified annotations allowed
                    if (!box.isHumanVerified) {
                        validationErrors.add("Unverified box in approved item ${item.fileName} was excluded")
                        continue
                    }

                    val yoloCoords = box.toYoloCoordinates()
                    val classId = box.assignedClass.classId

                    // Validate YOLO coordinates and class ID
                    if (classId !in 0..2) {
                        validationErrors.add("Invalid class ID $classId in ${item.fileName}")
                        continue
                    }
                    if (yoloCoords.xCenter !in 0f..1f || yoloCoords.yCenter !in 0f..1f) {
                        validationErrors.add("Out of bounds center [${yoloCoords.xCenter}, ${yoloCoords.yCenter}] in ${item.fileName}")
                        continue
                    }
                    if (yoloCoords.width <= 0f || yoloCoords.height <= 0f) {
                        validationErrors.add("Degenerate box dimensions width=${yoloCoords.width}, height=${yoloCoords.height} in ${item.fileName}")
                        continue
                    }

                    val line = yoloCoords.toYoloLine(classId)
                    labelLines.add(line)
                    splitBoxCount++
                    totalExportedBoxes++
                    classDistribution[classId] = (classDistribution[classId] ?: 0) + 1

                    itemManifestBoxes.put(JSONObject().apply {
                        put("boxId", box.boxId)
                        put("classId", classId)
                        put("className", box.assignedClass.displayName)
                        put("xCenter", yoloCoords.xCenter.toDouble())
                        put("yCenter", yoloCoords.yCenter.toDouble())
                        put("width", yoloCoords.width.toDouble())
                        put("height", yoloCoords.height.toDouble())
                        put("confidence", box.confidence.toDouble())
                        put("isHumanVerified", box.isHumanVerified)
                        put("source", box.source.name)
                    })
                }

                // Write label file (empty file written if zero valid boxes)
                labelFile.writeText(labelLines.joinToString("\n"))

                // Add to manifest
                manifestItems.put(JSONObject().apply {
                    put("id", item.id)
                    put("fileName", item.fileName)
                    put("fileHash", item.fileHash)
                    put("groupId", group?.groupId ?: "unknown")
                    put("split", split.dirName)
                    put("imageWidth", item.imageWidth)
                    put("imageHeight", item.imageHeight)
                    put("boxes", itemManifestBoxes)
                })
            }

            splitSummaries[split] = SplitSummary(
                split = split,
                imageCount = splitItemList.size,
                boxCount = splitBoxCount,
                classDistribution = classDistribution,
                groupIds = groupIdsInSplit
            )
        }

        // 5. Generate dataset.yaml pointing to separate split directories
        val datasetYaml = File(exportDir, "dataset.yaml")
        val yamlContent = buildString {
            appendLine("# Pocket QC Auto-Labeling PoC — Exported YOLOv8 Dataset")
            appendLine("# Classes: 0 = Intact, 1 = Fracture, 2 = Broken")
            appendLine("path: .")
            appendLine("train: images/${DatasetSplit.TRAIN.dirName}")
            appendLine("val: images/${DatasetSplit.VAL.dirName}")
            appendLine("test: images/${DatasetSplit.TEST.dirName}")
            appendLine("")
            appendLine("names:")
            appendLine("  0: Intact")
            appendLine("  1: Fracture")
            appendLine("  2: Broken")
        }
        datasetYaml.writeText(yamlContent)

        // 6. Generate manifest.json
        val manifestFile = File(exportDir, "manifest.json")
        val manifestObj = JSONObject().apply {
            put("exportTimestamp", System.currentTimeMillis())
            put("totalExportedImages", approvedItems.size)
            put("totalExportedBoxes", totalExportedBoxes)
            put("groupsCount", groups.size)
            put("classes", JSONObject().apply {
                put("0", "Intact")
                put("1", "Fracture")
                put("2", "Broken")
            })
            put("splitSummaries", JSONObject().apply {
                for ((split, sum) in splitSummaries) {
                    put(split.dirName, JSONObject().apply {
                        put("imageCount", sum.imageCount)
                        put("boxCount", sum.boxCount)
                        put("classDistribution", JSONObject(sum.classDistribution))
                        put("groupIds", JSONArray(sum.groupIds))
                    })
                }
            })
            put("warnings", JSONArray(allWarnings))
            put("items", manifestItems)
        }
        manifestFile.writeText(manifestObj.toString(2))

        // 7. Generate quality summary report
        val qualityFile = File(exportDir, "quality_report.json")
        val qualityObj = JSONObject().apply {
            put("exportTimestamp", System.currentTimeMillis())
            put("totalImagesEvaluated", items.size)
            put("approvedAndExported", approvedItems.size)
            put("skippedUnapproved", skippedCount)
            put("totalExportedBoxes", totalExportedBoxes)
            put("validationErrors", JSONArray(validationErrors))
            put("warnings", JSONArray(allWarnings))
            put("splitBreakdown", JSONObject().apply {
                for ((split, sum) in splitSummaries) {
                    put(split.dirName, JSONObject().apply {
                        put("images", sum.imageCount)
                        put("boxes", sum.boxCount)
                        put("classes", JSONObject(sum.classDistribution))
                    })
                }
            })
            if (qualityReport != null) {
                put("meanProcessingTimeMs", qualityReport.meanProcessingTimeMs)
                put("humanCorrectionRate", qualityReport.humanCorrectionRate.toDouble())
                put("invalidBoxesDetected", qualityReport.invalidBoxCount)
                put("duplicateBoxesDetected", qualityReport.duplicateBoxCount)
                qualityReport.benchmarkEvaluation?.let { b ->
                    put("benchmarkMeanIoU", b.meanIoU.toDouble())
                    put("benchmarkAgreementRate", b.classificationAgreementRate.toDouble())
                }
            }
        }
        qualityFile.writeText(qualityObj.toString(2))

        // 8. Self-validation of the generated dataset on disk
        val validationReport = validateExportedDataset(exportDir)
        validationErrors.addAll(validationReport.errors)
        allWarnings.addAll(validationReport.warnings)

        return ExportResult(
            exportedImageCount = approvedItems.size,
            exportedBoxCount = totalExportedBoxes,
            skippedUnapprovedCount = skippedCount,
            datasetYamlFile = datasetYaml,
            manifestJsonFile = manifestFile,
            qualityReportFile = qualityFile,
            exportDir = exportDir,
            splitSummaries = splitSummaries,
            warnings = allWarnings.distinct(),
            validationErrors = validationErrors.distinct(),
            isStructurallyValid = validationReport.isValid && validationErrors.isEmpty(),
            backupDir = backupDir
        )
    }

    /**
     * Validates the structural integrity and leakage-safety of an exported YOLO dataset directory.
     */
    fun validateExportedDataset(exportDir: File): DatasetValidationReport {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val yamlFile = File(exportDir, "dataset.yaml")
        if (!yamlFile.exists()) {
            errors.add("dataset.yaml missing in export directory")
            return DatasetValidationReport(false, errors, warnings)
        }

        val yamlText = yamlFile.readText()
        val trainLine = yamlText.lines().firstOrNull { it.trim().startsWith("train:") }?.substringAfter(":")?.trim()
        val valLine = yamlText.lines().firstOrNull { it.trim().startsWith("val:") }?.substringAfter(":")?.trim()
        val testLine = yamlText.lines().firstOrNull { it.trim().startsWith("test:") }?.substringAfter(":")?.trim()

        if (trainLine.isNullOrBlank() || valLine.isNullOrBlank() || testLine.isNullOrBlank()) {
            errors.add("dataset.yaml missing train, val, or test entries")
        } else {
            // Critical check: splits must NOT point to the same directory
            if (trainLine == valLine || trainLine == testLine || valLine == testLine) {
                errors.add("Critical data leakage: dataset.yaml has identical split paths ($trainLine, $valLine, $testLine)")
            }
        }

        // Verify required directories exist
        val splitImageCounts = mutableMapOf<DatasetSplit, Int>()
        val splitLabelCounts = mutableMapOf<DatasetSplit, Int>()
        val partitionImages = mutableMapOf<DatasetSplit, Set<String>>()
        val partitionLabels = mutableMapOf<DatasetSplit, Set<String>>()

        for (split in DatasetSplit.entries) {
            val imgDir = File(exportDir, "images/${split.dirName}")
            val lblDir = File(exportDir, "labels/${split.dirName}")

            if (!imgDir.exists()) errors.add("Directory missing: ${imgDir.path}")
            if (!lblDir.exists()) errors.add("Directory missing: ${lblDir.path}")

            val imgFiles = imgDir.listFiles()?.filter { it.isFile } ?: emptyList()
            val lblFiles = lblDir.listFiles()?.filter { it.isFile } ?: emptyList()

            splitImageCounts[split] = imgFiles.size
            splitLabelCounts[split] = lblFiles.size

            partitionImages[split] = imgFiles.map { it.name }.toSet()
            partitionLabels[split] = lblFiles.map { it.name }.toSet()

            // Verify 1:1 image to label pairing within split
            for (img in imgFiles) {
                val base = img.name.substringBeforeLast('.')
                val expectedLbl = File(lblDir, "$base.txt")
                if (!expectedLbl.exists()) {
                    errors.add("Missing label file for image ${img.name} in split ${split.dirName}")
                }
            }

            for (lbl in lblFiles) {
                val base = lbl.name.substringBeforeLast('.')
                val matchingImg = imgFiles.firstOrNull { it.name.substringBeforeLast('.') == base }
                if (matchingImg == null) {
                    errors.add("Orphaned label file ${lbl.name} without matching image in split ${split.dirName}")
                }

                // Check label file syntax
                lbl.readLines().forEachIndexed { lineIdx, line ->
                    if (line.isNotBlank()) {
                        val tokens = line.trim().split("\\s+".toRegex())
                        if (tokens.size != 5) {
                            errors.add("Malformed YOLO label line in ${lbl.name}:L${lineIdx + 1} (expected 5 tokens, got ${tokens.size})")
                        } else {
                            val cid = tokens[0].toIntOrNull()
                            val cx = tokens[1].toFloatOrNull()
                            val cy = tokens[2].toFloatOrNull()
                            val w = tokens[3].toFloatOrNull()
                            val h = tokens[4].toFloatOrNull()

                            if (cid == null || cid !in 0..2) {
                                errors.add("Invalid class ID in ${lbl.name}:L${lineIdx + 1} ('${tokens[0]}')")
                            }
                            if (cx == null || cy == null || cx !in 0f..1f || cy !in 0f..1f) {
                                errors.add("Invalid coordinates in ${lbl.name}:L${lineIdx + 1}")
                            }
                            if (w == null || h == null || w <= 0f || h <= 0f || w > 1f || h > 1f) {
                                errors.add("Invalid box dimensions in ${lbl.name}:L${lineIdx + 1}")
                            }
                        }
                    }
                }
            }
        }

        // Mutual exclusivity check across partitions (zero image/label leakage)
        val trainImgs = partitionImages[DatasetSplit.TRAIN] ?: emptySet()
        val valImgs = partitionImages[DatasetSplit.VAL] ?: emptySet()
        val testImgs = partitionImages[DatasetSplit.TEST] ?: emptySet()

        val trainValOverlap = trainImgs.intersect(valImgs)
        val trainTestOverlap = trainImgs.intersect(testImgs)
        val valTestOverlap = valImgs.intersect(testImgs)

        if (trainValOverlap.isNotEmpty()) {
            errors.add("Image leakage between train and val: $trainValOverlap")
        }
        if (trainTestOverlap.isNotEmpty()) {
            errors.add("Image leakage between train and test: $trainTestOverlap")
        }
        if (valTestOverlap.isNotEmpty()) {
            errors.add("Image leakage between val and test: $valTestOverlap")
        }

        // Parse manifest to verify group and hash isolation across splits
        val manifestFile = File(exportDir, "manifest.json")
        if (manifestFile.exists()) {
            try {
                val manifestObj = JSONObject(manifestFile.readText())
                val itemsArr = manifestObj.optJSONArray("items") ?: JSONArray()

                val splitGroups = mutableMapOf<String, MutableSet<String>>()
                val splitHashes = mutableMapOf<String, MutableSet<String>>()

                for (i in 0 until itemsArr.length()) {
                    val item = itemsArr.getJSONObject(i)
                    val splitName = item.getString("split")
                    val groupId = item.optString("groupId", "")
                    val fileHash = item.optString("fileHash", "")

                    if (groupId.isNotBlank()) {
                        splitGroups.computeIfAbsent(splitName) { mutableSetOf() }.add(groupId)
                    }
                    if (fileHash.isNotBlank()) {
                        splitHashes.computeIfAbsent(splitName) { mutableSetOf() }.add(fileHash.lowercase(Locale.ROOT))
                    }
                }

                // Check group cross-partition leakage
                for (s1 in listOf("train", "val", "test")) {
                    for (s2 in listOf("train", "val", "test")) {
                        if (s1 < s2) {
                            val g1 = splitGroups[s1] ?: emptySet()
                            val g2 = splitGroups[s2] ?: emptySet()
                            val gOverlap = g1.intersect(g2)
                            if (gOverlap.isNotEmpty()) {
                                errors.add("Group leakage between $s1 and $s2: $gOverlap")
                            }

                            val h1 = splitHashes[s1] ?: emptySet()
                            val h2 = splitHashes[s2] ?: emptySet()
                            val hOverlap = h1.intersect(h2)
                            if (hOverlap.isNotEmpty()) {
                                errors.add("Content hash leakage between $s1 and $s2: $hOverlap")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                warnings.add("Failed to parse manifest.json for cross-partition group check: ${e.message}")
            }
        }

        return DatasetValidationReport(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            splitImageCounts = splitImageCounts,
            splitLabelCounts = splitLabelCounts
        )
    }

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { inStream ->
            FileOutputStream(dst).use { outStream ->
                inStream.copyTo(outStream)
            }
        }
    }
}
