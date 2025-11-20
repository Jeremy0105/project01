package com.example.topics_diabetes

import android.app.AlertDialog
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.sqrt
import kotlin.math.pow

class AnalysisActivity : AppCompatActivity() {
    private lateinit var imageView: ImageView
    private lateinit var resultTextView: TextView
    private lateinit var backButton: Button
    private lateinit var toggleButton: Button
    private lateinit var saveToGroupButton: Button

    // 進度顯示元件
    private lateinit var progressContainer: CardView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var progressPercentage: TextView

    // 模型選擇器
    private lateinit var modelSelectorContainer: LinearLayout
    private lateinit var modelSpinner: Spinner

    // 多模型相關
    private var multiModelHelper: MultiModelHelper? = null
    private var currentBitmap: Bitmap? = null
    private var overlayBitmap: Bitmap? = null
    private var grayscaleBitmap: Bitmap? = null
    private var showingGrayscale = false
    private var currentImageUri: Uri? = null
    private var analysisResult: MultiModelHelper.MultiModelResult? = null

    // 單模型顯示用的 Bitmap 緩存
    private val singleModelBitmaps = mutableMapOf<String, Bitmap>()

    // 綠色圓形檢測相關
    private var detectedCircles = listOf<CircleInfo>()
    private var showCircleMarkers = true

