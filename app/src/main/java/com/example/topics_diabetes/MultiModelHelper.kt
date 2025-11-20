package com.example.topics_diabetes

import android.content.Context
import android.graphics.*
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MultiModelHelper(private val context: Context) {

    companion object {
        private const val TAG = "MultiModelHelper"
        private const val OUTPUT_CHANNELS = 1
    }

    private var modelManager: ModelManager? = null
// 功能：
//  - 調用 ModelManager.initializeModels()
//  - 等待模型載入完成
//  - 返回成功/失敗狀態
//
//  內部調用： ModelManager.initializeModels()
    suspend fun initializeModels(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                modelManager = ModelManager(context)
                val success = modelManager?.initializeModels() ?: false

                if (success) {
                    Log.d(TAG, "多模型系統初始化成功")
                } else {
                    Log.e(TAG, "多模型系統初始化失敗")
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "初始化異常", e)
                e.printStackTrace()
                false
            }
        }
    }
// 功能：
//  - 檢查模型是否就緒
//  - 獲取所有啟用的模型配置（7個）
//  - FOR 每個模型（index = 0 to 6）：
//    - 回調進度：onProgress(index+1, 7, modelName)
//    - 獲取對應的 Interpreter
//    - 調用 convertBitmapToByteBuffer() 預處理圖片
//    - 創建輸出 ByteBuffer
//    - 執行推論： interpreter.run(inputBuffer, outputBuffer)
//    - 將輸出轉換為 FloatArray
//    - 調用 calculateStatistics() 計算統計數據
//    - 保存到 individualResults Map
//  - 調用 createCombinedOverlay() 創建合併疊加圖
//  - 調用 createCombinedGrayscale() 創建合併灰階圖
//  - 計算整體統計數據和健康評分
//  - 返回 MultiModelResult
//
//  輸入： Bitmap, 進度回調
//  輸出： MultiModelResult
    // 添加支援進度回調的推論方法
    suspend fun runMultiModelInferenceWithProgress(
        inputBitmap: Bitmap,
        onProgress: (current: Int, total: Int, modelName: String) -> Unit
    ): MultiModelResult? {
        return withContext(Dispatchers.IO) {
            val manager = modelManager
            if (manager == null || !manager.isAllModelsReady()) {
                Log.e(TAG, "模型未就緒")
                return@withContext null
            }

            try {
                Log.d(TAG, "=== 開始多模型推論 ===")
                Log.d(TAG, "輸入圖像尺寸: ${inputBitmap.width}x${inputBitmap.height}")

                val startTime = System.currentTimeMillis()
                val models = manager.getAvailableModels()
                Log.d(TAG, "載入了 ${models.size} 個模型")

                val individualResults = mutableMapOf<String, TissueResult>()
                var woundMask: FloatArray? = null
                var woundMaskShape: IntArray? = null

                // 步驟 1: 先執行 GT 模型（傷口範圍檢測）
                Log.d(TAG, "=== 步驟 1: 執行 GT 傷口範圍檢測 ===")
                val gtModel = models.find { it.id == "gt_tissue" }
                if (gtModel != null) {
                    onProgress(1, models.size, gtModel.name)

                    val interpreter = manager.getInterpreter(gtModel.id)
                    if (interpreter != null) {
                        val modelStartTime = System.currentTimeMillis()
                        // GT 模型使用無預處理版本（RGB, 0-255）
                        val inputBuffer = convertBitmapToByteBufferGT(inputBitmap, gtModel.inputSize)

                        val outputShape = interpreter.getOutputTensor(0).shape()
                        val outputBuffer = ByteBuffer.allocateDirect(
                            outputShape.fold(1) { acc, dim -> acc * dim } * 4
                        )
                        outputBuffer.order(ByteOrder.nativeOrder())

                        interpreter.run(inputBuffer, outputBuffer)
                        val inferenceTime = System.currentTimeMillis() - modelStartTime

                        outputBuffer.rewind()
                        val outputData = FloatArray(outputBuffer.capacity() / 4)
                        outputBuffer.asFloatBuffer().get(outputData)

                        // 保存 GT 遮罩供後續使用
                        woundMask = outputData
                        woundMaskShape = outputShape

                        val statistics = calculateStatistics(outputData, outputShape, gtModel.threshold)

                        individualResults[gtModel.id] = TissueResult(
                            modelId = gtModel.id,
                            modelName = gtModel.name,
                            color = gtModel.color,
                            pixelCount = statistics.positivePixels,
                            percentage = statistics.positiveRatio * 100,
                            averageValue = statistics.averageValue,
                            outputData = outputData,
                            outputShape = outputShape
                        )

                        Log.d(TAG, "GT傷口範圍檢測完成: ${statistics.positivePixels} 像素 (${String.format("%.2f", statistics.positiveRatio * 100)}%), 耗時 ${inferenceTime}ms")
                    }
                }

                // 步驟 2: 執行其他 6 個組織分類模型（只在傷口範圍內）
                Log.d(TAG, "=== 步驟 2: 執行組織分類模型（限制在傷口範圍內） ===")
                val otherModels = models.filter { it.id != "gt_tissue" }
                for ((index, modelConfig) in otherModels.withIndex()) {
                    val current = index + 2  // 從 2 開始（1 是 GT）
                    val total = models.size

                    // 回調進度
                    onProgress(current, total, modelConfig.name)

                    Log.d(TAG, "--- 模型 $current/$total: ${modelConfig.name} ---")

                    val interpreter = manager.getInterpreter(modelConfig.id) ?: continue

                    val modelStartTime = System.currentTimeMillis()
                    // 其他 6 個模型使用 ResNet50 預處理（BGR + 減均值）
                    val inputBuffer = convertBitmapToByteBufferResNet50(inputBitmap, modelConfig.inputSize)
                    Log.d(TAG, "${modelConfig.name}: 輸入準備完成（ResNet50 預處理）")

                    val outputShape = interpreter.getOutputTensor(0).shape()
                    val outputBuffer = ByteBuffer.allocateDirect(
                        outputShape.fold(1) { acc, dim -> acc * dim } * 4
                    )
                    outputBuffer.order(ByteOrder.nativeOrder())

                    Log.d(TAG, "${modelConfig.name}: 開始推論...")
                    interpreter.run(inputBuffer, outputBuffer)
                    val inferenceTime = System.currentTimeMillis() - modelStartTime
                    Log.d(TAG, "${modelConfig.name}: 推論完成,耗時 ${inferenceTime}ms")

                    outputBuffer.rewind()
                    val outputData = FloatArray(outputBuffer.capacity() / 4)
                    outputBuffer.asFloatBuffer().get(outputData)

                    // ⭐ 關鍵修改：使用 GT 遮罩過濾輸出
                    val filteredData = if (woundMask != null) {
                        applyWoundMask(outputData, woundMask, modelConfig.threshold)
                    } else {
                        outputData  // 如果沒有 GT 遮罩，使用原始輸出
                    }

                    val statistics = calculateStatistics(
                        filteredData,
                        outputShape,
                        modelConfig.threshold
                    )

                    individualResults[modelConfig.id] = TissueResult(
                        modelId = modelConfig.id,
                        modelName = modelConfig.name,
                        color = modelConfig.color,
                        pixelCount = statistics.positivePixels,
                        percentage = statistics.positiveRatio * 100,
                        averageValue = statistics.averageValue,
                        outputData = filteredData,
                        outputShape = outputShape
                    )

                    Log.d(TAG, "${modelConfig.name}: ${statistics.positivePixels} 像素 (${String.format("%.2f", statistics.positiveRatio * 100)}%)")
                }

                Log.d(TAG, "=== 所有模型推論完成 ===")
                Log.d(TAG, "開始創建合併圖像...")

                val combinedStartTime = System.currentTimeMillis()
                val combinedBitmap = createCombinedOverlay(inputBitmap, individualResults, models)
                val combinedTime = System.currentTimeMillis() - combinedStartTime
                Log.d(TAG, "合併圖像創建完成,耗時 ${combinedTime}ms")

                Log.d(TAG, "開始創建灰階圖像...")
                val grayscaleStartTime = System.currentTimeMillis()
                val grayscaleBitmap = createCombinedGrayscale(inputBitmap, individualResults, models)
                val grayscaleTime = System.currentTimeMillis() - grayscaleStartTime
                Log.d(TAG, "灰階圖像創建完成,耗時 ${grayscaleTime}ms")

                val processingTime = System.currentTimeMillis() - startTime

                val totalPixels = inputBitmap.width * inputBitmap.height
                val overallStats = OverallStatistics(
                    totalPixels = totalPixels,
                    tissueBreakdown = individualResults.mapValues { it.value.pixelCount },
                    healthScore = calculateHealthScore(individualResults)
                )

                Log.d(TAG, "=== 多模型推論完成 ===")
                Log.d(TAG, "總耗時: ${processingTime}ms")
                Log.d(TAG, "健康評分: ${String.format("%.1f", overallStats.healthScore)}/100")

                MultiModelResult(
                    combinedBitmap = combinedBitmap,
                    grayscaleBitmap = grayscaleBitmap,
                    individualResults = individualResults,
                    processingTime = processingTime,
                    overallStatistics = overallStats
                )

            } catch (e: Exception) {
                Log.e(TAG, "多模型推論失敗", e)
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun runMultiModelInference(inputBitmap: Bitmap): MultiModelResult? {
        // 直接調用有進度回調的版本，使用空回調
        return runMultiModelInferenceWithProgress(inputBitmap) { _, _, _ -> }
    }
    // 功能：
    //  - 步驟 1：縮放圖片
    //    - 如果尺寸不是 512x512，使用 Bitmap.createScaledBitmap() 縮放
    //    - 使用雙線性插值 (filter = true)
    //  - 步驟 2：創建 ByteBuffer
    //    - 大小：4 bytes × 512 × 512 × 3 channels = 3,145,728 bytes
    //    - 順序：Native Byte Order（通常是小端序）
    //  - 步驟 3：提取像素值
    //    - getPixels() 獲取所有像素的 ARGB 值
    //    - 返回 IntArray (size = 262,144)
    //  - 步驟 4：RGB 分離與轉換
    //    - FOR 每個像素：
    //        - 提取 R：(pixelValue shr 16) and 0xFF
    //      - 提取 G：(pixelValue shr 8) and 0xFF
    //      - 提取 B：pixelValue and 0xFF
    //      - 轉換為 Float：r.toFloat(), g.toFloat(), b.toFloat()
    //      - 範圍：0-255（未歸一化）
    //      - 按 RGB 順序放入 ByteBuffer
    //
    //  輸入： Bitmap, inputSize (512)
    //  輸出： ByteBuffer (3,145,728 bytes)
    //
    //  數據格式：
    //  [R₁, G₁, B₁, R₂, G₂, B₂, ..., R₂₆₂₁₄₄, G₂₆₂₁₄₄, B₂₆₂₁₄₄]
    //  每個值為 Float (4 bytes)，範圍 0-255
    private fun convertBitmapToByteBuffer(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val resizedBitmap = if (bitmap.width != inputSize || bitmap.height != inputSize) {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        } else {
            bitmap
        }

        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(
            intValues,
            0,
            resizedBitmap.width,
            0,
            0,
            resizedBitmap.width,
            resizedBitmap.height
        )

        for (pixelValue in intValues) {
            val r = (pixelValue shr 16) and 0xFF
            val g = (pixelValue shr 8) and 0xFF
            val b = pixelValue and 0xFF

            // 原始預處理: RGB 順序, 0-255 範圍（用於 GT 模型）
            byteBuffer.putFloat(r.toFloat())
            byteBuffer.putFloat(g.toFloat())
            byteBuffer.putFloat(b.toFloat())
        }

        return byteBuffer
    }

    /**
     * GT 模型專用：無預處理（RGB, 0-255）
     */
    private fun convertBitmapToByteBufferGT(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val resizedBitmap = if (bitmap.width != inputSize || bitmap.height != inputSize) {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        } else {
            bitmap
        }

        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(
            intValues,
            0,
            resizedBitmap.width,
            0,
            0,
            resizedBitmap.width,
            resizedBitmap.height
        )

        for (pixelValue in intValues) {
            val r = (pixelValue shr 16) and 0xFF
            val g = (pixelValue shr 8) and 0xFF
            val b = pixelValue and 0xFF

            // GT 模型：無預處理，RGB 順序, 0-255 範圍
            byteBuffer.putFloat(r.toFloat())
            byteBuffer.putFloat(g.toFloat())
            byteBuffer.putFloat(b.toFloat())
        }

        return byteBuffer
    }

    /**
     * 其他 6 個模型專用：ResNet50 預處理（BGR + 減均值）
     */
    private fun convertBitmapToByteBufferResNet50(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val resizedBitmap = if (bitmap.width != inputSize || bitmap.height != inputSize) {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        } else {
            bitmap
        }

        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        resizedBitmap.getPixels(
            intValues,
            0,
            resizedBitmap.width,
            0,
            0,
            resizedBitmap.width,
            resizedBitmap.height
        )

        // ResNet50 預處理參數 (ImageNet 均值)
        val MEAN_R = 123.68f
        val MEAN_G = 116.779f
        val MEAN_B = 103.939f

        for (pixelValue in intValues) {
            val r = (pixelValue shr 16) and 0xFF
            val g = (pixelValue shr 8) and 0xFF
            val b = pixelValue and 0xFF

            // ResNet50 風格預處理: BGR 順序 + 減去 ImageNet 均值
            byteBuffer.putFloat(b.toFloat() - MEAN_B)  // B 通道
            byteBuffer.putFloat(g.toFloat() - MEAN_G)  // G 通道
            byteBuffer.putFloat(r.toFloat() - MEAN_R)  // R 通道
        }

        return byteBuffer
    }
    // 功能：
    //  - 計算基本統計值：
    //    - 總和 (sum)
    //    - 最小值 (min)
    //    - 最大值 (max)
    //    - 平均值 (average = sum / totalPixels)
    //  - 正規化與閾值處理：
    //    - FOR 每個輸出值：
    //        - 正規化：normalizedValue = (value - min) / (max - min)
    //      - 如果 normalizedValue > threshold (0.5) → 計為正像素
    //    - 計算正像素比例
    //  - 返回統計數據：
    //    - averageValue (平均值)
    //    - minValue, maxValue (範圍)
    //    - positivePixels (正像素數)
    //    - totalPixels (總像素數)
    //    - positiveRatio (正像素比例)
    //
    //  輸入： FloatArray (模型輸出), 形狀, 閾值
    //  輸出： Statistics
    /**
     * 使用傷口遮罩過濾模型輸出
     * 只保留傷口範圍內的像素，背景區域設為 0
     */
    private fun applyWoundMask(
        modelOutput: FloatArray,
        woundMask: FloatArray,
        threshold: Float
    ): FloatArray {
        if (modelOutput.size != woundMask.size) {
            Log.w(TAG, "遮罩尺寸不匹配: ${modelOutput.size} vs ${woundMask.size}")
            return modelOutput
        }

        // 正規化 GT 遮罩
        val minValue = woundMask.minOrNull() ?: 0f
        val maxValue = woundMask.maxOrNull() ?: 1f
        val valueRange = maxValue - minValue

        val filteredOutput = FloatArray(modelOutput.size)
        var maskedPixels = 0

        for (i in modelOutput.indices) {
            // 正規化 GT 值
            val normalizedGT = if (valueRange > 0) {
                (woundMask[i] - minValue) / valueRange
            } else {
                0f
            }

            // 如果該像素在傷口範圍內（GT > threshold），保留模型輸出；否則設為 0
            filteredOutput[i] = if (normalizedGT > threshold) {
                modelOutput[i]
            } else {
                maskedPixels++
                0f  // 背景區域
            }
        }

        Log.d(TAG, "遮罩過濾: ${maskedPixels}/${modelOutput.size} 像素被標記為背景")
        return filteredOutput
    }

    private fun calculateStatistics(outputData: FloatArray, outputShape: IntArray, threshold: Float): Statistics {
        val totalPixels = outputData.size
        var sum = 0f
        var min = Float.MAX_VALUE
        var max = Float.MIN_VALUE

        for (value in outputData) {
            sum += value
            min = minOf(min, value)
            max = maxOf(max, value)
        }

        val average = sum / totalPixels

        var positivePixels = 0
        if (max > min) {
            for (value in outputData) {
                val normalizedValue = (value - min) / (max - min)
                if (normalizedValue > threshold) {
                    positivePixels++
                }
            }
        }

        return Statistics(
            averageValue = average,
            minValue = min,
            maxValue = max,
            positivePixels = positivePixels,
            totalPixels = totalPixels,
            positiveRatio = positivePixels.toFloat() / totalPixels
        )
    }
// 功能：
//  - 複製原始圖片為可編輯 Bitmap
//  - 創建 Canvas 用於繪圖
//  - FOR 每個模型：
//    - 調用 createMaskBitmap() 創建遮罩
//    - 使用 Paint (alpha=180) 繪製半透明遮罩到 Canvas
//    - 回收遮罩 Bitmap
//  - 返回合併後的圖片
//
//  輸入： 原圖, 結果 Map, 模型配置
//  輸出： Bitmap (原圖 + 7色遮罩)
    private fun createCombinedOverlay(
        inputBitmap: Bitmap,
        results: Map<String, TissueResult>,
        models: List<ModelManager.ModelConfig>
    ): Bitmap {
        Log.d(TAG, "創建合併圖像: ${inputBitmap.width}x${inputBitmap.height}")

        try {
            val outputBitmap = inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(outputBitmap)

            Log.d(TAG, "開始疊加 ${models.size} 個模型的遮罩")

            for ((index, modelConfig) in models.withIndex()) {
                Log.d(TAG, "處理遮罩 ${index + 1}/${models.size}: ${modelConfig.name}")

                val result = results[modelConfig.id]
                if (result == null) {
                    Log.w(TAG, "跳過 ${modelConfig.name}: 結果為空")
                    continue
                }

                try {
                    val maskBitmap = createMaskBitmap(
                        inputBitmap.width,
                        inputBitmap.height,
                        result.outputData,
                        result.outputShape,
                        modelConfig.color,
                        modelConfig.threshold
                    )

                    val paint = Paint().apply {
                        alpha = 180
                    }
                    canvas.drawBitmap(maskBitmap, 0f, 0f, paint)
                    maskBitmap.recycle()

                    Log.d(TAG, "${modelConfig.name} 遮罩疊加完成")

                } catch (e: Exception) {
                    Log.e(TAG, "${modelConfig.name} 遮罩創建失敗", e)
                }
            }

            Log.d(TAG, "合併圖像創建成功")
            return outputBitmap

        } catch (e: Exception) {
            Log.e(TAG, "創建合併圖像失敗", e)
            e.printStackTrace()
            return inputBitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
    }
//功能：
//  - 創建空白 Bitmap（白色背景）
//  - 定義灰度等級：[64, 128, 192, 224]
//  - FOR 每個模型（index = 0-6）：
//    - 選擇對應灰度值
//    - FOR 每個輸出像素：
//        - 正規化輸出值
//      - 如果 > threshold：設為對應灰度
//      - 縮放座標到原圖尺寸
//      - 設置像素顏色
//  - 返回灰階圖
//
//  輸入： 原圖, 結果 Map, 模型配置
//  輸出： Bitmap (多灰度分割圖)
//
//  灰度映射示例：
//  模型 0 (GT組織)     → 灰度 64 (深灰)
//  模型 1 (骨組織)     → 灰度 128 (中灰)
//  模型 2 (上皮化)     → 灰度 192 (淺灰)
//  模型 3 (肉芽)       → 灰度 224 (很淺灰)
//  模型 4-6           → 灰度 128 (預設)
//  背景               → 灰度 255 (白色)
    private fun createCombinedGrayscale(
        inputBitmap: Bitmap,
        results: Map<String, TissueResult>,
        models: List<ModelManager.ModelConfig>
    ): Bitmap {
        Log.d(TAG, "創建灰階圖像: ${inputBitmap.width}x${inputBitmap.height}")

        try {
            val width = inputBitmap.width
            val height = inputBitmap.height
            val grayscaleBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)

            // 初始化為白色背景
            pixels.fill(Color.WHITE)

            val grayLevels = listOf(64, 128, 192, 224)

            for ((index, modelConfig) in models.withIndex()) {
                val result = results[modelConfig.id] ?: continue
                val grayValue = grayLevels.getOrElse(index) { 128 }
                val grayColor = Color.rgb(grayValue, grayValue, grayValue)

                val outputData = result.outputData
                val outputShape = result.outputShape
                val outputWidth = if (outputShape.size >= 4) outputShape[2] else width
                val outputHeight = if (outputShape.size >= 4) outputShape[1] else height

                // 預先計算 min/max 值
                val minValue = outputData.minOrNull() ?: 0f
                val maxValue = outputData.maxOrNull() ?: 1f
                val valueRange = maxValue - minValue

                var processedPixels = 0

                // 優化版本:減少重複計算
                if (valueRange > 0) {
                    for (i in outputData.indices) {
                        val normalizedValue = (outputData[i] - minValue) / valueRange

                        if (normalizedValue > modelConfig.threshold) {
                            val y = i / outputWidth
                            val x = i % outputWidth

                            // 使用位運算加速
                            val scaledX = (x * width) / outputWidth
                            val scaledY = (y * height) / outputHeight

                            if (scaledX < width && scaledY < height) {
                                val pixelIndex = scaledY * width + scaledX
                                if (pixelIndex < pixels.size) {
                                    pixels[pixelIndex] = grayColor
                                    processedPixels++
                                }
                            }
                        }
                    }
                }

                Log.d(TAG, "${modelConfig.name}: 處理了 $processedPixels 個像素")
            }

            grayscaleBitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            Log.d(TAG, "灰階圖像創建成功")
            return grayscaleBitmap

        } catch (e: Exception) {
            Log.e(TAG, "創建灰階圖像失敗", e)
            e.printStackTrace()
            return Bitmap.createBitmap(inputBitmap.width, inputBitmap.height, Bitmap.Config.ARGB_8888)
        }
    }
// 功能：
//  - 創建空白 Bitmap (512x512)
//  - 創建像素陣列
//  - FOR 每個像素：
//    - 正規化輸出值：(rawValue - min) / (max - min)
//    - 如果 normalizedValue > threshold (0.5)：
//        - 設為模型顏色（例如紅色、綠色等）
//    - 否則：
//        - 設為透明
//  - 設置像素到 Bitmap
//  - 如果需要，縮放到目標尺寸
//  - 返回遮罩 Bitmap
//
//  輸入： 尺寸, 輸出數據, 形狀, 顏色, 閾值
//  輸出： Bitmap (單色遮罩)
    private fun createMaskBitmap(
        targetWidth: Int,
        targetHeight: Int,
        outputData: FloatArray,
        outputShape: IntArray,
        color: Int,
        threshold: Float
    ): Bitmap {
        val outputWidth = if (outputShape.size >= 4) outputShape[2] else targetWidth
        val outputHeight = if (outputShape.size >= 4) outputShape[1] else targetHeight

        val maskBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(outputWidth * outputHeight)

        val minValue = outputData.minOrNull() ?: 0f
        val maxValue = outputData.maxOrNull() ?: 1f

        for (i in pixels.indices) {
            val rawValue = outputData[i]
            val normalizedValue = if (maxValue > minValue) {
                (rawValue - minValue) / (maxValue - minValue)
            } else {
                0f
            }

            pixels[i] = if (normalizedValue > threshold) {
                color
            } else {
                Color.TRANSPARENT
            }
        }

        maskBitmap.setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        return if (targetWidth != outputWidth || targetHeight != outputHeight) {
            val scaledBitmap = Bitmap.createScaledBitmap(maskBitmap, targetWidth, targetHeight, true)
            maskBitmap.recycle()
            scaledBitmap
        } else {
            maskBitmap
        }
    }

    private fun calculateHealthScore(results: Map<String, TissueResult>): Float {
        var score = 50f

        results.forEach { (modelId, result) ->
            when (modelId) {
                "epithelization" -> score += result.percentage * 1.0f
                "granulation" -> score += result.percentage * 0.8f
                "slough" -> score -= result.percentage * 1.5f
                "gt_tissue" -> score += result.percentage * 0.5f
            }
        }

        return score.coerceIn(0f, 100f)
    }

    fun isModelsReady(): Boolean {
        return modelManager?.isAllModelsReady() ?: false
    }

    fun close() {
        modelManager?.close()
        modelManager = null
        Log.d(TAG, "多模型系統資源已釋放")
    }

    data class MultiModelResult(
        val combinedBitmap: Bitmap,
        val grayscaleBitmap: Bitmap,
        val individualResults: Map<String, TissueResult>,
        val processingTime: Long,
        val overallStatistics: OverallStatistics
    )

    data class TissueResult(
        val modelId: String,
        val modelName: String,
        val color: Int,
        val pixelCount: Int,
        val percentage: Float,
        val averageValue: Float,
        val outputData: FloatArray,
        val outputShape: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as TissueResult

            if (modelId != other.modelId) return false
            if (modelName != other.modelName) return false
            if (color != other.color) return false
            if (pixelCount != other.pixelCount) return false
            if (percentage != other.percentage) return false
            if (averageValue != other.averageValue) return false
            if (!outputData.contentEquals(other.outputData)) return false
            if (!outputShape.contentEquals(other.outputShape)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = modelId.hashCode()
            result = 31 * result + modelName.hashCode()
            result = 31 * result + color
            result = 31 * result + pixelCount
            result = 31 * result + percentage.hashCode()
            result = 31 * result + averageValue.hashCode()
            result = 31 * result + outputData.contentHashCode()
            result = 31 * result + outputShape.contentHashCode()
            return result
        }
    }

    data class OverallStatistics(
        val totalPixels: Int,
        val tissueBreakdown: Map<String, Int>,
        val healthScore: Float
    )

    private data class Statistics(
        val averageValue: Float,
        val minValue: Float,
        val maxValue: Float,
        val positivePixels: Int,
        val totalPixels: Int,
        val positiveRatio: Float
    )
}