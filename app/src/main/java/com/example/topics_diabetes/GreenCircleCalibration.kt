package com.example.topics_diabetes

import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 綠圓校準輔助類
 * 功能:
 * 1. 根據綠圓計算像素到實際尺寸的比例
 * 2. 計算傷口的實際面積
 * 3. 提供尺寸標註功能
 */
class GreenCircleCalibration {

    companion object {
        private const val TAG = "GreenCircleCalibration"

        // 標準綠圓直徑 (可配置)
        const val STANDARD_CIRCLE_DIAMETER_MM = 12.0  // 12mm 直徑的綠色貼紙
        const val ALTERNATIVE_DIAMETER_MM = 15.0      // 備選: 15mm
        const val LEGACY_DIAMETER_MM = 20.0           // 舊版: 20mm
    }

    /**
     * 校準信息
     */
    data class CalibrationInfo(
        val pixelsPerMm: Double,          // 像素/毫米比例
        val mmPerPixel: Double,           // 毫米/像素比例
        val circleRadiusPixels: Float,    // 偵測到的圓半徑(像素)
        val circleDiameterMm: Double,     // 使用的圓直徑(毫米)
        val imageWidth: Int,              // 影像寬度
        val imageHeight: Int,             // 影像高度
        val isCalibrated: Boolean         // 是否已校準
    )

    /**
     * 面積計算結果
     */
    data class AreaMeasurement(
        val pixelCount: Int,              // 像素數量
        val areaSquareMm: Double,         // 面積(平方毫米)
        val areaSquareCm: Double,         // 面積(平方厘米)
        val perimeter: Double = 0.0,      // 周長(毫米)
        val boundingBoxWidth: Double = 0.0,   // 外框寬度(毫米)
        val boundingBoxHeight: Double = 0.0   // 外框高度(毫米)
    )

    private var currentCalibration: CalibrationInfo? = null

    /**
     * 使用檢測到的綠圓進行校準
     */
    fun calibrateWithDetectedCircle(
        circle: CircleCandidate,
        imageWidth: Int,
        imageHeight: Int,
        circleDiameterMm: Double = STANDARD_CIRCLE_DIAMETER_MM
    ): CalibrationInfo {

        // 計算圓的半徑(像素)
        val radiusPixels = circle.radius
        val diameterPixels = radiusPixels * 2

        // 計算比例: 像素/毫米
        val pixelsPerMm = diameterPixels / circleDiameterMm
        val mmPerPixel = circleDiameterMm / diameterPixels

        val calibration = CalibrationInfo(
            pixelsPerMm = pixelsPerMm,
            mmPerPixel = mmPerPixel,
            circleRadiusPixels = radiusPixels,
            circleDiameterMm = circleDiameterMm,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            isCalibrated = true
        )

        currentCalibration = calibration

        Log.d(TAG, "校準完成:")
        Log.d(TAG, "  圓半徑: ${radiusPixels}px")
        Log.d(TAG, "  圓直徑: ${circleDiameterMm}mm")
        Log.d(TAG, "  比例: ${"%.2f".format(pixelsPerMm)} 像素/毫米")
        Log.d(TAG, "  比例: ${"%.3f".format(mmPerPixel)} 毫米/像素")

        return calibration
    }

    /**
     * 計算區域的實際面積
     */
    fun calculateRealArea(
        pixelCount: Int,
        calibration: CalibrationInfo? = currentCalibration
    ): AreaMeasurement? {

        if (calibration == null || !calibration.isCalibrated) {
            Log.w(TAG, "未校準,無法計算實際面積")
            return null
        }

        // 計算實際面積
        val areaSquareMm = pixelCount * calibration.mmPerPixel.pow(2)
        val areaSquareCm = areaSquareMm / 100.0

        return AreaMeasurement(
            pixelCount = pixelCount,
            areaSquareMm = areaSquareMm,
            areaSquareCm = areaSquareCm
        )
    }

