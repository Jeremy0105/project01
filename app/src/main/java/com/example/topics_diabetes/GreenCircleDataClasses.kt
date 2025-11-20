package com.example.topics_diabetes

import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF

/**
 * 綠圓檢測相關數據類
 * 所有綠圓檢測器共享這些數據類定義
 */

/**
 * 圓形候選
 */
data class CircleCandidate(
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val circularity: Float,
    val pixelCount: Int,
    val score: Float,
    val aspectRatio: Float = 1f
)

/**
 * 檢測結果
 */
data class DetectionResult(
    val detected: Boolean,
    val areas: List<RectF>,
    val confidence: Float,
    val message: String,
    val circles: List<CircleCandidate> = emptyList()
)

/**
 * 綠色範圍
 */
data class GreenRange(
    val minR: Int, val maxR: Int,
    val minG: Int, val maxG: Int,
    val minB: Int, val maxB: Int
)

/**
 * 區域
 */
data class Region(
    val pixels: List<Point>,
    val bounds: Rect
)

data class SimpleDetection(
    val detected: Boolean,
    val count: Int,
    val timestamp: Long
)
