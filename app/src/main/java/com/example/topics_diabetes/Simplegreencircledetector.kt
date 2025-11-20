package com.example.topics_diabetes

import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 簡化版綠圓檢測器 - 用於靜態圖片分析
 * 修改版: 只返回一個最佳綠圓,固定使用 12mm 校準
 */
class SimpleGreenCircleDetector {

    companion object {
        private const val TAG = "SimpleGreenCircleDetector"

        // 檢測參數
        private const val MIN_CIRCLE_RADIUS = 8
        private const val MAX_CIRCLE_RADIUS = 120
        private const val MIN_CIRCULARITY = 0.45f
        private const val MIN_PIXEL_COUNT = 80
        private const val MAX_PIXEL_COUNT = 5000
        private const val MIN_SCORE = 0.40f

        // ⭐ 固定使用 12mm (1.2cm) 的綠圓貼紙
        const val FIXED_CIRCLE_DIAMETER_MM = 12.0
    }

    // 綠色範圍定義
    private val greenRanges = listOf(
        GreenRange(minR = 0, maxR = 120, minG = 100, maxG = 200, minB = 0, maxB = 120),
        GreenRange(minR = 0, maxR = 140, minG = 120, maxG = 230, minB = 0, maxB = 140),
        GreenRange(minR = 0, maxR = 150, minG = 140, maxG = 255, minB = 100, maxB = 200)
    )

    /**
     * 從像素數組檢測綠圓
     * 修改版: 只返回一個最佳綠圓
     */
    fun detectFromPixels(pixels: IntArray, width: Int, height: Int): DetectionResult {
        try {
            Log.d(TAG, "開始檢測綠圓: ${width}x${height}")

            // 1. 創建綠色遮罩
            val greenMask = createGreenMask(pixels, width, height)

            // 2. 降噪處理
            val cleanedMask = noiseFilter(greenMask, width, height)

            // 3. 找到連通區域
            val regions = findConnectedRegions(cleanedMask, width, height)
            Log.d(TAG, "找到 ${regions.size} 個綠色區域")

            // 4. 圓形分析
            val circleList = analyzeCircles(regions)
            Log.d(TAG, "找到 ${circleList.size} 個圓形候選")

            // 5. 選擇最佳圓形 ⭐ 修改: 只選一個
            val bestCircle = selectBestCircle(circleList)

            if (bestCircle != null) {
                Log.d(TAG, "✓ 最終選擇: 中心(${bestCircle.centerX.toInt()}, ${bestCircle.centerY.toInt()}), " +
                        "半徑=${bestCircle.radius.toInt()}, 評分=${"%.2f".format(bestCircle.score)}")
            } else {
                Log.d(TAG, "✗ 未找到符合條件的綠圓")
            }

            return createDetectionResult(bestCircle, width, height)

        } catch (e: Exception) {
            Log.e(TAG, "檢測錯誤", e)
            return DetectionResult(
                detected = false,
                areas = emptyList(),
                confidence = 0f,
                message = "檢測錯誤: ${e.message}",
                circles = emptyList()
            )
        }
    }

    private fun createGreenMask(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val mask = BooleanArray(pixels.size)
        var greenCount = 0

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            // RGB 檢測
            val rgbGreen = greenRanges.any { range ->
                r in range.minR..range.maxR &&
                        g in range.minG..range.maxG &&
                        b in range.minB..range.maxB
            } && g > r + 15 && g > b

            // HSV 檢測
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            val hue = hsv[0]
            val saturation = hsv[1]
            val value = hsv[2]

            val hsvGreen = (hue in 80f..170f) &&
                    (saturation > 0.2f) &&
                    (value > 0.2f) &&
                    (value < 0.9f)

            if (rgbGreen || hsvGreen) {
                mask[i] = true
                greenCount++
            }
        }

        val percentage = (greenCount * 100f / pixels.size)
        Log.d(TAG, "綠色像素: $greenCount / ${pixels.size} (${percentage.toInt()}%)")