    /**
     * 計算多模型分割結果的實際面積
     */
    fun calculateMultiModelAreas(
        modelResults: Map<String, Int>,  // 模型ID -> 像素數量
        calibration: CalibrationInfo? = currentCalibration
    ): Map<String, AreaMeasurement>? {

        if (calibration == null || !calibration.isCalibrated) {
            return null
        }

        return modelResults.mapValues { (modelId, pixelCount) ->
            val area = calculateRealArea(pixelCount, calibration)
            Log.d(TAG, "模型 $modelId: ${pixelCount}px = ${"%.2f".format(area?.areaSquareMm)}mm²")
            area ?: AreaMeasurement(pixelCount, 0.0, 0.0)
        }
    }

    /**
     * 計算兩點間的實際距離
     */
    fun calculateRealDistance(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        calibration: CalibrationInfo? = currentCalibration
    ): Double? {

        if (calibration == null || !calibration.isCalibrated) {
            return null
        }

        val distancePixels = sqrt((x2 - x1).pow(2) + (y2 - y1).pow(2))
        return distancePixels * calibration.mmPerPixel
    }

    /**
     * 獲取當前校準信息
     */
    fun getCurrentCalibration(): CalibrationInfo? = currentCalibration

    /**
     * 重置校準
     */
    fun resetCalibration() {
        currentCalibration = null
        Log.d(TAG, "校準已重置")
    }

    /**
     * 生成校準報告
     */
    fun generateCalibrationReport(calibration: CalibrationInfo? = currentCalibration): String {
        if (calibration == null || !calibration.isCalibrated) {
            return "未校準"
        }

        return buildString {
            appendLine("=== 綠圓校準報告 ===")
            appendLine("標準圓直徑: ${calibration.circleDiameterMm}mm")
            appendLine("檢測圓半徑: ${calibration.circleRadiusPixels.toInt()}像素")
            appendLine("影像尺寸: ${calibration.imageWidth}x${calibration.imageHeight}")
            appendLine("轉換比例: ${"%.2f".format(calibration.pixelsPerMm)} 像素/毫米")
            appendLine("轉換比例: ${"%.3f".format(calibration.mmPerPixel)} 毫米/像素")
            appendLine("1cm² = ${(calibration.pixelsPerMm.pow(2) * 100).toInt()} 像素")
        }
    }

    /**
     * 驗證校準是否合理
     */
    fun validateCalibration(calibration: CalibrationInfo): ValidationResult {
        val issues = mutableListOf<String>()

        // 檢查比例是否合理 (通常在 10-100 像素/mm 之間)
        if (calibration.pixelsPerMm < 5 || calibration.pixelsPerMm > 200) {
            issues.add("比例異常: ${"%.2f".format(calibration.pixelsPerMm)} 像素/毫米")
        }

        // 檢查圓的大小是否合理
        if (calibration.circleRadiusPixels < 20 || calibration.circleRadiusPixels > 500) {
            issues.add("圓半徑異常: ${calibration.circleRadiusPixels}像素")
        }

        val isValid = issues.isEmpty()

        return ValidationResult(
            isValid = isValid,
            issues = issues,
            recommendation = if (isValid) {
                "校準有效,可以進行測量"
            } else {
                "建議重新校準或調整拍攝距離"
            }
        )
    }

    data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>,
        val recommendation: String
    )
}

/**
 * 擴展函數: 為 DetectionResult 添加校準功能
 */
fun DetectionResult.getCalibrationInfo(
    imageWidth: Int,
    imageHeight: Int,
    circleDiameterMm: Double = GreenCircleCalibration.STANDARD_CIRCLE_DIAMETER_MM
): GreenCircleCalibration.CalibrationInfo? {

    if (!detected || circles.isEmpty()) {
        return null
    }

    // 使用信心度最高的圓進行校準
    val bestCircle = circles.maxByOrNull { it.score } ?: return null

    val calibration = GreenCircleCalibration()
    return calibration.calibrateWithDetectedCircle(
        bestCircle,
        imageWidth,
        imageHeight,
        circleDiameterMm
    )
}

/**
 * 多模型結果與綠圓校準整合
 */
class MultiModelCalibrationHelper {