    // 面積計算相關 - 固定使用 12mm
    private var calibrationFactor: Float = 0f
    private val referenceCircleDiameter: Float = 1.2f  // 固定 12mm = 1.2cm
//  功能：
//  - 初始化所有 UI 元件（ImageView, TextView, Button等）
//  - 調用 initializeGTModel() 初始化 ML 模型
//  - 從 Intent 獲取圖片 URI
//  - 調用 loadImageBitmap() 加載圖片
//  - 顯示圖片在 ImageView
//  - 調用 analyzeImage() 開始分析
//  - 設置按鈕監聽器
//
//  輸入： Intent (含圖片 URI)
//  輸出： 初始化完成的 Activity
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_analysis)

        initializeViews()
        initializeGTModel()
        // 移除校準對話框 - 直接使用固定的 12mm

        val imageUriString = intent.getStringExtra(CameraActivity.EXTRA_IMAGE_URI)
        if (imageUriString != null) {
            currentImageUri = Uri.parse(imageUriString)
            loadImageBitmap(currentImageUri!!)
            if (currentBitmap != null) {
                imageView.setImageBitmap(currentBitmap)
            } else {
                imageView.setImageURI(currentImageUri)
            }
            analyzeImage(currentImageUri!!)
        }

        setupButtonListeners()
    }

    private fun initializeViews() {
        imageView = findViewById(R.id.analysis_image_view)
        resultTextView = findViewById(R.id.result_text_view)
        backButton = findViewById(R.id.back_button)
        toggleButton = findViewById(R.id.toggle_button)
        saveToGroupButton = findViewById(R.id.save_to_group_button)

        progressContainer = findViewById(R.id.progress_container)
        progressBar = findViewById(R.id.progress_bar)
        progressText = findViewById(R.id.progress_text)
        progressPercentage = findViewById(R.id.progress_percentage)

        modelSelectorContainer = findViewById(R.id.model_selector_container)
        modelSpinner = findViewById(R.id.model_spinner)

        toggleButton.isEnabled = false
        saveToGroupButton.isEnabled = false
    }

    // ==================== 面積計算功能 ====================

    /**
     * 根據檢測到的綠色圓形計算校準因子
     */
    private fun calculateCalibrationFactor(circles: List<CircleInfo>): Float {
        if (circles.isEmpty()) {
            Log.w("AnalysisActivity", "沒有檢測到綠色圓形,無法校準")
            return 0f
        }

        // 使用圓形度最高的圓形作為參考
        val referenceCircle = circles.maxByOrNull { it.circularity } ?: circles[0]

        // 計算參考圓形的像素直徑
        val pixelDiameter = referenceCircle.radius * 2

        // 計算校準因子: 實際尺寸 / 像素尺寸 (cm/pixel)
        val factor = referenceCircleDiameter / pixelDiameter

        Log.d("AnalysisActivity", "校準信息:")
        Log.d("AnalysisActivity", "  參考圓形半徑: ${referenceCircle.radius} px")
        Log.d("AnalysisActivity", "  參考圓形直徑: $pixelDiameter px")
        Log.d("AnalysisActivity", "  實際直徑: $referenceCircleDiameter cm (12mm)")
        Log.d("AnalysisActivity", "  校準因子: $factor cm/px")

        return factor
    }

    /**
     * 將像素面積轉換為實際面積
     */
    private fun pixelsToRealArea(pixelCount: Int): Float {
        if (calibrationFactor == 0f) {
            Log.w("AnalysisActivity", "未校準,無法計算實際面積")
            return 0f
        }

        // 實際面積 = 像素數 × (校準因子)²
        return pixelCount * calibrationFactor.pow(2)
    }

    /**
     * 格式化面積顯示
     */
    private fun formatArea(areaCm2: Float): String {
        return when {
            areaCm2 < 1f -> "%.2f mm² (%.4f cm²)".format(areaCm2 * 100, areaCm2)
            areaCm2 < 100f -> "%.2f cm²".format(areaCm2)
            else -> "%.2f cm² (%.2f dm²)".format(areaCm2, areaCm2 / 100)
        }
    }

    // ==================== 原有功能 ====================

    private fun setupButtonListeners() {
        backButton.setOnClickListener { finish() }
        toggleButton.setOnClickListener { toggleDisplayMode() }
        saveToGroupButton.setOnClickListener { showGroupSelectionDialog() }
    }

    private fun updateProgress(current: Int, total: Int, modelName: String) {
        runOnUiThread {
            val percentage = (current * 100) / total
            progressBar.progress = percentage
            progressText.text = "正在分析 $modelName"
            progressPercentage.text = "$percentage%"
        }
    }

    private fun showProgress() {
        runOnUiThread {
            progressContainer.visibility = View.VISIBLE
            progressBar.progress = 0
            progressText.text = "正在初始化..."
            progressPercentage.text = "0%"
        }
    }

    private fun hideProgress() {
        runOnUiThread {
            progressContainer.visibility = View.GONE
        }
    }

    // ==================== 綠色圓形檢測功能 ====================

    private suspend fun detectGreenCircles(bitmap: Bitmap): List<CircleInfo> = withContext(Dispatchers.Default) {
        Log.d("AnalysisActivity", "開始檢測綠色圓形標記 (12mm)")

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val greenMask = createGreenMask(pixels, width, height)
        val regions = findConnectedRegions(greenMask, width, height)
        val circles = analyzeCircles(regions, width, height)

        Log.d("AnalysisActivity", "檢測到 ${circles.size} 個綠色圓形")

        circles
    }

    private fun createGreenMask(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val mask = BooleanArray(width * height)

        for (i in pixels.indices) {
            val color = pixels[i]
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)

            val isGreen = (g > r + 15 && g > b) && (g in 100..230)
            mask[i] = isGreen
        }

        return mask
    }

    private fun findConnectedRegions(mask: BooleanArray, width: Int, height: Int): List<Region> {
        val visited = BooleanArray(mask.size)
        val regions = mutableListOf<Region>()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (mask[index] && !visited[index]) {
                    val region = floodFill(mask, visited, x, y, width, height)
                    if (region.pixels.size in 80..5000) {
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

        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        while (queue.isNotEmpty()) {
            val point = queue.removeAt(0)
            val x = point.x
            val y = point.y
            val index = y * width + x

            if (x !in 0 until width || y !in 0 until height || visited[index] || !mask[index]) continue

            visited[index] = true
            pixels.add(point)

            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)

            queue.add(Point(x + 1, y))
            queue.add(Point(x - 1, y))
            queue.add(Point(x, y + 1))
            queue.add(Point(x, y - 1))
        }

        return Region(pixels, Rect(minX, minY, maxX, maxY))
    }

    private fun analyzeCircles(regions: List<Region>, width: Int, height: Int): List<CircleInfo> {
        val circles = mutableListOf<CircleInfo>()

        for (region in regions) {
            val centerX = region.pixels.map { it.x }.average().toFloat()
            val centerY = region.pixels.map { it.y }.average().toFloat()

            val distances = region.pixels.map { pixel ->
                sqrt(((pixel.x - centerX) * (pixel.x - centerX) + (pixel.y - centerY) * (pixel.y - centerY)).toDouble()).toFloat()
            }

            val avgRadius = distances.average().toFloat()

            val variance = distances.map { (it - avgRadius) * (it - avgRadius) }.average()
            val stdDev = sqrt(variance.toFloat())
            val circularity = 1f - (stdDev / avgRadius).coerceAtMost(1f)

            val regionWidth = region.bounds.width().toFloat()
            val regionHeight = region.bounds.height().toFloat()
            val aspectRatio = if (regionWidth > regionHeight) {
                regionHeight / regionWidth
            } else {
                regionWidth / regionHeight
            }

            // 放寬限制以支援側面貼的綠圓 (會變成橢圓形)
            if (circularity > 0.35f && aspectRatio > 0.4f && avgRadius in 8f..120f) {
                circles.add(
                    CircleInfo(
                        centerX = centerX,
                        centerY = centerY,
                        radius = avgRadius,
                        circularity = circularity,
                        pixelCount = region.pixels.size
                    )
                )
            }
        }

        // 只返回圓形度最高的一個圓
        val bestCircle = circles.maxByOrNull { it.circularity }
        return if (bestCircle != null) listOf(bestCircle) else emptyList()
    }

    private fun drawCircleMarkers(bitmap: Bitmap, circles: List<CircleInfo>): Bitmap {
        val resultBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(resultBitmap)

        val strokePaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }

        val fillPaint = Paint().apply {
            color = Color.argb(20, 0, 255, 0)
            style = Paint.Style.FILL
        }

        val textPaint = Paint().apply {
            color = Color.GREEN
            textSize = 40f
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        circles.forEachIndexed { index, circle ->
            canvas.drawCircle(circle.centerX, circle.centerY, circle.radius, fillPaint)
            canvas.drawCircle(circle.centerX, circle.centerY, circle.radius, strokePaint)

            // 顯示標記編號和實際直徑
            val diameter = circle.radius * 2 * calibrationFactor
            val label = if (calibrationFactor > 0) {
                "✓ ${index + 1} (Ø%.1f cm)".format(diameter)
            } else {
                "✓ ${index + 1}"
            }

            canvas.drawText(label, circle.centerX - 80, circle.centerY - circle.radius - 20, textPaint)

            drawCornerMarkers(canvas, circle, strokePaint)
        }

        return resultBitmap
    }

    private fun drawCornerMarkers(canvas: Canvas, circle: CircleInfo, paint: Paint) {
        val markerLength = 30f
        val markerPaint = Paint(paint).apply {
            strokeWidth = 10f
        }

        val r = circle.radius
        val cx = circle.centerX
        val cy = circle.centerY

        canvas.drawLine(cx - markerLength / 2, cy - r, cx + markerLength / 2, cy - r, markerPaint)
        canvas.drawLine(cx - markerLength / 2, cy + r, cx + markerLength / 2, cy + r, markerPaint)
        canvas.drawLine(cx - r, cy - markerLength / 2, cx - r, cy + markerLength / 2, markerPaint)
        canvas.drawLine(cx + r, cy - markerLength / 2, cx + r, cy + markerLength / 2, markerPaint)
    }

    // ==================== 模型選擇器 ====================

    private fun setupModelSelector(result: MultiModelHelper.MultiModelResult) {
        val modelNames = mutableListOf("全部組織")
        val modelIds = mutableListOf("all")

        // ⭐ 使用 Set 追蹤已添加的模型名稱,避免重複
        val addedNames = mutableSetOf<String>()
        addedNames.add("全部組織")  // 先加入"全部組織"

        result.individualResults.forEach { (id, tissueResult) ->
            // 只在名稱未出現過時才添加
            if (tissueResult.modelName !in addedNames) {
                modelNames.add(tissueResult.modelName)
                modelIds.add(id)
                addedNames.add(tissueResult.modelName)
            }
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter

        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                showingGrayscale = false

                if (position == 0) {
                    val displayBitmap = if (showCircleMarkers && detectedCircles.isNotEmpty()) {
                        drawCircleMarkers(result.combinedBitmap, detectedCircles)
                    } else {
                        result.combinedBitmap
                    }
                    imageView.setImageBitmap(displayBitmap)
                    toggleButton.text = "切換到灰階圖"
                } else {
                    val modelId = modelIds[position]
                    showSingleModelResult(modelId, result)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        modelSelectorContainer.visibility = View.VISIBLE
    }
//功能：
//  - 檢查緩存中是否已有該模型的圖片
//  - 如果沒有，調用 createSingleModelBitmap() 創建
//  - 顯示單模型遮罩圖到 ImageView
//  - 保存到緩存 singleModelBitmaps
//
//  輸入： modelId, MultiModelResult
//  輸出： 顯示單模型圖片
    private fun showSingleModelResult(modelId: String, result: MultiModelHelper.MultiModelResult) {
        val tissueResult = result.individualResults[modelId] ?: return

        if (singleModelBitmaps.containsKey(modelId)) {
            imageView.setImageBitmap(singleModelBitmaps[modelId])
            return
        }

        lifecycleScope.launch {
            try {
                val bitmap = currentBitmap ?: return@launch
                val singleBitmap = createSingleModelBitmap(bitmap, tissueResult)

                singleModelBitmaps[modelId] = singleBitmap

                runOnUiThread {
                    imageView.setImageBitmap(singleBitmap)
                }
            } catch (e: Exception) {
                Log.e("AnalysisActivity", "創建單模型圖像失敗", e)
            }
        }
    }
//功能：
//  - 複製原圖
//  - 提取該模型的輸出數據
//  - 創建遮罩 Bitmap
//  - FOR 每個像素：
//    - 正規化輸出值
//    - 如果 > 0.5 → 設為組織顏色
//    - 否則 → 設為透明
//  - 縮放遮罩到原圖尺寸
//  - 使用 Paint (alpha=180) 疊加到原圖
//  - 返回疊加圖
//
//  輸入： 原圖, TissueResult
//  輸出： Bitmap (原圖 + 單色遮罩)
    private fun createSingleModelBitmap(
        originalBitmap: Bitmap,
        tissueResult: MultiModelHelper.TissueResult
    ): Bitmap {
        val outputBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(outputBitmap)

        val width = originalBitmap.width
        val height = originalBitmap.height
        val outputData = tissueResult.outputData
        val outputShape = tissueResult.outputShape

        val outputWidth = if (outputShape.size >= 4) outputShape[2] else width
        val outputHeight = if (outputShape.size >= 4) outputShape[1] else height

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

            pixels[i] = if (normalizedValue > 0.5f) {
                tissueResult.color
            } else {
                Color.TRANSPARENT
            }
        }

        maskBitmap.setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)

        val scaledMask = if (width != outputWidth || height != outputHeight) {
            Bitmap.createScaledBitmap(maskBitmap, width, height, true).also {
                maskBitmap.recycle()
            }
        } else {
            maskBitmap
        }

        val paint = Paint().apply {
            alpha = 180
        }
        canvas.drawBitmap(scaledMask, 0f, 0f, paint)
        scaledMask.recycle()

        return outputBitmap
    }

    private fun toggleDisplayMode() {
        if (overlayBitmap != null && grayscaleBitmap != null) {
            showingGrayscale = !showingGrayscale

            if (showingGrayscale) {
                imageView.setImageBitmap(grayscaleBitmap)
                toggleButton.text = "切換到彩色疊加"
                Toast.makeText(this, "顯示灰階分割圖", Toast.LENGTH_SHORT).show()
            } else {
                val displayBitmap = if (showCircleMarkers && detectedCircles.isNotEmpty()) {
                    drawCircleMarkers(overlayBitmap!!, detectedCircles)
                } else {
                    overlayBitmap
                }
                imageView.setImageBitmap(displayBitmap)
                toggleButton.text = "切換到灰階圖"
                Toast.makeText(this, "顯示彩色疊加圖", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ==================== 群組管理 ====================

    private fun showGroupSelectionDialog() {
        val groups = loadWoundGroups()

        val groupNames = groups.map { it.name }.toMutableList()
        groupNames.add("創建新群組...")

        AlertDialog.Builder(this)
            .setTitle("選擇要保存到的群組")
            .setItems(groupNames.toTypedArray()) { _, which ->
                if (which == groupNames.size - 1) {
                    showCreateGroupDialog()
                } else {
                    saveAnalysisToGroup(groups[which])
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCreateGroupDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_wound_group, null)
        val nameEditText = dialogView.findViewById<EditText>(R.id.group_name_edit_text)
        val locationEditText = dialogView.findViewById<EditText>(R.id.wound_location_edit_text)

        nameEditText.hint = "例如:張三 - 腿部傷口"
        locationEditText.hint = "例如:左腿膝蓋"

        AlertDialog.Builder(this)
            .setTitle("創建新的分析群組")
            .setView(dialogView)
            .setPositiveButton("創建並保存") { _, _ ->
                val name = nameEditText.text.toString().trim()
                val location = locationEditText.text.toString().trim()

                if (name.isNotEmpty()) {
                    val newGroup = createWoundGroup(name, location)
                    saveAnalysisToGroup(newGroup)
                } else {
                    Toast.makeText(this, "請輸入群組名稱", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createWoundGroup(name: String, location: String): WoundGroup {
        val groupId = UUID.randomUUID().toString()
        val groupDir = File(getExternalFilesDir(null), "WoundGroups/$groupId")
        if (!groupDir.exists()) {
            groupDir.mkdirs()
        }

        val woundGroup = WoundGroup(
            id = groupId,
            name = name,
            location = location,
            creationDate = Date(),
            lastUpdated = Date(),
            imageCount = 0
        )

        saveWoundGroupInfo(woundGroup)
        return woundGroup
    }

    private fun saveWoundGroupInfo(woundGroup: WoundGroup) {
        val infoFile = File(getExternalFilesDir(null), "WoundGroups/${woundGroup.id}/info.txt")
        infoFile.writeText(
            "name=${woundGroup.name}\n" +
                    "location=${woundGroup.location}\n" +
                    "created=${woundGroup.creationDate.time}\n" +
                    "updated=${woundGroup.lastUpdated.time}\n" +
                    "count=${woundGroup.imageCount}"
        )
    }

    private fun saveAnalysisToGroup(group: WoundGroup) {
        try {
            val groupDir = File(getExternalFilesDir(null), "WoundGroups/${group.id}")
            if (!groupDir.exists()) {
                groupDir.mkdirs()
            }

            val timeStamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis())

            val originalImageFile = File(groupDir, "${timeStamp}_original.jpg")
            currentImageUri?.let { uri ->
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(originalImageFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            analysisResult?.let { result ->
                val overlayFile = File(groupDir, "${timeStamp}_overlay.jpg")
                FileOutputStream(overlayFile).use { out ->
                    result.combinedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                val grayscaleFile = File(groupDir, "${timeStamp}_grayscale.jpg")
                FileOutputStream(grayscaleFile).use { out ->
                    result.grayscaleBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }

                val reportFile = File(groupDir, "${timeStamp}_report.txt")
                val report = buildAnalysisReport(result, group)
                reportFile.writeText(report)
            }

            updateGroupImageCount(group.id)

            Toast.makeText(this, "分析結果已保存到群組: ${group.name}", Toast.LENGTH_LONG).show()

            AlertDialog.Builder(this)
                .setTitle("保存成功")
                .setMessage("分析結果已保存到群組「${group.name}」\n是否要查看歷史紀錄(分析)?")
                .setPositiveButton("查看") { _, _ ->
                    val intent = Intent(this, HistoryAnalysisActivity::class.java)
                    startActivity(intent)
                }
                .setNegativeButton("繼續分析", null)
                .show()

        } catch (e: Exception) {
            Toast.makeText(this, "保存失敗: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e("AnalysisActivity", "保存分析結果失敗", e)
        }
    }

    /**
     * 生成完整的分析報告(包含實際面積)
     */
    private fun buildAnalysisReport(
        result: MultiModelHelper.MultiModelResult,
        group: WoundGroup
    ): String {
        return buildString {
            appendLine("=== 多模型傷口分析報告 ===")
            appendLine("分析時間: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("群組: ${group.name}")
            if (group.location.isNotEmpty()) {
                appendLine("位置: ${group.location}")
            }
            appendLine()

            // 校準信息
            if (detectedCircles.isNotEmpty() && calibrationFactor > 0) {
                appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                appendLine("📏 校準信息:")
                appendLine("  ✓ 檢測到綠色標記 (12mm)")
                appendLine("  ✓ 參考物直徑: 1.2 cm (12 mm)")
                appendLine("  ✓ 校準因子: ${"%.4f".format(calibrationFactor)} cm/px")

                val bestCircle = detectedCircles.firstOrNull()
                if (bestCircle != null) {
                    val diameter = bestCircle.radius * 2
                    appendLine("  ✓ 參考圓像素直徑: ${"%.1f".format(diameter)} px")
                    appendLine("  ✓ 圓形度: ${"%.1f".format(bestCircle.circularity * 100)}%")
                }
                appendLine()
            } else {
                appendLine("⚠ 未檢測到綠色標記,無法計算實際面積")
                appendLine()
            }

            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("組織分佈分析:")
            appendLine()

            // 組織詳細信息(包含實際面積)
            var totalRealArea = 0f
            result.individualResults.forEach { (modelId, tissueResult) ->
                val pixelArea = tissueResult.pixelCount
                val realArea = pixelsToRealArea(pixelArea)
                totalRealArea += realArea

                appendLine("• ${tissueResult.modelName}")
                appendLine("  像素數: ${tissueResult.pixelCount}")

                if (calibrationFactor > 0) {
                    appendLine("  實際面積: ${formatArea(realArea)}")
                } else {
                    appendLine("  相對面積: ${"%.2f".format(pixelArea / 100.0)} 單位²")
                }

                appendLine("  比例: ${"%.2f".format(tissueResult.percentage)}%")
                appendLine()
            }

            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("整體統計:")
            appendLine("• 總像素數: ${result.overallStatistics.totalPixels}")

            if (calibrationFactor > 0) {
                appendLine("• 傷口總面積: ${formatArea(totalRealArea)}")

                // 計算每平方厘米的組織分佈
                appendLine()
                appendLine("每 cm² 組織分佈:")
                result.individualResults.forEach { (_, tissueResult) ->
                    val realArea = pixelsToRealArea(tissueResult.pixelCount)
                    if (totalRealArea > 0) {
                        val percentage = (realArea / totalRealArea) * 100
                        appendLine("  ${tissueResult.modelName}: ${"%.2f".format(realArea)} cm² (${"%.1f".format(percentage)}%)")
                    }
                }
            }

            appendLine()
            appendLine("• 健康評分: ${"%.1f".format(result.overallStatistics.healthScore)}/100")
            appendLine("• 處理時間: ${result.processingTime} ms")
            appendLine()

            // 健康評估
            val healthScore = result.overallStatistics.healthScore
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine("評估結果:")
            when {
                healthScore >= 80 -> {
                    appendLine("✓ 傷口癒合狀況良好")
                    appendLine("建議: 繼續保持當前護理方式")
                }
                healthScore >= 60 -> {
                    appendLine("○ 傷口癒合進展正常")
                    appendLine("建議: 密切觀察並保持清潔")
                }
                healthScore >= 40 -> {
                    appendLine("△ 傷口需要加強護理")
                    appendLine("建議: 建議諮詢醫療專業人員")
                }
                else -> {
                    appendLine("⚠ 傷口狀況需要關注")
                    appendLine("建議: 請盡快諮詢醫療專業人員")
                }
            }

            if (calibrationFactor > 0) {
                appendLine()
                appendLine("備註: 面積計算基於綠色標記校準 (12mm標準貼紙)")
            } else {
                appendLine()
                appendLine("備註: 未進行校準,顯示相對面積值")
            }
        }
    }

    private fun updateGroupImageCount(groupId: String) {
        val infoFile = File(getExternalFilesDir(null), "WoundGroups/$groupId/info.txt")
        if (infoFile.exists()) {
            try {
                val lines = infoFile.readLines()
                val properties = lines.associate { line ->
                    val parts = line.split("=", limit = 2)
                    parts[0] to parts.getOrElse(1) { "" }
                }

                val updatedProperties = properties.toMutableMap()
                updatedProperties["updated"] = System.currentTimeMillis().toString()
                val currentCount = properties["count"]?.toIntOrNull() ?: 0
                updatedProperties["count"] = (currentCount + 1).toString()

                infoFile.writeText(
                    updatedProperties.entries.joinToString("\n") { "${it.key}=${it.value}" }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadWoundGroups(): List<WoundGroup> {
        val groups = mutableListOf<WoundGroup>()
        val rootDir = File(getExternalFilesDir(null), "WoundGroups")

        if (!rootDir.exists()) {
            rootDir.mkdirs()
            return groups
        }

        val groupDirs = rootDir.listFiles { file -> file.isDirectory }
        if (groupDirs != null) {
            for (dir in groupDirs) {
                val infoFile = File(dir, "info.txt")
                if (infoFile.exists()) {
                    try {
                        val lines = infoFile.readLines()
                        val properties = lines.associate { line ->
                            val parts = line.split("=", limit = 2)
                            parts[0] to parts.getOrElse(1) { "" }
                        }

                        val woundGroup = WoundGroup(
                            id = dir.name,
                            name = properties["name"] ?: "未命名群組",
                            location = properties["location"] ?: "",
                            creationDate = Date(properties["created"]?.toLongOrNull() ?: System.currentTimeMillis()),
                            lastUpdated = Date(properties["updated"]?.toLongOrNull() ?: System.currentTimeMillis()),
                            imageCount = properties["count"]?.toIntOrNull() ?: 0
                        )

                        groups.add(woundGroup)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        return groups.sortedByDescending { it.lastUpdated }
    }

    // ==================== 初始化與分析 ====================
//功能：
//  - 創建 MultiModelHelper 實例
//  - 調用 multiModelHelper.initializeModels()
//  - 等待初始化完成
//  - 如果失敗，顯示 Toast 提示
//
//  輸入： 無
//  輸出： 初始化完成的 MultiModelHelper
//
//  內部調用： MultiModelHelper.initializeModels()
    private fun initializeGTModel() {
        lifecycleScope.launch {
            try {
                multiModelHelper = MultiModelHelper(this@AnalysisActivity)
                val initialized = multiModelHelper?.initializeModels() ?: false

                if (initialized) {
                    Log.d("AnalysisActivity", "多模型系統初始化成功")
                } else {
                    Log.e("AnalysisActivity", "多模型系統初始化失敗")
                    runOnUiThread {
                        Toast.makeText(this@AnalysisActivity, "多模型系統初始化失敗", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("AnalysisActivity", "多模型系統初始化異常", e)
            }
        }
    }
//功能：
//  - 根據 URI 的 scheme 打開對應的 InputStream
//    - content:// → contentResolver.openInputStream()
//    - file:// → FileInputStream()
//  - 使用 BitmapFactory.decodeStream() 解碼為 Bitmap
//  - 保存到 currentBitmap 變數
//  - 關閉 InputStream
//
//  輸入： Uri (圖片路徑)
//  輸出： Bitmap (存到 currentBitmap)
    private fun loadImageBitmap(imageUri: Uri) {
        try {
            val inputStream = when {
                imageUri.scheme == "content" -> contentResolver.openInputStream(imageUri)
                imageUri.scheme == "file" -> FileInputStream(imageUri.path)
                else -> null
            }

            currentBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
        } catch (e: Exception) {
            Log.e("AnalysisActivity", "載入圖像失敗", e)
        }
    }

    private fun analyzeImage(imageUri: Uri) {
        val imagePathString = imageUri.toString()
        Log.d("AnalysisActivity", "開始分析圖像: $imagePathString")

        showProgress()
        resultTextView.setText("正在初始化分析...")

        lifecycleScope.launch {
            try {
                var attempts = 0
                while (multiModelHelper?.isModelsReady() != true && attempts < 50) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }

                if (multiModelHelper?.isModelsReady() != true) {
                    runOnUiThread {
                        hideProgress()
                        resultTextView.setText("多模型系統初始化超時,請重試")
                    }
                    return@launch
                }

                val bitmap = currentBitmap
                if (bitmap == null) {
                    runOnUiThread {
                        hideProgress()
                        resultTextView.setText("圖像載入失敗")
                    }
                    return@launch
                }

                // 檢測綠色圓形標記
                runOnUiThread {
                    progressText.text = "正在檢測綠色標記 (12mm)..."
                }

                detectedCircles = detectGreenCircles(bitmap)

                // 計算校準因子
                if (detectedCircles.isNotEmpty()) {
                    calibrationFactor = calculateCalibrationFactor(detectedCircles)

                    Log.d("AnalysisActivity", "檢測到綠色圓形標記 (12mm)")
                    Log.d("AnalysisActivity", "校準因子: $calibrationFactor cm/px")

                    runOnUiThread {
                        val message = if (calibrationFactor > 0) {
                            "✓ 檢測到綠色標記 (12mm 已校準)"
                        } else {
                            "⚠ 檢測到標記但校準失敗"
                        }
                        Toast.makeText(this@AnalysisActivity, message, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@AnalysisActivity,
                            "⚠ 未檢測到綠色標記 (12mm),將顯示相對面積",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                runOnUiThread {
                    progressText.text = "準備執行多模型推論..."
                }

                val startTime = System.currentTimeMillis()

                val multiResult = multiModelHelper?.runMultiModelInferenceWithProgress(
                    bitmap,
                    onProgress = { current, total, modelName ->
                        updateProgress(current, total, modelName)
                    }
                )

                val processingTime = System.currentTimeMillis() - startTime

                runOnUiThread {
                    hideProgress()

                    if (multiResult != null) {
                        Log.d("AnalysisActivity", "收到分析結果,準備顯示")
                        analysisResult = multiResult

                        overlayBitmap = if (detectedCircles.isNotEmpty()) {
                            drawCircleMarkers(multiResult.combinedBitmap, detectedCircles)
                        } else {
                            multiResult.combinedBitmap
                        }

                        grayscaleBitmap = multiResult.grayscaleBitmap

                        showMultiModelResult(bitmap, multiResult, processingTime)
                        setupModelSelector(multiResult)

                        toggleButton.isEnabled = true
                        saveToGroupButton.isEnabled = true

                        Log.d("AnalysisActivity", "結果顯示完成")
                    } else {
                        resultTextView.setText("多模型推論失敗")
                        Log.e("AnalysisActivity", "分析結果為 null")
                    }
                }

            } catch (e: Exception) {
                Log.e("AnalysisActivity", "分析過程發生錯誤", e)
                e.printStackTrace()
                runOnUiThread {
                    hideProgress()
                    resultTextView.setText("分析失敗: ${e.message}")
                }
            }
        }
    }

    private fun showMultiModelResult(
        originalBitmap: Bitmap,
        multiResult: MultiModelHelper.MultiModelResult,
        processingTime: Long
    ) {
        val displayBitmap = if (detectedCircles.isNotEmpty()) {
            drawCircleMarkers(multiResult.combinedBitmap, detectedCircles)
        } else {
            multiResult.combinedBitmap
        }

        imageView.setImageBitmap(displayBitmap)

        val debugText = buildString {
            appendLine("=== 多模型傷口分析結果 ===")
            appendLine()

            // 校準信息
            if (detectedCircles.isNotEmpty()) {
                if (calibrationFactor > 0) {
                    appendLine("✓ 已校準 - 顯示實際面積 (12mm)")
                    appendLine("  參考直徑: 1.2 cm (12 mm)")
                    appendLine("  校準因子: ${"%.4f".format(calibrationFactor)} cm/px")
                } else {
                    appendLine("⚠ 檢測到標記但校準失敗")
                }

                val circle = detectedCircles.firstOrNull()
                if (circle != null) {
                    val diameter = circle.radius * 2
                    val realDiameter = diameter * calibrationFactor
                    appendLine("  圓形度: ${"%.1f".format(circle.circularity * 100)}%")
                    if (calibrationFactor > 0) {
                        appendLine("  實際直徑: ${"%.2f".format(realDiameter)} cm")
                    }
                }
                appendLine()
            } else {
                appendLine("⚠ 未檢測到綠色標記 (12mm)")
                appendLine("  將顯示相對面積值")
                appendLine()
            }

            appendLine("組織分佈分析:")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            var totalRealArea = 0f
            multiResult.individualResults.forEach { (modelId, result) ->
                val pixelArea = result.pixelCount
                val realArea = pixelsToRealArea(pixelArea)
                totalRealArea += realArea

                appendLine()
                appendLine("• ${result.modelName}")
                appendLine("  像素數: ${result.pixelCount}")

                if (calibrationFactor > 0) {
                    appendLine("  實際面積: ${formatArea(realArea)}")
                } else {
                    appendLine("  相對面積: ${"%.2f".format(pixelArea / 100.0)} 單位²")
                }

                appendLine("  比例: ${"%.2f".format(result.percentage)}%")
            }

            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()
            appendLine("整體統計:")
            appendLine("• 總像素數: ${multiResult.overallStatistics.totalPixels}")

            if (calibrationFactor > 0) {
                appendLine("• 傷口總面積: ${formatArea(totalRealArea)}")
            } else {
                appendLine("• 相對總面積: ${"%.2f".format(multiResult.overallStatistics.totalPixels / 100.0)} 單位²")
            }

            appendLine("• 健康評分: ${"%.1f".format(multiResult.overallStatistics.healthScore)}/100")
            appendLine()
            appendLine("處理時間: ${processingTime} ms")
            appendLine()

            val healthScore = multiResult.overallStatistics.healthScore
            when {
                healthScore >= 80 -> {
                    appendLine("評估: 傷口癒合狀況良好 ✓")
                    appendLine("建議: 繼續保持當前護理方式")
                }
                healthScore >= 60 -> {
                    appendLine("評估: 傷口癒合進展正常")
                    appendLine("建議: 密切觀察並保持清潔")
                }
                healthScore >= 40 -> {
                    appendLine("評估: 傷口需要加強護理")
                    appendLine("建議: 建議諮詢醫療專業人員")
                }
                else -> {
                    appendLine("評估: 傷口狀況需要關注 ⚠")
                    appendLine("建議: 請盡快諮詢醫療專業人員")
                }
            }

            appendLine()
            appendLine("可以使用上方選單切換查看不同組織")
            appendLine("可以選擇將此分析結果保存到群組中")
        }

        resultTextView.setText(debugText)

        val message = "多模型分析完成 - 健康評分: ${"%.1f".format(multiResult.overallStatistics.healthScore)}/100"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()

        multiModelHelper?.close()
        currentBitmap?.recycle()
        overlayBitmap?.recycle()
        grayscaleBitmap?.recycle()

        singleModelBitmaps.values.forEach { it.recycle() }
        singleModelBitmaps.clear()

        Log.d("AnalysisActivity", "所有資源已釋放")
    }

    // ==================== 數據類 ====================

    data class WoundGroup(
        val id: String,
        val name: String,
        val location: String,
        val creationDate: Date,
        val lastUpdated: Date,
        val imageCount: Int
    )

    data class CircleInfo(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val circularity: Float,
        val pixelCount: Int
    )

    data class Region(
        val pixels: List<Point>,
        val bounds: Rect
    )
}