        return mask
    }

    private fun noiseFilter(mask: BooleanArray, width: Int, height: Int): BooleanArray {
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
                                totalCount++
                                if (mask[ny * width + nx]) greenCount++
                            }
                        }
                    }

                    val ratio = greenCount.toFloat() / totalCount
                    if (ratio < 0.35f) {
                        result[index] = false
                    }
                }
            }
        }

        return result
    }

    private fun findConnectedRegions(mask: BooleanArray, width: Int, height: Int): List<Region> {
        val visited = BooleanArray(mask.size)
        val regions = mutableListOf<Region>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (mask[index] && !visited[index]) {
                    val region = floodFill(mask, visited, x, y, width, height)
                    if (region.pixels.size in MIN_PIXEL_COUNT..MAX_PIXEL_COUNT) {
                        regions.add(region)
                    }
                }
            }
        }

        return regions
    }

    private fun floodFill(
        mask: BooleanArray,
        visited: BooleanArray,
        startX: Int,
        startY: Int,
        width: Int,
        height: Int
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

            if (x !in 0 until width || y !in 0 until height || visited[index] || !mask[index]) {
                continue
            }

            visited[index] = true
            pixels.add(point)

            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)

            queue.add(Point(x + 1, y))
            queue.add(Point(x - 1, y))
            queue.add(Point(x, y + 1))
            queue.add(Point(x, y - 1))
        }

        return Region(pixels, Rect(minX, minY, maxX, maxY))
    }

    private fun analyzeCircles(regions: List<Region>): List<CircleCandidate> {
        val circleList = mutableListOf<CircleCandidate>()

        for (region in regions) {
            val pixelCount = region.pixels.size

            if (pixelCount !in MIN_PIXEL_COUNT..MAX_PIXEL_COUNT) {
                continue
            }

            val centerX = region.pixels.map { it.x }.average().toFloat()
            val centerY = region.pixels.map { it.y }.average().toFloat()

            val distances = region.pixels.map { pixel ->
                sqrt(
                    ((pixel.x - centerX) * (pixel.x - centerX) +
                            (pixel.y - centerY) * (pixel.y - centerY)).toFloat()
                )
            }

            val avgRadius = distances.average().toFloat()

            if (avgRadius !in MIN_CIRCLE_RADIUS.toFloat()..MAX_CIRCLE_RADIUS.toFloat()) {
                continue
            }

            val circularity = calculateCircularity(region.pixels, centerX, centerY, avgRadius)
            if (circularity < MIN_CIRCULARITY) {
                continue
            }

            val aspectRatio = calculateAspectRatio(region.bounds)
            if (aspectRatio !in 0.6f..1.4f) {
                continue
            }

            val score = circularity * 0.6f + (1f - abs(aspectRatio - 1f)) * 0.4f

            circleList.add(
                CircleCandidate(
                    centerX = centerX,
                    centerY = centerY,
                    radius = avgRadius,
                    circularity = circularity,
                    pixelCount = pixelCount,
                    score = score,
                    aspectRatio = aspectRatio
                )
            )
        }

        return circleList
    }

    private fun calculateCircularity(
        pixels: List<Point>,
        centerX: Float,
        centerY: Float,
        avgRadius: Float
    ): Float {
        if (pixels.size < 12) return 0f

        val distances = pixels.map { pixel ->
            sqrt(
                ((pixel.x - centerX) * (pixel.x - centerX) +
                        (pixel.y - centerY) * (pixel.y - centerY)).toFloat()
            )
        }

        val variance = distances.map { (it - avgRadius) * (it - avgRadius) }.average()
        val stdDev = sqrt(variance.toFloat())

        val radiusConsistency = 1f - (stdDev / avgRadius).coerceAtMost(1f)

        val actualArea = pixels.size.toFloat()
        val idealArea = PI.toFloat() * avgRadius * avgRadius
        val areaRatio = min(actualArea, idealArea) / max(actualArea, idealArea)

        return radiusConsistency * 0.6f + areaRatio * 0.4f
    }

    private fun calculateAspectRatio(bounds: Rect): Float {
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        if (width == 0f || height == 0f) return 0f
        return if (width > height) height / width else width / height
    }

    /**
     * ⭐ 修改: 只選擇一個最佳圓形
     */
    private fun selectBestCircle(circleList: List<CircleCandidate>): CircleCandidate? {
        if (circleList.isEmpty()) return null

        // 按評分排序,選擇最高分的
        val sortedCircles = circleList.sortedByDescending { it.score }

        // 返回評分最高且超過門檻的圓形
        val bestCircle = sortedCircles.firstOrNull { it.score >= MIN_SCORE }

        if (bestCircle != null) {
            Log.d(TAG, "✓ 選擇最佳圓形: " +
                    "中心(${bestCircle.centerX.toInt()}, ${bestCircle.centerY.toInt()}), " +
                    "半徑=${bestCircle.radius.toInt()}, " +
                    "圓形度=${"%.1f".format(bestCircle.circularity * 100)}%, " +
                    "評分=${"%.2f".format(bestCircle.score)}")
        }

        return bestCircle
    }

    /**
     * ⭐ 修改: 創建單個圓形的檢測結果
     */
    private fun createDetectionResult(
        circle: CircleCandidate?,
        imageWidth: Int,
        imageHeight: Int
    ): DetectionResult {
        if (circle == null) {
            return DetectionResult(
                detected = false,
                areas = emptyList(),
                confidence = 0f,
                message = "未檢測到符合條件的綠色圓形 (12mm)",
                circles = emptyList()
            )
        }

        // 創建單個圓形的區域
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
            message = "✓ 檢測到綠色圓形標記 (12mm)",
            circles = listOf(circle)
        )
    }
}