    private val calibration = GreenCircleCalibration()

    /**
     * 整合分析報告
     */
    data class IntegratedAnalysisReport(
        val calibrationInfo: GreenCircleCalibration.CalibrationInfo?,
        val modelAreas: Map<String, GreenCircleCalibration.AreaMeasurement>,
        val totalWoundArea: GreenCircleCalibration.AreaMeasurement?,
        val tissuePercentages: Map<String, Double>,
        val recommendations: List<String>
    )

    /**
     * 生成完整的分析報告
     */
    fun generateIntegratedReport(
        detectionResult: DetectionResult,
        modelResults: Map<String, Int>,  // 模型ID -> 像素數量
        modelNames: Map<String, String>, // 模型ID -> 中文名稱
        imageWidth: Int,
        imageHeight: Int
    ): IntegratedAnalysisReport {

        // 1. 校準信息
        val calibrationInfo = detectionResult.getCalibrationInfo(imageWidth, imageHeight)

        // 2. 計算各模型的實際面積
        val modelAreas = if (calibrationInfo != null) {
            calibration.calculateMultiModelAreas(modelResults, calibrationInfo) ?: emptyMap()
        } else {
            emptyMap()
        }

        // 3. 計算總傷口面積
        val totalPixels = modelResults.values.sum()
        val totalArea = if (calibrationInfo != null) {
            calibration.calculateRealArea(totalPixels, calibrationInfo)
        } else {
            null
        }

        // 4. 計算組織百分比
        val tissuePercentages = if (totalPixels > 0) {
            modelResults.mapValues { (_, pixels) ->
                (pixels.toDouble() / totalPixels) * 100.0
            }
        } else {
            emptyMap()
        }

        // 5. 生成建議
        val recommendations = generateRecommendations(
            tissuePercentages,
            modelNames,
            calibrationInfo != null
        )

        return IntegratedAnalysisReport(
            calibrationInfo = calibrationInfo,
            modelAreas = modelAreas,
            totalWoundArea = totalArea,
            tissuePercentages = tissuePercentages,
            recommendations = recommendations
        )
    }

    private fun generateRecommendations(
        percentages: Map<String, Double>,
        modelNames: Map<String, String>,
        isCalibrated: Boolean
    ): List<String> {
        val recommendations = mutableListOf<String>()

        if (!isCalibrated) {
            recommendations.add("⚠️ 未檢測到綠色標記,無法計算實際尺寸")
            recommendations.add("💡 建議在傷口旁放置綠色圓形貼紙以獲得精確測量")
        }

        // 根據組織類型百分比給出建議
        val necroticTotal = percentages.filter { it.key.contains("necrotic") }.values.sum()
        val granulationTotal = percentages.filter { it.key.contains("granulation") }.values.sum()
        val epithelizationTotal = percentages.filter { it.key.contains("epithelization") }.values.sum()
        val sloughTotal = percentages.filter { it.key.contains("slough") }.values.sum()

        // 壞死組織建議
        when {
            necroticTotal > 30 -> {
                recommendations.add("⚠️ 壞死組織比例較高(${necroticTotal.toInt()}%),建議清創處理")
            }
            necroticTotal > 10 -> {
                recommendations.add("⚡ 存在壞死組織(${necroticTotal.toInt()}%),需持續觀察")
            }
        }

        // 腐肉組織建議
        if (sloughTotal > 20) {
            recommendations.add("⚠️ 腐肉組織較多(${sloughTotal.toInt()}%),建議清創")
        }

        // 肉芽組織建議
        when {
            granulationTotal > 50 -> {
                recommendations.add("✓ 肉芽組織良好(${granulationTotal.toInt()}%),傷口癒合進展順利")
            }
            granulationTotal < 20 -> {
                recommendations.add("💡 肉芽組織較少(${granulationTotal.toInt()}%),可能需要促進組織生長")
            }
        }

        // 上皮化建議
        if (epithelizationTotal > 20) {
            recommendations.add("✓ 上皮化進展良好(${epithelizationTotal.toInt()}%)")
        }

        return recommendations
    }
}