package com.pocketqc.lab.training

import com.pocketqc.lab.model.DatasetSplit
import com.pocketqc.lab.model.EpochLog
import com.pocketqc.lab.model.ExperimentalModelMetadata
import com.pocketqc.lab.model.ExperimentalSample
import com.pocketqc.lab.model.TrainingConfig
import com.pocketqc.lab.model.TrainingProgress
import com.pocketqc.lab.workspace.ExperimentalWorkspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device neural training engine optimized for small datasets (<20 images).
 *
 * Implements:
 * 1. Physical 64-D feature extraction (geometry, cracks, normalized spatial grid, chromaticity)
 * 2. On-the-fly physical data augmentation (flips, rotations, zoom/scale, texture jitter)
 * 3. Class-balanced re-sampling (prevents majority-class bias)
 * 4. Z-score feature standardization (robust numerical scaling)
 * 5. Nearest-Centroid prototype generation for few-shot stability
 * 6. Analytical mini-batch SGD with momentum (0.9) and L2 weight decay
 * 7. Strict production model SHA-256 invariant auditing
 */
class OnDeviceTrainingEngine(
    private val workspace: ExperimentalWorkspace
) {

    private data class SampleData(
        val sample: ExperimentalSample,
        val features: FloatArray,
        val targetIndex: Int
    )

    /**
     * Executes real on-device training and produces an isolated experimental model artifact.
     */
    suspend fun train(
        samples: List<ExperimentalSample>,
        config: TrainingConfig = TrainingConfig(),
        onProgress: (TrainingProgress) -> Unit = {}
    ): Result<ExperimentalModel> = withContext(Dispatchers.Default) {
        runCatching {
            // 1. Audit production model hash BEFORE training
            val prodHashBefore = workspace.computeProductionModelSha256()

            val trainSamples = samples.filter { it.split == DatasetSplit.TRAIN }.ifEmpty { samples }
            val valSamples = samples.filter { it.split == DatasetSplit.VAL }.ifEmpty { trainSamples }

            check(trainSamples.isNotEmpty()) { "Cannot train with empty training dataset. Collect or import samples first." }

            // 2. Extract base 64-D features from disk
            val rawTrainData = loadFeatureData(trainSamples)
            val valData = loadFeatureData(valSamples)

            check(rawTrainData.isNotEmpty()) { "Failed to extract valid training features from biscuit images." }

            val inputDim = 64
            val hiddenDim = config.hiddenUnits
            val outputDim = 3
            val random = Random(42)

            // 3. Physical Data Augmentation (8-10x expansion per sample)
            val augmentedTrainData = mutableListOf<SampleData>()
            for (s in rawTrainData) {
                augmentedTrainData.addAll(augmentSample(s, random))
            }

            // 4. Class-Balanced Re-sampling
            val byClass = augmentedTrainData.groupBy { it.targetIndex }
            val maxClassCount = byClass.values.maxOfOrNull { it.size } ?: 1
            val balancedTrainData = mutableListOf<SampleData>()
            for ((_, classItems) in byClass) {
                var added = 0
                while (added < maxClassCount) {
                    for (item in classItems) {
                        if (added < maxClassCount) {
                            balancedTrainData.add(item)
                            added++
                        }
                    }
                }
            }

            // 5. Z-Score Feature Normalization
            val featureMeans = FloatArray(inputDim)
            val featureStds = FloatArray(inputDim)
            val nTrain = balancedTrainData.size.toFloat()

            for (item in balancedTrainData) {
                for (i in 0 until inputDim) featureMeans[i] += item.features[i]
            }
            for (i in 0 until inputDim) featureMeans[i] /= nTrain

            for (item in balancedTrainData) {
                for (i in 0 until inputDim) {
                    val diff = item.features[i] - featureMeans[i]
                    featureStds[i] += diff * diff
                }
            }
            for (i in 0 until inputDim) {
                featureStds[i] = sqrt(featureStds[i] / nTrain).coerceAtLeast(1e-4f)
            }

            // 6. Nearest-Centroid Class Prototypes
            val classPrototypes = Array(outputDim) { FloatArray(inputDim) }
            val protoCounts = IntArray(outputDim)
            for (item in balancedTrainData) {
                val c = item.targetIndex
                protoCounts[c]++
                for (i in 0 until inputDim) {
                    val z = (item.features[i] - featureMeans[i]) / featureStds[i]
                    classPrototypes[c][i] += z
                }
            }
            for (c in 0 until outputDim) {
                if (protoCounts[c] > 0) {
                    var norm = 0f
                    for (i in 0 until inputDim) {
                        classPrototypes[c][i] /= protoCounts[c]
                        norm += classPrototypes[c][i] * classPrototypes[c][i]
                    }
                    norm = sqrt(norm)
                    if (norm > 1e-6f) {
                        for (i in 0 until inputDim) classPrototypes[c][i] /= norm
                    }
                }
            }

            // 7. Weight Initialization (He Normal)
            val w1 = Array(inputDim) { FloatArray(hiddenDim) { (random.nextGaussian() * sqrt(2.0 / inputDim)).toFloat() } }
            val b1 = FloatArray(hiddenDim) { 0f }
            val w2 = Array(hiddenDim) { FloatArray(outputDim) { (random.nextGaussian() * sqrt(2.0 / hiddenDim)).toFloat() } }
            val b2 = FloatArray(outputDim) { 0f }

            // Momentum velocity buffers
            val vW1 = Array(inputDim) { FloatArray(hiddenDim) }
            val vB1 = FloatArray(hiddenDim)
            val vW2 = Array(hiddenDim) { FloatArray(outputDim) }
            val vB2 = FloatArray(outputDim)

            val epochLogs = mutableListOf<EpochLog>()
            val logFile = File(workspace.logsDir, "training_${System.currentTimeMillis()}.log")
            val logStringBuilder = StringBuilder()
            logStringBuilder.appendLine("=== POCKET QC HIGH-PRECISION ON-DEVICE TRAINING SESSION ===")
            logStringBuilder.appendLine("Timestamp: ${System.currentTimeMillis()}")
            logStringBuilder.appendLine("Config: Epochs=${config.epochs}, LR=${config.learningRate}, Hidden=${config.hiddenUnits}")
            logStringBuilder.appendLine("Dataset: RawTrain=${rawTrainData.size}, AugmentedBalanced=${balancedTrainData.size}, Val=${valData.size}")
            logStringBuilder.appendLine("Production Model Hash Before: $prodHashBefore")
            logStringBuilder.appendLine("----------------------------------------------------------")

            // 8. Main Training Loop
            val totalEpochs = config.epochs
            for (epoch in 1..totalEpochs) {
                val epochStart = System.currentTimeMillis()
                val shuffled = balancedTrainData.shuffled(random)

                var totalTrainLoss = 0f
                var correctTrain = 0

                // Mini-batch updates
                for (batchStart in shuffled.indices step config.batchSize) {
                    val batchEnd = (batchStart + config.batchSize).coerceAtMost(shuffled.size)
                    val batchSizeActual = batchEnd - batchStart

                    val dW1 = Array(inputDim) { FloatArray(hiddenDim) }
                    val dB1 = FloatArray(hiddenDim)
                    val dW2 = Array(hiddenDim) { FloatArray(outputDim) }
                    val dB2 = FloatArray(outputDim)

                    for (idx in batchStart until batchEnd) {
                        val item = shuffled[idx]
                        // Standardize input features
                        val x = FloatArray(inputDim)
                        for (i in 0 until inputDim) {
                            x[i] = (item.features[i] - featureMeans[i]) / featureStds[i]
                        }
                        val target = item.targetIndex

                        // Forward: Hidden layer
                        val h = FloatArray(hiddenDim)
                        val z1 = FloatArray(hiddenDim)
                        for (j in 0 until hiddenDim) {
                            var sum = b1[j]
                            for (i in 0 until inputDim) sum += x[i] * w1[i][j]
                            z1[j] = sum
                            h[j] = max(0f, sum)
                        }

                        // Forward: Logits & Softmax
                        val logits = FloatArray(outputDim)
                        for (k in 0 until outputDim) {
                            var sum = b2[k]
                            for (j in 0 until hiddenDim) sum += h[j] * w2[j][k]
                            logits[k] = sum
                        }

                        var maxLogit = logits[0]
                        for (k in 1 until outputDim) if (logits[k] > maxLogit) maxLogit = logits[k]
                        var sumExp = 0f
                        val expL = FloatArray(outputDim)
                        for (k in 0 until outputDim) {
                            expL[k] = exp((logits[k] - maxLogit).toDouble()).toFloat()
                            sumExp += expL[k]
                        }
                        val probs = FloatArray(outputDim)
                        for (k in 0 until outputDim) probs[k] = expL[k] / sumExp

                        val sampleLoss = -ln(probs[target].coerceAtLeast(1e-7f).toDouble()).toFloat()
                        totalTrainLoss += sampleLoss

                        val predictedClassIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
                        if (predictedClassIdx == target) correctTrain++

                        // Backward: Output layer
                        val dZ2 = FloatArray(outputDim)
                        for (k in 0 until outputDim) {
                            dZ2[k] = probs[k] - (if (k == target) 1f else 0f)
                            dB2[k] += dZ2[k]
                            for (j in 0 until hiddenDim) dW2[j][k] += h[j] * dZ2[k]
                        }

                        // Backward: Hidden layer (ReLU derivative)
                        for (j in 0 until hiddenDim) {
                            var dH = 0f
                            for (k in 0 until outputDim) dH += dZ2[k] * w2[j][k]
                            val dZ1 = if (z1[j] > 0f) dH else 0f
                            dB1[j] += dZ1
                            for (i in 0 until inputDim) dW1[i][j] += x[i] * dZ1
                        }
                    }

                    // Apply gradients with Momentum & L2 Weight Decay
                    val invBatch = 1f / batchSizeActual
                    val lr = config.learningRate
                    val mom = config.momentum
                    val wd = config.weightDecay

                    for (j in 0 until hiddenDim) {
                        for (k in 0 until outputDim) {
                            val grad = (dW2[j][k] * invBatch) + (wd * w2[j][k])
                            vW2[j][k] = (mom * vW2[j][k]) + (lr * grad)
                            w2[j][k] -= vW2[j][k]
                        }
                        val gradB = dB2[j % outputDim] * invBatch
                        vB2[j % outputDim] = (mom * vB2[j % outputDim]) + (lr * gradB)
                        b2[j % outputDim] -= vB2[j % outputDim]
                    }

                    for (i in 0 until inputDim) {
                        for (j in 0 until hiddenDim) {
                            val grad = (dW1[i][j] * invBatch) + (wd * w1[i][j])
                            vW1[i][j] = (mom * vW1[i][j]) + (lr * grad)
                            w1[i][j] -= vW1[i][j]
                        }
                    }
                    for (j in 0 until hiddenDim) {
                        val gradB = dB1[j] * invBatch
                        vB1[j] = (mom * vB1[j]) + (lr * gradB)
                        b1[j] -= vB1[j]
                    }
                }

                val avgTrainLoss = totalTrainLoss / balancedTrainData.size
                val trainAcc = correctTrain.toFloat() / balancedTrainData.size

                // Validation monitoring
                var totalValLoss = 0f
                var correctVal = 0
                val tempModel = ExperimentalModel(
                    metadata = ExperimentalModelMetadata(
                        modelId = "tmp",
                        version = 1,
                        creationTimestamp = 0L,
                        trainSampleCount = rawTrainData.size,
                        valSampleCount = valData.size,
                        epochs = epoch,
                        finalAccuracy = 0f,
                        artifactFileName = "tmp",
                        artifactPath = "tmp"
                    ),
                    inputDim = inputDim,
                    hiddenDim = hiddenDim,
                    outputDim = outputDim,
                    w1 = w1,
                    b1 = b1,
                    w2 = w2,
                    b2 = b2,
                    featureMeans = featureMeans,
                    featureStds = featureStds,
                    classPrototypes = classPrototypes
                )

                for (item in valData) {
                    val res = tempModel.predictFeatures(item.features)
                    val target = item.targetIndex
                    totalValLoss += -ln(res.probabilities[target].coerceAtLeast(1e-7f).toDouble()).toFloat()
                    if (res.predictedClass.classIndex == target) correctVal++
                }
                val avgValLoss = totalValLoss / valData.size
                val valAcc = correctVal.toFloat() / valData.size

                val epochDuration = System.currentTimeMillis() - epochStart
                val epochLog = EpochLog(
                    epoch = epoch,
                    trainLoss = avgTrainLoss,
                    valLoss = avgValLoss,
                    trainAccuracy = trainAcc,
                    valAccuracy = valAcc,
                    epochDurationMs = epochDuration
                )
                epochLogs.add(epochLog)

                val logLine = "Epoch $epoch/$totalEpochs [${epochDuration}ms] - TrainLoss: ${"%.4f".format(avgTrainLoss)}, TrainAcc: ${"%.2f%%".format(trainAcc * 100)}, ValLoss: ${"%.4f".format(avgValLoss)}, ValAcc: ${"%.2f%%".format(valAcc * 100)}"
                logStringBuilder.appendLine(logLine)

                onProgress(
                    TrainingProgress(
                        currentEpoch = epoch,
                        totalEpochs = totalEpochs,
                        trainLoss = avgTrainLoss,
                        valLoss = avgValLoss,
                        trainAccuracy = trainAcc,
                        valAccuracy = valAcc,
                        isCompleted = epoch == totalEpochs
                    )
                )
            }

            // 9. Build and Export Experimental Model Artifact
            val modelTimestamp = System.currentTimeMillis()
            val modelId = "EXP_MODEL_${modelTimestamp}"
            val artifactFileName = "experimental_model_${modelTimestamp}.json"
            val artifactFile = File(workspace.modelsDir, artifactFileName)

            val finalValAcc = epochLogs.lastOrNull()?.valAccuracy ?: 0f

            val metadata = ExperimentalModelMetadata(
                modelId = modelId,
                version = 1,
                creationTimestamp = modelTimestamp,
                trainSampleCount = rawTrainData.size,
                valSampleCount = valData.size,
                epochs = totalEpochs,
                finalAccuracy = finalValAcc,
                artifactFileName = artifactFileName,
                artifactPath = artifactFile.absolutePath,
                isExperimentalOnly = true,
                isolationNotice = "Experimental model only. The production 2D inspection model remains unchanged."
            )

            val trainedModel = ExperimentalModel(
                metadata = metadata,
                inputDim = inputDim,
                hiddenDim = hiddenDim,
                outputDim = outputDim,
                w1 = w1,
                b1 = b1,
                w2 = w2,
                b2 = b2,
                featureMeans = featureMeans,
                featureStds = featureStds,
                classPrototypes = classPrototypes
            )

            // Save exclusively to experimental workspace
            trainedModel.saveToFile(artifactFile)

            logStringBuilder.appendLine("Exported Artifact: ${artifactFile.absolutePath}")
            logStringBuilder.appendLine("Final Validation Accuracy: ${"%.2f%%".format(finalValAcc * 100)}")

            // 10. Audit production model hash AFTER training
            val prodHashAfter = workspace.computeProductionModelSha256()
            logStringBuilder.appendLine("Production Model Hash After: $prodHashAfter")
            val isIntact = prodHashBefore.equals(prodHashAfter, ignoreCase = true)
            logStringBuilder.appendLine("Hash Identical & Immutable: $isIntact")

            logFile.writeText(logStringBuilder.toString())

            check(isIntact) { "SECURITY INTEGRITY VIOLATION: Production model hash changed during training!" }

            trainedModel
        }
    }

    private fun augmentSample(base: SampleData, random: Random): List<SampleData> {
        val list = mutableListOf<SampleData>()
        val orig = base.features
        val dim = orig.size
        val target = base.targetIndex

        // 1. Original
        list.add(base)

        // 2. Horizontal Flip
        val hFlip = orig.clone()
        hFlip[1] = -hFlip[1] // Invert Centroid X
        swap(hFlip, 6 + 0, 6 + 4)
        swap(hFlip, 6 + 1, 6 + 3)
        swap(hFlip, 6 + 7, 6 + 5)
        for (r in 0..3) {
            swap(hFlip, 32 + 4 * r + 0, 32 + 4 * r + 3)
            swap(hFlip, 32 + 4 * r + 1, 32 + 4 * r + 2)
        }
        list.add(SampleData(base.sample, hFlip, target))

        // 3. Vertical Flip
        val vFlip = orig.clone()
        vFlip[2] = -vFlip[2] // Invert Centroid Y
        swap(vFlip, 6 + 2, 6 + 6)
        swap(vFlip, 6 + 1, 6 + 7)
        swap(vFlip, 6 + 3, 6 + 5)
        for (c in 0..3) {
            swap(vFlip, 32 + 4 * 0 + c, 32 + 4 * 3 + c)
            swap(vFlip, 32 + 4 * 1 + c, 32 + 4 * 2 + c)
        }
        list.add(SampleData(base.sample, vFlip, target))

        // 4. 180-Degree Rotation
        val dFlip = orig.clone()
        dFlip[1] = -dFlip[1]
        dFlip[2] = -dFlip[2]
        for (k in 0..3) swap(dFlip, 6 + k, 6 + (k + 4))
        for (r in 0..3) {
            for (c in 0..1) {
                swap(dFlip, 32 + 4 * r + c, 32 + 4 * (3 - r) + (3 - c))
            }
        }
        list.add(SampleData(base.sample, dFlip, target))

        // 5. Scale/Zoom (+4% fill ratio)
        val zoomIn = orig.clone()
        zoomIn[0] = (zoomIn[0] * 1.04f).coerceIn(0f, 1f)
        for (k in 0 until 8) zoomIn[6 + k] = (zoomIn[6 + k] * 1.02f).coerceIn(0f, 2.5f)
        list.add(SampleData(base.sample, zoomIn, target))

        // 6. Scale/Zoom (-4% fill ratio)
        val zoomOut = orig.clone()
        zoomOut[0] = (zoomOut[0] * 0.96f).coerceIn(0f, 1f)
        for (k in 0 until 8) zoomOut[6 + k] = (zoomOut[6 + k] * 0.98f).coerceIn(0f, 2.5f)
        list.add(SampleData(base.sample, zoomOut, target))

        // 7. Crack/Gradient Jitter (+10%)
        val texturePlus = orig.clone()
        texturePlus[16] = (texturePlus[16] * 1.10f).coerceIn(0f, 2.5f)
        texturePlus[18] = (texturePlus[18] * 1.10f).coerceIn(0f, 1f)
        texturePlus[19] = (texturePlus[19] * 1.10f).coerceIn(0f, 2.5f)
        list.add(SampleData(base.sample, texturePlus, target))

        // 8. Crack/Gradient Jitter (-10%)
        val textureMinus = orig.clone()
        textureMinus[16] = (textureMinus[16] * 0.90f).coerceIn(0f, 2.5f)
        textureMinus[18] = (textureMinus[18] * 0.90f).coerceIn(0f, 1f)
        textureMinus[19] = (textureMinus[19] * 0.90f).coerceIn(0f, 2.5f)
        list.add(SampleData(base.sample, textureMinus, target))

        // 9. Subtle Feature Noise Jitter
        val noisy = orig.clone()
        for (i in 0 until dim) {
            val noise = (random.nextGaussian() * 0.015).toFloat()
            noisy[i] = (noisy[i] + noise).coerceIn(-1f, 3f)
        }
        list.add(SampleData(base.sample, noisy, target))

        return list
    }

    private fun swap(arr: FloatArray, i: Int, j: Int) {
        if (i in arr.indices && j in arr.indices) {
            val tmp = arr[i]
            arr[i] = arr[j]
            arr[j] = tmp
        }
    }

    private fun loadFeatureData(samples: List<ExperimentalSample>): List<SampleData> {
        val list = mutableListOf<SampleData>()
        for (s in samples) {
            val file = File(s.imageFilePath)
            if (!file.exists()) continue
            val feat = ExperimentalModel.extractFeaturesFromFile(file, s.boundingBox)
            list.add(SampleData(sample = s, features = feat, targetIndex = s.assignedClass.classIndex))
        }
        return list
    }
}
