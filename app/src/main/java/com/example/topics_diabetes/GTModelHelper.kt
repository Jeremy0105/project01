package com.example.topics_diabetes

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GTModelHelper(private val context: Context) {

    companion object {
        private const val TAG = "GTModelHelper"
        private const val MODEL_NAME = "GT_model_170.tflite"
        private const val INPUT_SIZE = 512
        private const val INPUT_CHANNELS = 3
        private const val OUTPUT_CHANNELS = 1
        private const val SEGMENTATION_THRESHOLD = 0.5f
    }

    private var interpreter: Interpreter? = null

    suspend fun initializeModel(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val modelBuffer = loadModelFile()
                val options = Interpreter.Options().apply {
                    numThreads = 4
                }
                interpreter = Interpreter(modelBuffer, options)
                Log.d(TAG, "GT 模型載入成功")

                interpreter?.getInputTensor(0)?.shape()?.let { shape ->
                    Log.d(TAG, "模型輸入形狀: ${shape.contentToString()}")
                }
                interpreter?.getOutputTensor(0)?.shape()?.let { shape ->
                    Log.d(TAG, "模型輸出形狀: ${shape.contentToString()}")
                }

                true
            } catch (e: Exception) {
                Log.e(TAG, "模型初始化失敗", e)
                false
            }
        }
    }

    private fun loadModelFile(): MappedByteBuffer {
        val fileDescriptor: AssetFileDescriptor = context.assets.openFd(MODEL_NAME)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    suspend fun runInference(inputBitmap: Bitmap): GTResult? {
        return withContext(Dispatchers.IO) {
            val currentInterpreter = interpreter
            if (currentInterpreter == null) {
                Log.e(TAG, "模型未初始化")
                return@withContext null
            }

            try {
                val startTime = System.currentTimeMillis()

                // 使用與原始ModelHelper相同的輸入處理方式
                val inputBuffer = convertBitmapToByteBuffer(inputBitmap)

                // 準備輸出緩衝區
                val outputTensor = currentInterpreter.getOutputTensor(0)
                val outputShape = outputTensor.shape()
                Log.d(TAG, "輸出張量形狀: ${outputShape.contentToString()}")

                val outputBuffer = ByteBuffer.allocateDirect(
                    outputShape.fold(1) { acc, dim -> acc * dim } * 4
                )
                outputBuffer.order(ByteOrder.nativeOrder())

                // 執行推論
                currentInterpreter.run(inputBuffer, outputBuffer)
                val processingTime = System.currentTimeMillis() - startTime

                // 將輸出緩衝區轉換為FloatArray
                outputBuffer.rewind()
                val outputData = FloatArray(outputBuffer.capacity() / 4)
                outputBuffer.asFloatBuffer().get(outputData)

                Log.d(TAG, "推論完成，耗時: ${processingTime}ms")
                Log.d(TAG, "輸出數據大小: ${outputData.size}")

                // 生成結果圖像
                val (segmentationBitmap, grayscaleBitmap) = createBothResults(inputBitmap, outputData, outputShape)
                val statistics = calculateStatisticsFromArray(outputData, outputShape)

                GTResult(
                    outputBitmap = segmentationBitmap,
                    grayscaleBitmap = grayscaleBitmap, // 新增灰階圖
                    rawOutput = convertToArray(outputData, outputShape),
                    processingTime = processingTime,
                    statistics = statistics,
                    inputSize = INPUT_SIZE,
                    outputSize = INPUT_SIZE
                )

            } catch (e: Exception) {
                Log.e(TAG, "推論失敗", e)
                null
            }
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val resizedBitmap = if (bitmap.width != INPUT_SIZE || bitmap.height != INPUT_SIZE) {
            Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        } else {
            bitmap
        }

        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * INPUT_CHANNELS)
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        resizedBitmap.getPixels(intValues, 0, resizedBitmap.width, 0, 0, resizedBitmap.width, resizedBitmap.height)

        Log.d(TAG, "輸入處理:")
        Log.d(TAG, "- 像素數量: ${intValues.size}")
        Log.d(TAG, "- 通道數: $INPUT_CHANNELS")

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

        Log.d(TAG, "- 緩衝區使用量: ${byteBuffer.position()} / ${byteBuffer.capacity()}")
        return byteBuffer
    }

    private fun createBothResults(inputBitmap: Bitmap, outputData: FloatArray, outputShape: IntArray): Pair<Bitmap, Bitmap> {
        val outputWidth = if (outputShape.size >= 4) outputShape[2] else INPUT_SIZE
        val outputHeight = if (outputShape.size >= 4) outputShape[1] else INPUT_SIZE

        val minValue = outputData.minOrNull() ?: 0f
        val maxValue = outputData.maxOrNull() ?: 1f

        Log.d(TAG, "輸出處理:")
        Log.d(TAG, "- 輸出尺寸: ${outputWidth}x${outputHeight}")
        Log.d(TAG, "- 數據範圍: $minValue ~ $maxValue")

        // 1. 創建灰階分割圖（黑白輸出，對應第二張圖）
        val grayscaleBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val grayscalePixels = IntArray(outputWidth * outputHeight)

        for (i in grayscalePixels.indices) {
            val rawValue = outputData[i]

            // 正規化到 0-1 範圍
            val normalizedValue = if (maxValue > minValue) {
                (rawValue - minValue) / (maxValue - minValue)
            } else {
                0f
            }

            // 轉換為 0-255 強度值
            val intensity = (normalizedValue * 255).toInt().coerceIn(0, 255)

            // 直接顯示模型輸出：白色 = 模型識別的亮點區域
            grayscalePixels[i] = (0xFF shl 24) or (intensity shl 16) or (intensity shl 8) or intensity
        }

        grayscaleBitmap.setPixels(grayscalePixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        // 2. 創建彩色疊加圖（原圖+藍色遮罩，對應第一張圖的效果）
        val scaledOriginal = Bitmap.createScaledBitmap(inputBitmap, outputWidth, outputHeight, true)
        val overlayBitmap = scaledOriginal.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(overlayBitmap)

        // 創建藍色遮罩
        val maskBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val maskPixels = IntArray(outputWidth * outputHeight)

        var segmentedPixelCount = 0

        for (i in maskPixels.indices) {
            val rawValue = outputData[i]
            val normalizedValue = if (maxValue > minValue) {
                (rawValue - minValue) / (maxValue - minValue)
            } else {
                0f
            }

            // 使用閾值進行二值化
            if (normalizedValue > SEGMENTATION_THRESHOLD) {
                segmentedPixelCount++

                // 使用黑色遮罩（完全不透明）
                val blackColor = Color.argb(255, 0, 0, 0)  // 純黑色
                maskPixels[i] = blackColor
            } else {
                maskPixels[i] = Color.TRANSPARENT
            }
        }

        maskBitmap.setPixels(maskPixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        // 疊加遮罩到原圖
        val paint = Paint().apply {
            isAntiAlias = true
        }
        canvas.drawBitmap(maskBitmap, 0f, 0f, paint)

        Log.d(TAG, "分割像素數: $segmentedPixelCount")
        Log.d(TAG, "分割比例: ${"%.2f".format(segmentedPixelCount.toFloat() / (outputWidth * outputHeight) * 100)}%")

        // 將結果縮放回原始尺寸
        val finalOverlay = if (inputBitmap.width != outputWidth || inputBitmap.height != outputHeight) {
            Bitmap.createScaledBitmap(overlayBitmap, inputBitmap.width, inputBitmap.height, true)
        } else {
            overlayBitmap
        }

        val finalGrayscale = if (inputBitmap.width != outputWidth || inputBitmap.height != outputHeight) {
            Bitmap.createScaledBitmap(grayscaleBitmap, inputBitmap.width, inputBitmap.height, true)
        } else {
            grayscaleBitmap
        }

        return Pair(finalOverlay, finalGrayscale)
    }

    private fun convertToArray(outputData: FloatArray, outputShape: IntArray): Array<Array<FloatArray>> {
        val height = if (outputShape.size >= 4) outputShape[1] else INPUT_SIZE
        val width = if (outputShape.size >= 4) outputShape[2] else INPUT_SIZE
        val channels = if (outputShape.size >= 4) outputShape[3] else 1

        val result = Array(height) { Array(width) { FloatArray(channels) } }

        var index = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                for (c in 0 until channels) {
                    if (index < outputData.size) {
                        result[y][x][c] = outputData[index]
                        index++
                    }
                }
            }
        }

        return result
    }

    private fun calculateStatisticsFromArray(outputData: FloatArray, outputShape: IntArray): GTStatistics {
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

        // 計算正規化後的positive pixels
        var positivePixels = 0
        if (max > min) {
            for (value in outputData) {
                val normalizedValue = (value - min) / (max - min)
                if (normalizedValue > SEGMENTATION_THRESHOLD) {
                    positivePixels++
                }
            }
        }

        val positiveRatio = positivePixels.toFloat() / totalPixels

        return GTStatistics(
            averageValue = average,
            minValue = min,
            maxValue = max,
            positivePixels = positivePixels,
            totalPixels = totalPixels,
            positiveRatio = positiveRatio
        )
    }

    fun isModelReady(): Boolean {
        return interpreter != null
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        Log.d(TAG, "GT 模型資源已釋放")
    }
}

data class GTResult(
    val outputBitmap: Bitmap,
    val grayscaleBitmap: Bitmap, // 新增灰階圖
    val rawOutput: Array<Array<FloatArray>>,
    val processingTime: Long,
    val statistics: GTStatistics,
    val inputSize: Int,
    val outputSize: Int
)

data class GTStatistics(
    val averageValue: Float,
    val minValue: Float,
    val maxValue: Float,
    val positivePixels: Int,
    val totalPixels: Int,
    val positiveRatio: Float
)