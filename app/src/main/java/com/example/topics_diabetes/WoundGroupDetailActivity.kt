package com.example.topics_diabetes

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.Description
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class WoundGroupDetailActivity : AppCompatActivity() {

    private lateinit var groupNameTextView: TextView
    private lateinit var locationTextView: TextView
    private lateinit var creationDateTextView: TextView
    private lateinit var backButton: Button
    private lateinit var deleteButton: Button
    private lateinit var chartButton: Button
    private lateinit var compareButton: Button
    private lateinit var analysisRecyclerView: RecyclerView
    private lateinit var emptyView: TextView

    private lateinit var groupId: String
    private lateinit var groupName: String
    private var analysisRecords = mutableListOf<AnalysisRecord>()
    private lateinit var analysisAdapter: AnalysisRecordAdapter
    private var isDeleteMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wound_group_detail)

        groupId = intent.getStringExtra(HistoryAnalysisActivity.EXTRA_GROUP_ID) ?: ""
        groupName = intent.getStringExtra(HistoryAnalysisActivity.EXTRA_GROUP_NAME) ?: "未命名群組"

        if (groupId.isEmpty()) {
            Toast.makeText(this, "無效的群組 ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        initializeViews()
        setupRecyclerView()
        setupButtonListeners()
        loadGroupDetails()
        loadAnalysisRecords()
    }

    override fun onResume() {
        super.onResume()
        loadAnalysisRecords()
        if (isDeleteMode) {
            exitDeleteMode()
        }
    }

    private fun initializeViews() {
        groupNameTextView = findViewById(R.id.group_name_text)
        locationTextView = findViewById(R.id.location_text)
        creationDateTextView = findViewById(R.id.creation_date_text)
        backButton = findViewById(R.id.back_button)
        deleteButton = findViewById(R.id.delete_button)
        chartButton = findViewById(R.id.chart_button)
        compareButton = findViewById(R.id.compare_button)
        analysisRecyclerView = findViewById(R.id.analysis_recycler_view)
        emptyView = findViewById(R.id.empty_view)

        supportActionBar?.title = "分析記錄 - $groupName"
        groupNameTextView.text = groupName
    }

    private fun setupRecyclerView() {
        analysisRecyclerView.layoutManager = LinearLayoutManager(this)
        analysisAdapter = AnalysisRecordAdapter()
        analysisRecyclerView.adapter = analysisAdapter
    }

    private fun setupButtonListeners() {
        backButton.setOnClickListener { finish() }
        deleteButton.setOnClickListener { toggleDeleteMode() }
        chartButton.setOnClickListener { showWoundSizeChart() }
        compareButton.setOnClickListener { showComparisonDialog() }
    }

    /**
     * 顯示比較對話框
     */
    private fun showComparisonDialog() {
        if (analysisRecords.size < 2) {
            Toast.makeText(this, "至少需要2筆記錄才能進行比較", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_comparison, null)
        val firstSpinner = dialogView.findViewById<Spinner>(R.id.first_record_spinner)
        val secondSpinner = dialogView.findViewById<Spinner>(R.id.second_record_spinner)

        // 準備記錄列表
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val recordNames = analysisRecords.map {
            dateFormat.format(it.date)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, recordNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        firstSpinner.adapter = adapter
        secondSpinner.adapter = adapter

        // 預設選擇最舊和最新的記錄
        if (analysisRecords.size >= 2) {
            firstSpinner.setSelection(analysisRecords.size - 1)  // 最舊的
            secondSpinner.setSelection(0)  // 最新的
        }

        AlertDialog.Builder(this)
            .setTitle("選擇比較記錄")
            .setView(dialogView)
            .setPositiveButton("比較") { _, _ ->
                val firstIndex = firstSpinner.selectedItemPosition
                val secondIndex = secondSpinner.selectedItemPosition

                if (firstIndex == secondIndex) {
                    Toast.makeText(this, "請選擇不同的記錄進行比較", Toast.LENGTH_SHORT).show()
                } else {
                    showComparisonResult(
                        analysisRecords[firstIndex],
                        analysisRecords[secondIndex]
                    )
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 顯示比較結果
     */
    private fun showComparisonResult(first: AnalysisRecord, second: AnalysisRecord) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_comparison_result, null)

        val firstDateText = dialogView.findViewById<TextView>(R.id.first_date_text)
        val secondDateText = dialogView.findViewById<TextView>(R.id.second_date_text)
        val firstImage = dialogView.findViewById<ImageView>(R.id.first_image)
        val secondImage = dialogView.findViewById<ImageView>(R.id.second_image)
        val comparisonDataText = dialogView.findViewById<TextView>(R.id.comparison_data_text)

        // 設置日期
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        firstDateText.text = dateFormat.format(first.date)
        secondDateText.text = dateFormat.format(second.date)

        // 載入圖片
        if (first.overlayImageFile?.exists() == true) {
            val bitmap = BitmapFactory.decodeFile(first.overlayImageFile.absolutePath)
            firstImage.setImageBitmap(bitmap)
        }

        if (second.overlayImageFile?.exists() == true) {
            val bitmap = BitmapFactory.decodeFile(second.overlayImageFile.absolutePath)
            secondImage.setImageBitmap(bitmap)
        }

        // 解析報告數據
        val firstData = parseReportData(first.reportContent)
        val secondData = parseReportData(second.reportContent)

        // 生成比較文字
        val comparisonText = buildComparisonText(firstData, secondData, first.date, second.date)
        comparisonDataText.text = comparisonText

        AlertDialog.Builder(this)
            .setTitle("分析比較結果")
            .setView(dialogView)
            .setPositiveButton("關閉", null)
            .setNeutralButton("匯出報告") { _, _ ->
                Toast.makeText(this, "匯出功能開發中", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 解析報告數據 (已移除腐肉支持)
     */
    private fun parseReportData(reportContent: String): Map<String, ReportData> {
        val data = mutableMapOf<String, ReportData>()
        val lines = reportContent.split("\n")

        var currentTissue: String? = null
        var pixelCount = 0
        var percentage = 0f

        for (line in lines) {
            when {
                line.contains("• GT組織") -> currentTissue = "GT組織"
                line.contains("• 上皮化組織") -> currentTissue = "上皮化組織"
                line.contains("• 肉芽組織") -> currentTissue = "肉芽組織"
                // 移除腐肉檢測

                line.contains("像素數:") && currentTissue != null -> {
                    val regex = "\\d+".toRegex()
                    val match = regex.find(line)
                    pixelCount = match?.value?.toIntOrNull() ?: 0
                }

                line.contains("比例:") && currentTissue != null -> {
                    val regex = "\\d+\\.\\d+".toRegex()
                    val match = regex.find(line)
                    percentage = match?.value?.toFloatOrNull() ?: 0f

                    data[currentTissue] = ReportData(pixelCount, percentage)
                    currentTissue = null
                }
            }
        }

        return data
    }

    /**
     * 生成比較文字
     */
    private fun buildComparisonText(
        firstData: Map<String, ReportData>,
        secondData: Map<String, ReportData>,
        firstDate: Date,
        secondDate: Date
    ): String {
        val daysDiff = ((secondDate.time - firstDate.time) / (1000 * 60 * 60 * 24)).toInt()

        return buildString {
            appendLine("=== 傷口變化分析 ===")
            appendLine()
            appendLine("時間間隔: $daysDiff 天")
            appendLine()
            appendLine("組織變化:")
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

            val allTissues = (firstData.keys + secondData.keys).distinct()

            for (tissue in allTissues) {
                val first = firstData[tissue]
                val second = secondData[tissue]

                appendLine()
                appendLine("$tissue:")

                if (first != null && second != null) {
                    val pixelDiff = second.pixelCount - first.pixelCount
                    val percentDiff = second.percentage - first.percentage

                    appendLine("  基準: ${first.pixelCount} 像素 - ${"%.2f".format(first.percentage)}%")
                    appendLine("  當前: ${second.pixelCount} 像素 - ${"%.2f".format(second.percentage)}%")

                    val arrow = when {
                        pixelDiff > 0 -> "↑"
                        pixelDiff < 0 -> "↓"
                        else -> "→"
                    }

                    appendLine("  變化: $arrow ${abs(pixelDiff)} 像素")
                    appendLine("        ${if (percentDiff >= 0) "+" else ""}${"%.2f".format(percentDiff)}%")

                    // 評估變化
                    val evaluation = evaluateTissueChange(tissue, percentDiff)
                    appendLine("  評估: $evaluation")

                } else if (first != null) {
                    appendLine("  狀態: 已消失 ✓")
                } else if (second != null) {
                    appendLine("  狀態: 新出現")
                }
            }

            appendLine()
            appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLine()

            // 整體評估
            val overallEvaluation = evaluateOverallChange(firstData, secondData)
            appendLine("整體評估: $overallEvaluation")
        }
    }

    /**
     * 評估組織變化 (已移除腐肉評估)
     */
    private fun evaluateTissueChange(tissueName: String, percentDiff: Float): String {
        return when (tissueName) {
            "上皮化組織" -> {
                when {
                    percentDiff > 5f -> "大幅增加 - 癒合良好 ✓✓"
                    percentDiff > 2f -> "適度增加 - 進展正常 ✓"
                    percentDiff > -2f -> "維持穩定"
                    else -> "減少 - 需要關注 ⚠"
                }
            }
            "肉芽組織" -> {
                when {
                    percentDiff > 5f -> "大幅增加 - 新生組織發育良好 ✓"
                    percentDiff > 2f -> "適度增加 - 進展正常 ✓"
                    percentDiff > -5f -> "維持穩定"
                    else -> "減少 - 可能需要促進生長"
                }
            }
            "GT組織" -> {
                when {
                    abs(percentDiff) < 5f -> "維持穩定"
                    percentDiff > 0 -> "增加"
                    else -> "減少"
                }
            }
            else -> "數據變化"
        }
    }

    /**
     * 整體評估 (基於上皮化、肉芽和GT組織,不再使用腐肉)
     */
    private fun evaluateOverallChange(
        firstData: Map<String, ReportData>,
        secondData: Map<String, ReportData>
    ): String {
        val epithelizationChange = (secondData["上皮化組織"]?.percentage ?: 0f) -
                (firstData["上皮化組織"]?.percentage ?: 0f)
        val granulationChange = (secondData["肉芽組織"]?.percentage ?: 0f) -
                (firstData["肉芽組織"]?.percentage ?: 0f)
        val gtChange = (secondData["GT組織"]?.percentage ?: 0f) -
                (firstData["GT組織"]?.percentage ?: 0f)

        return when {
            // 上皮化和肉芽都大幅增加
            epithelizationChange > 5f && granulationChange > 3f ->
                "傷口癒合進展極佳 ✓✓ - 上皮化和肉芽組織均增加,建議繼續當前護理方式"

            // 上皮化顯著增加
            epithelizationChange > 3f ->
                "傷口癒合進展良好 ✓ - 上皮化組織顯著增加,持續改善中"

            // 任一組織正向變化
            epithelizationChange > 0f || granulationChange > 0f ->
                "傷口呈現正向變化 - 繼續觀察"

            // 上皮化明顯減少
            epithelizationChange < -5f ->
                "需要關注 ⚠ - 上皮化組織明顯減少,建議加強護理"

            // 肉芽明顯減少
            granulationChange < -5f ->
                "需要注意 ⚠ - 肉芽組織減少,建議諮詢醫療專業人員"

            // GT組織大幅增加(可能不好)
            gtChange > 10f ->
                "需要評估 - GT組織增加明顯,建議專業評估"

            // 基本穩定
            abs(epithelizationChange) < 2f && abs(granulationChange) < 2f ->
                "傷口狀況相對穩定 - 建議持續監測"

            // 其他情況
            else ->
                "傷口變化需要持續觀察 - 建議定期追蹤"
        }
    }

    /**
     * 從分析報告中提取傷口總面積 (cm²)
     */
    private fun extractWoundAreaFromReport(reportContent: String): Float? {
        return try {
            Log.d("WoundChart", ">>> 開始提取傷口面積")
            val lines = reportContent.split("\n")

            Log.d("WoundChart", "報告總行數: ${lines.size}")

            // 方法1: 直接提取 "傷口總面積"
            val totalAreaLine = lines.find {
                it.contains("傷口總面積") || it.contains("傷口總面積:")
            }

            if (totalAreaLine != null) {
                Log.d("WoundChart", "找到傷口總面積行: $totalAreaLine")

                // 提取面積值 (xx.xx cm²)
                val areaRegex = "(\\d+\\.\\d+)\\s*cm".toRegex()
                val areaMatch = areaRegex.find(totalAreaLine)
                val areaCm2 = areaMatch?.groupValues?.get(1)?.toFloatOrNull()

                if (areaCm2 != null) {
                    Log.d("WoundChart", "✓ 方法1成功: ${areaCm2} cm²")
                    return areaCm2
                }
            } else {
                Log.w("WoundChart", "未找到 '傷口總面積' 行")
            }

            // 方法2: 加總所有組織的實際面積
            Log.d("WoundChart", "嘗試方法2: 加總組織實際面積")
            var totalArea = 0f
            var foundAnyTissue = false

            var i = 0
            while (i < lines.size) {
                val line = lines[i]

                if (line.contains("• GT組織") ||
                    line.contains("• 上皮化組織") ||
                    line.contains("• 肉芽組織")) {

                    Log.d("WoundChart", "找到組織: $line")

                    // 往下找 "實際面積" 行 (最多找5行)
                    for (j in 1..5) {
                        if (i + j < lines.size) {
                            val nextLine = lines[i + j]
                            if (nextLine.contains("實際面積")) {
                                Log.d("WoundChart", "找到實際面積行: $nextLine")

                                // 提取 cm² 值
                                val areaRegex = "(\\d+\\.\\d+)\\s*cm²".toRegex()
                                val areaMatch = areaRegex.find(nextLine)
                                val area = areaMatch?.groupValues?.get(1)?.toFloatOrNull()

                                if (area != null) {
                                    totalArea += area
                                    foundAnyTissue = true
                                    Log.d("WoundChart", "  加入 ${area} cm², 累計: ${totalArea} cm²")
                                } else {
                                    // 也檢查 mm² (需要轉換)
                                    val mmAreaRegex = "(\\d+\\.\\d+)\\s*mm²".toRegex()
                                    val mmMatch = mmAreaRegex.find(nextLine)
                                    val mmArea = mmMatch?.groupValues?.get(1)?.toFloatOrNull()

                                    if (mmArea != null) {
                                        val cmArea = mmArea / 100f  // mm² 轉 cm²
                                        totalArea += cmArea
                                        foundAnyTissue = true
                                        Log.d("WoundChart", "  加入 ${mmArea} mm² (${cmArea} cm²), 累計: ${totalArea} cm²")
                                    }
                                }

                                break
                            }
                        }
                    }
                }
                i++
            }

            if (foundAnyTissue && totalArea > 0) {
                Log.d("WoundChart", "✓ 方法2成功(加總): ${totalArea} cm²")
                return totalArea
            }

            Log.e("WoundChart", "✗ 所有方法都失敗,無法提取面積")
            null

        } catch (e: Exception) {
            Log.e("WoundChart", "提取過程發生錯誤", e)
            null
        }
    }

    /**
     * 顯示傷口大小趨勢圖
     */
    private fun showWoundSizeChart() {
        Log.d("WoundChart", "=== 開始生成趨勢圖 ===")

        if (analysisRecords.isEmpty()) {
            Log.e("WoundChart", "沒有分析記錄")
            Toast.makeText(this, "沒有分析數據可顯示", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("WoundChart", "總記錄數: ${analysisRecords.size}")

        // 準備圖表數據
        val chartEntries = mutableListOf<Entry>()
        val dateLabels = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("MM/dd", Locale.getDefault())

        val sortedRecords = analysisRecords.sortedBy { it.date }

        sortedRecords.forEachIndexed { index, record ->
            Log.d("WoundChart", "--- 處理記錄 $index ---")
            Log.d("WoundChart", "時間戳: ${record.timestamp}")
            Log.d("WoundChart", "報告文件: ${record.reportFile.name}")

            // 打印報告內容的前500字符
            val reportPreview = record.reportContent.take(500)
            Log.d("WoundChart", "報告內容預覽:\n$reportPreview")

            // 改為提取面積而非像素
            val woundArea = extractWoundAreaFromReport(record.reportContent)

            if (woundArea != null) {
                chartEntries.add(Entry(index.toFloat(), woundArea))
                dateLabels.add(dateFormat.format(record.date))
                Log.d("WoundChart", "✓ 成功提取: 面積=${woundArea}cm²")
            } else {
                Log.e("WoundChart", "✗ 無法提取面積數據")
            }
        }

        Log.d("WoundChart", "=== 提取結果 ===")
        Log.d("WoundChart", "成功提取: ${chartEntries.size}/${sortedRecords.size} 條記錄")

        if (chartEntries.isEmpty()) {
            Log.e("WoundChart", "所有記錄都無法提取數據!")
            Toast.makeText(this, "無法從分析報告中提取傷口大小數據\n請確保分析報告包含面積資訊", Toast.LENGTH_LONG).show()
            return
        }

        // 創建圖表對話框
        Log.d("WoundChart", "準備顯示圖表")
        showChartDialog(chartEntries, dateLabels)
    }

    /**
     * 顯示圖表對話框
     */
    private fun showChartDialog(entries: List<Entry>, dateLabels: List<String>) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_wound_size_chart, null)
        val lineChart = dialogView.findViewById<LineChart>(R.id.line_chart)

        setupLineChart(lineChart, entries, dateLabels)

        AlertDialog.Builder(this)
            .setTitle("傷口大小趨勢 - $groupName")
            .setView(dialogView)
            .setPositiveButton("關閉", null)
            .setNeutralButton("拍照分析") { _, _ ->
                val intent = Intent(this, CameraActivity::class.java).apply {
                    putExtra("EXTRA_GROUP_ID", groupId)
                    putExtra("EXTRA_GROUP_NAME", groupName)
                }
                startActivity(intent)
            }
            .show()
    }

    /**
     * 設置折線圖
     */
    private fun setupLineChart(lineChart: LineChart, entries: List<Entry>, dateLabels: List<String>) {
        // 創建數據集
        val dataSet = LineDataSet(entries, "傷口面積 (cm²)").apply {
            color = Color.rgb(255, 87, 34) // 橘紅色線條
            setCircleColor(Color.rgb(255, 87, 34))
            lineWidth = 3f
            circleRadius = 6f
            setDrawCircleHole(false)
            valueTextSize = 12f
            setDrawFilled(true)
            fillColor = Color.rgb(255, 87, 34)
            fillAlpha = 30

            // 設置數值顯示格式
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return String.format("%.2f", value)
                }
            }
        }

        val lineData = LineData(dataSet)
        lineChart.data = lineData

        // 設置 X 軸
        lineChart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            granularity = 1f
            labelCount = dateLabels.size
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val index = value.toInt()
                    return if (index >= 0 && index < dateLabels.size) {
                        dateLabels[index]
                    } else {
                        ""
                    }
                }
            }
        }

        // 設置 Y 軸
        lineChart.axisLeft.apply {
            axisMinimum = 0f
            setDrawGridLines(true)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return String.format("%.1f", value)
                }
            }
        }

        lineChart.axisRight.isEnabled = false

        // 設置圖表描述
        val description = Description()
        description.text = "時間軸顯示傷口面積變化趨勢 (基於綠色標記校準)"
        description.textSize = 10f
        lineChart.description = description

        // 設置圖例
        lineChart.legend.apply {
            isEnabled = true
            textSize = 12f
        }

        // 啟用觸控和縮放
        lineChart.setTouchEnabled(true)
        lineChart.setDragEnabled(true)
        lineChart.setScaleEnabled(true)
        lineChart.setPinchZoom(true)

        // 設置動畫
        lineChart.animateX(1000)

        // 刷新圖表
        lineChart.invalidate()
    }

    private fun loadGroupDetails() {
        val infoFile = File(getExternalFilesDir(null), "WoundGroups/$groupId/info.txt")
        if (infoFile.exists()) {
            try {
                val lines = infoFile.readLines()
                val properties = lines.associate { line ->
                    val parts = line.split("=", limit = 2)
                    parts[0] to parts.getOrElse(1) { "" }
                }

                val location = properties["location"] ?: ""
                locationTextView.text = if (location.isNotEmpty()) {
                    "位置: $location"
                } else {
                    "位置: 未指定"
                }

                val creationTime = properties["created"]?.toLongOrNull() ?: System.currentTimeMillis()
                val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                creationDateTextView.text = "創建於: ${dateFormat.format(Date(creationTime))}"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun loadAnalysisRecords() {
        val groupDir = File(getExternalFilesDir(null), "WoundGroups/$groupId")
        if (!groupDir.exists()) {
            groupDir.mkdirs()
        }

        analysisRecords.clear()

        val reportFiles = groupDir.listFiles { file ->
            file.isFile && file.name.endsWith("_report.txt")
        }

        if (reportFiles != null && reportFiles.isNotEmpty()) {
            for (reportFile in reportFiles) {
                try {
                    val timestamp = reportFile.name.replace("_report.txt", "")
                    val originalImageFile = File(groupDir, "${timestamp}_original.jpg")
                    val overlayImageFile = File(groupDir, "${timestamp}_overlay.jpg")
                    val grayscaleImageFile = File(groupDir, "${timestamp}_grayscale.jpg")

                    val reportContent = reportFile.readText()

                    val analysisRecord = AnalysisRecord(
                        timestamp = timestamp,
                        date = Date(reportFile.lastModified()),
                        reportFile = reportFile,
                        originalImageFile = if (originalImageFile.exists()) originalImageFile else null,
                        overlayImageFile = if (overlayImageFile.exists()) overlayImageFile else null,
                        grayscaleImageFile = if (grayscaleImageFile.exists()) grayscaleImageFile else null,
                        reportContent = reportContent
                    )

                    analysisRecords.add(analysisRecord)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            analysisRecords.sortByDescending { it.date }

            analysisRecyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            analysisAdapter.notifyDataSetChanged()

            // 根據記錄數量決定是否顯示圖表和比較按鈕
            chartButton.visibility = if (analysisRecords.size >= 2) View.VISIBLE else View.GONE
            compareButton.visibility = if (analysisRecords.size >= 2) View.VISIBLE else View.GONE

        } else {
            analysisRecyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            chartButton.visibility = View.GONE
            compareButton.visibility = View.GONE
        }
    }

    private fun toggleDeleteMode() {
        isDeleteMode = !isDeleteMode

        if (isDeleteMode) {
            deleteButton.text = "完成刪除"
            deleteButton.setBackgroundResource(R.drawable.button_delete)
            Toast.makeText(this, "點擊分析記錄進行刪除", Toast.LENGTH_SHORT).show()
        } else {
            exitDeleteMode()
        }

        analysisAdapter.notifyDataSetChanged()
    }

    private fun exitDeleteMode() {
        isDeleteMode = false
        deleteButton.text = "刪除記錄"
        deleteButton.setBackgroundResource(R.drawable.button_normal)
    }

    private fun deleteAnalysisRecord(record: AnalysisRecord, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("刪除確認")
            .setMessage("確定要刪除這筆分析記錄嗎？")
            .setPositiveButton("刪除") { _, _ ->
                try {
                    record.reportFile.delete()
                    record.originalImageFile?.delete()
                    record.overlayImageFile?.delete()
                    record.grayscaleImageFile?.delete()

                    analysisRecords.removeAt(position)
                    analysisAdapter.notifyItemRemoved(position)

                    updateGroupAnalysisCount()

                    if (analysisRecords.isEmpty()) {
                        analysisRecyclerView.visibility = View.GONE
                        emptyView.visibility = View.VISIBLE
                        chartButton.visibility = View.GONE
                        compareButton.visibility = View.GONE
                        exitDeleteMode()
                    } else if (analysisRecords.size < 2) {
                        chartButton.visibility = View.GONE
                        compareButton.visibility = View.GONE
                    }

                    Toast.makeText(this, "分析記錄已刪除", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "刪除失敗: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateGroupAnalysisCount() {
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
                updatedProperties["count"] = analysisRecords.size.toString()

                infoFile.writeText(
                    updatedProperties.entries.joinToString("\n") { "${it.key}=${it.value}" }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun viewAnalysisDetail(record: AnalysisRecord) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_analysis_detail, null)
        val imageView = dialogView.findViewById<ImageView>(R.id.analysis_image)
        val reportTextView = dialogView.findViewById<TextView>(R.id.report_text)

        if (record.overlayImageFile?.exists() == true) {
            Glide.with(this)
                .load(record.overlayImageFile)
                .into(imageView)
        } else if (record.originalImageFile?.exists() == true) {
            Glide.with(this)
                .load(record.originalImageFile)
                .into(imageView)
        }

        reportTextView.text = record.reportContent

        AlertDialog.Builder(this)
            .setTitle("分析詳情")
            .setView(dialogView)
            .setPositiveButton("關閉", null)
            .setNeutralButton("查看原圖") { _, _ ->
                if (record.originalImageFile?.exists() == true) {
                    openImageWithFileProvider(record.originalImageFile)
                }
            }
            .show()
    }

    private fun openImageWithFileProvider(imageFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                imageFile
            )

            val intent = Intent().apply {
                action = Intent.ACTION_VIEW
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(this, "沒有找到可以開啟圖片的應用程式", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("WoundGroupDetail", "Error opening image with FileProvider", e)
            Toast.makeText(this, "無法開啟圖片: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    inner class AnalysisRecordAdapter : RecyclerView.Adapter<AnalysisRecordAdapter.AnalysisViewHolder>() {

        inner class AnalysisViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val previewImageView: ImageView = itemView.findViewById(R.id.preview_image)
            val dateTextView: TextView = itemView.findViewById(R.id.analysis_date_text)
            val summaryTextView: TextView = itemView.findViewById(R.id.analysis_summary_text)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AnalysisViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_analysis_record, parent, false)
            return AnalysisViewHolder(view)
        }

        override fun onBindViewHolder(holder: AnalysisViewHolder, position: Int) {
            val record = analysisRecords[position]

            val imageToShow = record.overlayImageFile ?: record.originalImageFile
            if (imageToShow?.exists() == true) {
                Glide.with(holder.previewImageView)
                    .load(imageToShow)
                    .centerCrop()
                    .into(holder.previewImageView)
            } else {
                holder.previewImageView.setImageResource(R.drawable.image_placeholder)
            }

            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            holder.dateTextView.text = dateFormat.format(record.date)

            val summary = extractSummaryFromReport(record.reportContent)
            holder.summaryTextView.text = summary

            holder.itemView.setOnClickListener {
                if (isDeleteMode) {
                    deleteAnalysisRecord(record, position)
                } else {
                    viewAnalysisDetail(record)
                }
            }

            if (isDeleteMode) {
                holder.previewImageView.setColorFilter(android.graphics.Color.argb(70, 255, 0, 0))
            } else {
                holder.previewImageView.clearColorFilter()
            }
        }

        override fun getItemCount(): Int = analysisRecords.size

        private fun extractSummaryFromReport(reportContent: String): String {
            return try {
                // 直接提取傷口總面積
                val areaLine = reportContent.split("\n").find {
                    it.contains("傷口總面積") || it.contains("傷口總面積:")
                }

                if (areaLine != null) {
                    val areaRegex = "(\\d+\\.\\d+)\\s*cm".toRegex()
                    val match = areaRegex.find(areaLine)
                    val area = match?.groupValues?.get(1)?.toFloatOrNull()

                    if (area != null) {
                        return "傷口面積: ${String.format("%.2f", area)} cm²"
                    }
                }

                "分析完成"
            } catch (e: Exception) {
                "分析記錄"
            }
        }
    }

    data class AnalysisRecord(
        val timestamp: String,
        val date: Date,
        val reportFile: File,
        val originalImageFile: File?,
        val overlayImageFile: File?,
        val grayscaleImageFile: File?,
        val reportContent: String
    )

    data class ReportData(
        val pixelCount: Int,
        val percentage: Float
    )
}