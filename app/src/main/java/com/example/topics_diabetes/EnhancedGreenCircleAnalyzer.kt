package com.example.topics_diabetes

import android.graphics.*
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.*

class EnhancedGreenCircleAnalyzer(
    private val listener: (DetectionResult) -> Unit
) : ImageAnalysis.Analyzer {

    private var lastProcessTimestamp = 0L
    private val processingInterval = 150L

    private var imageWidth = 0
    private var imageHeight = 0

    // 更精確的綠色範圍 - 針對深綠色圓形貼紙
    private val greenRanges = listOf(
        // 深綠色圓形貼紙 (主要目標)
        GreenRange(minR = 0, maxR = 120, minG = 100, maxG = 200, minB = 0, maxB = 120),
        // 標準綠色
        GreenRange(minR = 0, maxR = 140, minG = 120, maxG = 230, minB = 0, maxB = 140),
        // 青綠色 (但要求高飽和度)
        GreenRange(minR = 0, maxR = 150, minG = 140, maxG = 255, minB = 100, maxB = 200)
    )

    // 平衡的參數 - 既能檢測到又不會誤判
    private val minCircleRadius = 8     // 進一步降低到8像素 (貼紙可能很小)
    private val maxCircleRadius = 120   // 限制最大半徑,避免大區域
    private val minCircularity = 0.35f  // 放寬到35% 以支援側面橢圓形
    private val minPixelCount = 80      // 至少80像素
    private val maxPixelCount = 5000    // 降低最大像素數,更嚴格避免大區域

    private val recentDetections = mutableListOf<SimpleDetection>()
    private val maxHistorySize = 8

    override fun analyze(image: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()
        if (currentTimestamp - lastProcessTimestamp < processingInterval) {
            image.close()
            return
        }
        lastProcessTimestamp = currentTimestamp

        try {
            val bitmap = imageProxyToBitmap(image)
            val result = detectGreenCirclesEnhanced(bitmap)
            val stableResult = applyStabilityFilter(result)
            listener(stableResult)
        } catch (e: Exception) {
            Log.e("GreenCircleAnalyzer", "檢測錯誤: ${e.message}", e)
            listener(DetectionResult(false, emptyList(), 0f, "檢測錯誤: ${e.message}"))
        } finally {
            image.close()
        }
    }

    private fun detectGreenCirclesEnhanced(bitmap: Bitmap): DetectionResult {
        val width = bitmap.width
        val height = bitmap.height

        imageWidth = width
        imageHeight = height

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // 步驟1: 創建綠色遮罩 - 精確檢測
        val greenMask = createPreciseGreenMask(pixels, width, height)

        // 步驟2: 降噪處理
        val cleanedMask = improvedNoiseFilter(greenMask, width, height)

        // 步驟3: 找到連通區域
        val regions = findConnectedRegions(cleanedMask, width, height)

        Log.d("GreenCircleAnalyzer", "找到 ${regions.size} 個綠色區域")

        // 步驟4: 精確的圓形分析
        val circles = preciseCircularAnalysis(regions)

        Log.d("GreenCircleAnalyzer", "找到 ${circles.size} 個圓形候選")

        // 步驟5: 智能選擇最佳圓形
        val bestCircles = intelligentCircleSelection(circles)

        Log.d("GreenCircleAnalyzer", "最終選擇 ${bestCircles.size} 個圓形")

        return createDetectionResult(bestCircles)
    }

    private fun createPreciseGreenMask(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val mask = BooleanArray(width * height)
        var greenCount = 0

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            // RGB檢測 - 綠色明顯高於紅藍
            val rgbGreen = greenRanges.any { range ->
                r in range.minR..range.maxR &&
                        g in range.minG..range.maxG &&
                        b in range.minB..range.maxB
            } && g > r + 15 && g > b  // 綠色需要明顯高於紅,但對藍色要求較寬鬆

            // HSV檢測 - 精確的色相和飽和度
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            val hue = hsv[0]
            val saturation = hsv[1]
            val value = hsv[2]

            // 綠色系色相範圍:80-170度(涵蓋黃綠到青綠)
            val hsvGreen = (hue in 80f..170f) &&
                    (saturation > 0.2f) &&  // 降低飽和度要求
                    (value > 0.2f) &&       // 降低亮度要求
                    (value < 0.9f)          // 排除過亮的反光

            // 只要符合RGB或HSV其中一個條件即可(恢復OR邏輯)
            if (rgbGreen || hsvGreen) {
                mask[i] = true
                greenCount++
            }
        }

        Log.d("GreenCircleAnalyzer", "綠色像素數: $greenCount / ${pixels.size} (${(greenCount * 100f / pixels.size).toInt()}%)")

        return mask
    }

    private fun improvedNoiseFilter(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val result = mask.copyOf()
        val kernel = 2

        for (y in kernel until height - kernel) {
            for (x in kernel until width - kernel) {
                val index = y * width + x
                if (mask[index]) {
                    var greenCount = 0
                    var totalCount = 0

                    for (dy in -kernel..kernel) {
                        for (dx in -kernel..kernel) {
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until width && ny in 0 until height) {
                                val nIndex = ny * width + nx
                                totalCount++
                                if (mask[nIndex]) greenCount++
                            }
                        }
                    }

                    val ratio = greenCount.toFloat() / totalCount
                    if (ratio < 0.35f) {  // 適中的濾波門檻
                        result[index] = false
                    }
                } else {
                    var greenCount = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            val ny = y + dy
                            if (nx in 0 until width && ny in 0 until height) {
                                val nIndex = ny * width + nx
                                if (mask[nIndex]) greenCount++
                            }
                        }
                    }
                    if (greenCount >= 6) {
                        result[index] = true
                    }
                }
            }
        }

        return result
    }

    private fun preciseCircularAnalysis(regions: List<Region>): List<CircleCandidate> {
        val circles = mutableListOf<CircleCandidate>()

        for (region in regions) {
            val pixelCount = region.pixels.size

            // 過濾掉太小或太大的區域
            if (pixelCount < minPixelCount || pixelCount > maxPixelCount) {
                Log.d("GreenCircleAnalyzer", "區域像素數不合適: $pixelCount (範圍: $minPixelCount-$maxPixelCount)")
                continue
            }

            val centerX = region.pixels.map { it.x }.average().toFloat()
            val centerY = region.pixels.map { it.y }.average().toFloat()

            val distances = region.pixels.map { pixel ->
                sqrt((pixel.x - centerX) * (pixel.x - centerX) + (pixel.y - centerY) * (pixel.y - centerY))
            }

            val avgRadius = distances.average().toFloat()

            Log.d("GreenCircleAnalyzer", "區域: 像素=$pixelCount, 半徑=$avgRadius")

            // 檢查半徑是否在合理範圍內
            if (avgRadius !in minCircleRadius.toFloat()..maxCircleRadius.toFloat()) {
                Log.d("GreenCircleAnalyzer", "半徑超出範圍: $avgRadius (範圍: $minCircleRadius-$maxCircleRadius)")
                continue
            }

            // 計算圓形度
            val circularity = calculatePreciseCircularity(region.pixels, centerX, centerY, avgRadius)

            Log.d("GreenCircleAnalyzer", "圓形度: $circularity")

            if (circularity < minCircularity) {
                Log.d("GreenCircleAnalyzer", "圓形度不足: $circularity < $minCircularity")
                continue
            }

            // 計算長寬比
            val aspectRatio = calculateAspectRatio(region.bounds)

            Log.d("GreenCircleAnalyzer", "長寬比: $aspectRatio")

            // 放寬長寬比限制以支援側面橢圓形
            if (aspectRatio < 0.4f || aspectRatio > 1.5f) {
                Log.d("GreenCircleAnalyzer", "長寬比不合適: $aspectRatio")
                continue
            }

            // 計算面積一致性 - 僅作為參考,不作為硬性限制
            val theoreticalArea = PI.toFloat() * avgRadius * avgRadius
            val areaRatio = pixelCount / theoreticalArea

            Log.d("GreenCircleAnalyzer", "面積比率: $areaRatio (理論=${theoreticalArea.toInt()}, 實際=$pixelCount)")

            // 移除面積限制,因為綠色檢測可能包含周圍像素
            // 改用圓形度和長寬比作為主要判斷標準

            // 計算緊密度
            val compactness = calculateCompactness(pixelCount, avgRadius)

            // 綜合評分 - 主要依賴圓形度和長寬比
            val score = circularity * 0.6f +  // 圓形度最重要(60%)
                    (1f - abs(aspectRatio - 1f)) * 0.4f  // 長寬比(40%)

            Log.d("GreenCircleAnalyzer", "綜合評分: $score")

            circles.add(CircleCandidate(
                centerX, centerY, avgRadius, circularity, pixelCount, score
            ))
        }

        return circles
    }

    private fun calculatePreciseCircularity(pixels: List<Point>, centerX: Float, centerY: Float, avgRadius: Float): Float {
        if (pixels.size < 12) return 0f

        // 計算半徑的標準差
        val distances = pixels.map { pixel ->
            sqrt((pixel.x - centerX) * (pixel.x - centerX) + (pixel.y - centerY) * (pixel.y - centerY))
        }

        val variance = distances.map { (it - avgRadius) * (it - avgRadius) }.average()
        val stdDev = sqrt(variance.toFloat())

        // 標準差越小,圓形度越高
        val radiusConsistency = 1f - (stdDev / avgRadius).coerceAtMost(1f)

        // 計算面積比
        val actualArea = pixels.size.toFloat()
        val idealArea = PI.toFloat() * avgRadius * avgRadius
        val areaRatio = min(actualArea, idealArea) / max(actualArea, idealArea)

        // 綜合評估
        return radiusConsistency * 0.6f + areaRatio * 0.4f
    }

    private fun calculateAspectRatio(bounds: Rect): Float {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width == 0f || height == 0f) return 0f
        return if (width > height) height / width else width / height
    }

    private fun calculateCompactness(pixelCount: Int, radius: Float): Float {
        val idealPixelCount = PI * radius * radius
        return min(pixelCount.toFloat(), idealPixelCount.toFloat()) / max(pixelCount.toFloat(), idealPixelCount.toFloat())
    }

    private fun intelligentCircleSelection(circles: List<CircleCandidate>): List<CircleCandidate> {
        if (circles.isEmpty()) return emptyList()

        // 按評分排序，只選擇最佳的一個圓
        val sortedCircles = circles.sortedByDescending { it.score }

        // 降低評分門檻以支援側面橢圓形
        val bestCircle = sortedCircles.firstOrNull { it.score >= 0.30f }

        if (bestCircle != null) {
            Log.d("GreenCircleAnalyzer", "✓ 選擇最佳圓形: 中心(${bestCircle.centerX.toInt()}, ${bestCircle.centerY.toInt()}), 半徑=${bestCircle.radius.toInt()}, 評分=${String.format("%.2f", bestCircle.score)}")
            return listOf(bestCircle)  // 只返回一個最佳圓
        }

        Log.d("GreenCircleAnalyzer", "✗ 沒有符合條件的圓形（評分 < 0.30）")
        return emptyList()
    }

    private fun applyStabilityFilter(result: DetectionResult): DetectionResult {
        recentDetections.add(SimpleDetection(result.detected, result.areas.size, System.currentTimeMillis()))
        if (recentDetections.size > maxHistorySize) {
            recentDetections.removeAt(0)
        }

        val recentSuccess = recentDetections.takeLast(5).count { it.detected }
        val stabilityRatio = recentSuccess / 5f
        val adjustedConfidence = (result.confidence + stabilityRatio * 0.3f).coerceAtMost(1f)

        return result.copy(
            confidence = adjustedConfidence,
            message = when {
                result.detected && stabilityRatio > 0.6f -> "✓ 檢測穩定"
                result.detected && stabilityRatio > 0.4f -> "✓ 已檢測到綠色圓形"
                result.detected -> "⚡ 偵測中..."
                else -> "✗ 未檢測到綠色圓形"
            }
        )
    }

    private fun createDetectionResult(circles: List<CircleCandidate>): DetectionResult {
        if (circles.isEmpty()) {
            return DetectionResult(false, emptyList(), 0f, "未檢測到符合條件的綠色圓形", emptyList())
        }

        // 只有一個最佳圓
        val circle = circles[0]

        // 將圓形轉換為方框區域
        val margin = circle.radius * 0.25f
        val area = RectF(
            ((circle.centerX - circle.radius - margin) / imageWidth).coerceIn(0f, 1f),
            ((circle.centerY - circle.radius - margin) / imageHeight).coerceIn(0f, 1f),
            ((circle.centerX + circle.radius + margin) / imageWidth).coerceIn(0f, 1f),
            ((circle.centerY + circle.radius + margin) / imageHeight).coerceIn(0f, 1f)
        )

        return DetectionResult(
            detected = true,
            areas = listOf(area),
            confidence = circle.score,
            message = "檢測到綠色圓形標記 (12mm)",
            circles = circles
        )
    }

    private fun findConnectedRegions(mask: BooleanArray, width: Int, height: Int): List<Region> {
        val visited = BooleanArray(mask.size)
        val regions = mutableListOf<Region>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (mask[index] && !visited[index]) {
                    val region = floodFillRegion(mask, visited, x, y, width, height)
                    if (region.pixels.size >= minPixelCount && region.pixels.size <= maxPixelCount) {
                        regions.add(region)
                    }
                }
            }
        }

        return regions
    }

    private fun floodFillRegion(
        mask: BooleanArray, visited: BooleanArray,
        startX: Int, startY: Int, width: Int, height: Int
    ): Region {
        val pixels = mutableListOf<Point>()
        val queue = mutableListOf<Point>()
        queue.add(Point(startX, startY))

        var minX = startX; var maxX = startX
        var minY = startY; var maxY = startY

        while (queue.isNotEmpty()) {
            val point = queue.removeAt(0)
            val x = point.x
            val y = point.y
            val index = y * width + x

            if (x !in 0 until width || y !in 0 until height || visited[index] || !mask[index]) continue

            visited[index] = true
            pixels.add(point)

            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)

            if (x + 1 < width) queue.add(Point(x + 1, y))
            if (x - 1 >= 0) queue.add(Point(x - 1, y))
            if (y + 1 < height) queue.add(Point(x, y + 1))
            if (y - 1 >= 0) queue.add(Point(x, y - 1))
        }

        return Region(pixels, Rect(minX, minY, maxX, maxY))
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        val rotation = imageProxy.imageInfo.rotationDegrees
        return if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }
}


