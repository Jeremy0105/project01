package com.example.topics_diabetes

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.YuvImage
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.LinkedList
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraActivity : AppCompatActivity() {
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private lateinit var captureButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var previewImageView: ImageView
    private lateinit var groupInfoTextView: TextView

    // 檢測狀態按鈕
    private var detectionStatusButton: Button? = null
    private var detectionOverlay: ImageView? = null

    private var tempImageUri: Uri? = null
    private var isGreenCircleDetected = false

    // 傷口群組相關
    private var groupId: String? = null
    private var groupName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        // 從 Intent 獲取群組信息
        groupId = intent.getStringExtra("EXTRA_GROUP_ID")
        groupName = intent.getStringExtra("EXTRA_GROUP_NAME")

        initializeViews()
        setupGroupInfo()
        setupBackPressedHandler()

        // 初始化檢測狀態
        updateDetectionStatusUI(false, "正在啟動相機...")

        // 請求相機權限
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

        setupButtonListeners()
        cameraExecutor = Executors.newSingleThreadExecutor()
        createAppDirectory()
    }

    private fun initializeViews() {
        viewFinder = findViewById(R.id.viewFinder)
        captureButton = findViewById(R.id.camera_capture_button)
        analyzeButton = findViewById(R.id.analyze_button)
        previewImageView = findViewById(R.id.preview_image_view)
        groupInfoTextView = findViewById(R.id.group_info_text)

        // 檢測狀態按鈕
        try {
            detectionStatusButton = findViewById(R.id.detection_status_text)
            detectionOverlay = findViewById(R.id.detection_overlay)

            Log.d(TAG, "檢測狀態按鈕初始化成功")
            Log.d(TAG, "detectionStatusButton: ${detectionStatusButton != null}")
        } catch (e: Exception) {
            Log.e(TAG, "找不到檢測相關視圖: ${e.message}")
        }

        // 初始設置
        previewImageView.visibility = View.GONE
        captureButton.isEnabled = false
        analyzeButton.isEnabled = false
    }

    private fun setupGroupInfo() {
        if (groupId != null && groupName != null) {
            groupInfoTextView.text = "拍攝照片將存儲到: $groupName"
            groupInfoTextView.visibility = View.VISIBLE
        } else {
            groupInfoTextView.visibility = View.GONE
        }
    }

    private fun setupBackPressedHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (previewImageView.visibility == View.VISIBLE) {
                    resetCameraView()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupButtonListeners() {
        captureButton.setOnClickListener { takePhoto() }
        analyzeButton.setOnClickListener { analyzePhoto() }
    }

    // 更新檢測狀態UI - 使用按鈕樣式
    private fun updateDetectionStatusUI(detected: Boolean, customMessage: String? = null, confidence: Float = 1f) {
        isGreenCircleDetected = detected

        runOnUiThread {
            detectionStatusButton?.let { statusButton ->
                // 基本訊息
                val baseMessage = customMessage ?: if (detected) {
                    "✓ 已檢測到綠色圓形"
                } else {
                    "✗ 未檢測到綠色圓形"
                }

                // 如果有信心度資訊且檢測成功,加入百分比顯示
                val message = if (detected && confidence < 1f && customMessage == null) {
                    "$baseMessage (${(confidence * 100).toInt()}%)"
                } else {
                    baseMessage
                }

                statusButton.text = message

                // 設置狀態按鈕的樣式（不影響拍照按鈕）
                when {
                    detected && confidence > 0.8f -> {
                        // 高信心度檢測成功 - 綠色按鈕
                        statusButton.isEnabled = true
                        statusButton.setBackgroundResource(R.drawable.detection_status_button_background)
                        statusButton.setTextColor(Color.WHITE)
                    }
                    detected && confidence > 0.5f -> {
                        // 中等信心度檢測成功 - 橙色按鈕
                        statusButton.isEnabled = false
                        statusButton.setBackgroundResource(R.drawable.detection_status_button_background_warning)
                        statusButton.setTextColor(Color.WHITE)
                    }
                    detected -> {
                        // 低信心度但仍檢測到 - 綠色按鈕
                        statusButton.isEnabled = true
                        statusButton.setBackgroundResource(R.drawable.detection_status_button_background)
                        statusButton.setTextColor(Color.WHITE)
                    }
                    customMessage?.contains("⚠") == true || customMessage?.contains("警告") == true -> {
                        // 警告狀態 - 橙色按鈕
                        statusButton.isEnabled = false
                        statusButton.setBackgroundResource(R.drawable.detection_status_button_background_warning)
                        statusButton.setTextColor(Color.WHITE)
                    }
                    customMessage?.contains("📸") == true || customMessage?.contains("📷") == true -> {
                        // 信息狀態 - 藍色按鈕
                        statusButton.isEnabled = false
                        statusButton.setBackgroundResource(R.drawable.detection_status_button_background_info)
                        statusButton.setTextColor(Color.WHITE)
                    }
                    else -> {
                        // 檢測失敗/檢測中狀態 - 紅色按鈕（但不影響拍照按鈕）
                        statusButton.isEnabled = false
                        statusButton.setBackgroundResource(R.drawable.detection_status_button_background)
                        statusButton.setTextColor(Color.WHITE)
                    }
                }

                // 拍照按鈕始終可用（除非在預覽模式）
                captureButton.isEnabled = true
            } ?: run {
                // 如果沒有找到狀態按鈕,則始終啟用拍照按鈕
                captureButton.isEnabled = true
            }
        }
    }

    private fun createAppDirectory() {
        val appDir = File(getExternalFilesDir(null), APP_DIRECTORY)
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
    }
    //一功能：
    //  - 獲取相機的 ImageCapture 物件
    //  - 生成帶時間戳的文件名（格式：yyyy-MM-dd-HH-mm-ss-SSS.jpg）
    //  - 創建臨時文件在緩存目錄 (cacheDir)
    //  - 調用 CameraX 的 takePicture() 拍攝照片
    //  - 成功後調用 showImagePreview()
    //
    //  輸入： 無（點擊事件觸發）
    //  輸出： 臨時圖片文件
   private fun takePhoto() {
        // 獲取穩定的圖像捕獲引用
        val imageCapture = imageCapture ?: return

        // 創建帶時間戳的文件名
        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())

        // 使用內部臨時文件
        val tempFile = File(cacheDir, "$name.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        // 更新狀態
        updateDetectionStatusUI(false, "📸 正在拍攝照片...")

        // 拍照
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    updateDetectionStatusUI(false, "❌ 拍照失敗")
                    Toast.makeText(baseContext, "拍照失敗: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    showImagePreview(tempFile)
                }
            }
        )
    }
         //功能：
         //  - 使用 BitmapFactory.decodeFile() 將圖片加載為 Bitmap
         //  - 在 ImageView 中顯示圖片預覽
         //  - 隱藏相機預覽 (viewFinder)，顯示圖片預覽 (previewImageView)
         //  - 保存臨時 URI 到 tempImageUri
         //  - 彈出 AlertDialog 詢問 "使用" 或 "重拍"
         //  - 禁用拍照按鈕，啟用分析按鈕
         //
         //  輸入： File (臨時圖片文件)
         //  輸出： 顯示預覽，等待用戶操作
    private fun showImagePreview(imageFile: File) {
        // 顯示預覽圖片
        val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
        previewImageView.setImageBitmap(bitmap)

        // 隱藏相機預覽,顯示圖片預覽
        viewFinder.visibility = View.GONE
        previewImageView.visibility = View.VISIBLE
        detectionOverlay?.visibility = View.GONE

        // 更新狀態為預覽模式
        updateDetectionStatusUI(false, "📷 照片預覽中 - 請選擇使用或重拍")

        // 保存臨時URI
        tempImageUri = Uri.fromFile(imageFile)

        // 禁用拍照按鈕,啟用分析按鈕
        captureButton.isEnabled = false
        analyzeButton.isEnabled = true
        analyzeButton.tag = tempImageUri

        // 顯示對話框詢問用戶是否重拍
        AlertDialog.Builder(this)
            .setTitle("照片預覽")
            .setMessage("您要使用這張照片還是重新拍攝?")
            .setPositiveButton("使用") { _, _ ->
                // 保存照片到指定資料夾
                saveImageToAppDirectory(imageFile)
                // 重新啟用所有按鈕
                captureButton.isEnabled = true
                analyzeButton.isEnabled = true
                updateDetectionStatusUI(false, "✅ 照片已保存 - 可進行分析")
            }
            .setNegativeButton("重拍") { _, _ ->
                // 重新顯示相機預覽
                resetCameraView()
            }
            .setCancelable(false)
            .show()
    }
    // 功能：
    //  - 決定保存位置：
    //    - 如果有 groupId → WoundGroups/{groupId}/
    //    - 否則 → DiabetesPhotos/
    //  - 創建目標文件（新時間戳）
    //  - 使用 tempFile.copyTo() 複製文件
    //  - 更新 Android MediaStore（讓相簿可見）
    //  - 如果是群組模式，調用 updateWoundGroupLastUpdated() 更新群組資訊
    //  - 更新分析按鈕的 tag 為新文件的 URI
    //
    //  輸入： File (臨時文件)
    //  輸出： 永久保存的圖片文件
    private fun saveImageToAppDirectory(tempFile: File) {
        try {
            // 決定保存的目錄
            val destinationDir = if (groupId != null) {
                val groupDir = File(getExternalFilesDir(null), "WoundGroups/$groupId")
                if (!groupDir.exists()) {
                    groupDir.mkdirs()
                }
                groupDir
            } else {
                val appDir = File(getExternalFilesDir(null), APP_DIRECTORY)
                if (!appDir.exists()) {
                    appDir.mkdirs()
                }
                appDir
            }

            // 創建目標文件
            val timeStamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
            val destFile = File(destinationDir, "$timeStamp.jpg")

            // 複製文件
            tempFile.copyTo(destFile, overwrite = true)

            // 更新 MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$timeStamp.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$APP_DIRECTORY")
                }
            }

            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    val inputStream = destFile.inputStream()
                    inputStream.copyTo(os)
                    inputStream.close()
                }
            }

            // 更新 analyzeButton 的 tag
            analyzeButton.tag = Uri.fromFile(destFile)

            // 如果是群組模式,更新群組的最後修改時間
            if (groupId != null) {
                updateWoundGroupLastUpdated(groupId!!)
            }

            Toast.makeText(baseContext, "照片已保存", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(baseContext, "保存失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun updateWoundGroupLastUpdated(groupId: String) {
        val infoFile = File(getExternalFilesDir(null), "WoundGroups/$groupId/info.txt")
        if (infoFile.exists()) {
            try {
                val lines = infoFile.readLines()
                val properties = lines.associate { line ->
                    val parts = line.split("=", limit = 2)
                    parts[0] to parts.getOrElse(1) { "" }
                }

                // 更新最後修改時間和圖片數量
                val updatedProperties = properties.toMutableMap()
                updatedProperties["updated"] = System.currentTimeMillis().toString()

                // 更新圖片數量
                val currentCount = properties["count"]?.toIntOrNull() ?: 0
                updatedProperties["count"] = (currentCount + 1).toString()

                // 寫回文件
                infoFile.writeText(
                    updatedProperties.entries.joinToString("\n") { "${it.key}=${it.value}" }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun resetCameraView() {
        // 重新顯示相機預覽
        viewFinder.visibility = View.VISIBLE
        previewImageView.visibility = View.GONE
        detectionOverlay?.visibility = View.VISIBLE

        // 重新啟用檢測功能
        updateDetectionStatusUI(isGreenCircleDetected)
        analyzeButton.isEnabled = false
    }

   // 功能：
    //- 從分析按鈕的 tag 獲取圖片 URI
    //- 創建 Intent 啟動 AnalysisActivity
    //- 傳遞參數：
    //- EXTRA_IMAGE_URI (圖片路徑)
    //- EXTRA_GROUP_ID (群組ID，可選)
    //- EXTRA_GROUP_NAME (群組名稱，可選)

    //輸入： 無（點擊事件觸發）
    //輸出： 啟動新 Activity


    private fun analyzePhoto() {
        val imageUri = analyzeButton.tag as? Uri ?: return

        // 啟動分析活動
        val intent = Intent(this, AnalysisActivity::class.java).apply {
            putExtra(EXTRA_IMAGE_URI, imageUri.toString())
            // 如果有群組信息,也傳遞這些信息
            if (groupId != null) {
                putExtra("EXTRA_GROUP_ID", groupId)
            }
            if (groupName != null) {
                putExtra("EXTRA_GROUP_NAME", groupName)
            }
        }
        startActivity(intent)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            // 將相機的生命週期綁定到生命週期所有者
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 設置預覽
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            // 設置圖像捕獲
            imageCapture = ImageCapture.Builder().build()

            // 設置圖像分析器(僅當檢測視圖存在時)
            val useCasesList = mutableListOf<androidx.camera.core.UseCase>().apply {
                add(preview)
                add(imageCapture!!)
            }

            if (detectionStatusButton != null && detectionOverlay != null) {
                // 使用改進版檢測器
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(cameraExecutor, EnhancedGreenCircleAnalyzer { result ->
                            updateDetectionStatusUI(result.detected, result.message, result.confidence)
                            updateDetectionOverlay(result.areas)
                        })
                    }
                useCasesList.add(imageAnalyzer)

                // 更新狀態為正在檢測
                updateDetectionStatusUI(false, "🔍 相機已啟動,正在檢測...")
            } else {
                // 如果沒有檢測視圖,默認啟用拍照按鈕
                updateDetectionStatusUI(true, "📷 相機已就緒")
            }

            // 默認選擇後置相機
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 解綁所有用例後重新綁定
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, *useCasesList.toTypedArray()
                )

            } catch(exc: Exception) {
                // 處理錯誤
                updateDetectionStatusUI(false, "❌ 相機啟動失敗")
                Toast.makeText(this, "相機啟動失敗", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // 更新檢測覆蓋層 - 顯示方框
    private fun updateDetectionOverlay(detectedAreas: List<RectF>) {
        detectionOverlay?.let { overlay ->
            runOnUiThread {
                if (overlay.width == 0 || overlay.height == 0) return@runOnUiThread

                val bitmap = Bitmap.createBitmap(
                    overlay.width, overlay.height, Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)

                // 方框邊框畫筆 - 綠色粗線
                val strokePaint = Paint().apply {
                    color = Color.GREEN
                    style = Paint.Style.STROKE
                    strokeWidth = 8f  // 粗邊框
                    isAntiAlias = true
                }

                // 半透明填充畫筆
                val fillPaint = Paint().apply {
                    color = Color.argb(20, 0, 255, 0)  // 淺綠色半透明
                    style = Paint.Style.FILL
                }

                // 文字畫筆
                val textPaint = Paint().apply {
                    color = Color.GREEN
                    textSize = 40f
                    isAntiAlias = true
                    isFakeBoldText = true
                    setShadowLayer(4f, 2f, 2f, Color.BLACK)  // 文字陰影
                }

                for ((index, area) in detectedAreas.withIndex()) {
                    // 根據覆蓋層大小調整矩形
                    val scaledRect = RectF(
                        area.left * overlay.width,
                        area.top * overlay.height,
                        area.right * overlay.width,
                        area.bottom * overlay.height
                    )

                    // 繪製半透明填充
                    canvas.drawRect(scaledRect, fillPaint)

                    // 繪製方框邊框
                    canvas.drawRect(scaledRect, strokePaint)

                    // 在方框左上角標註編號
                    val label = "✓ ${index + 1}"
                    canvas.drawText(
                        label,
                        scaledRect.left + 10,
                        scaledRect.top + 50,
                        textPaint
                    )

                    // 繪製角標記以增強視覺效果
                    drawCornerMarkers(canvas, scaledRect, strokePaint)
                }

                overlay.setImageBitmap(bitmap)
            }
        }
    }

    // 輔助方法:繪製角標記
    private fun drawCornerMarkers(canvas: Canvas, rect: RectF, paint: Paint) {
        val markerLength = 30f
        val markerPaint = Paint(paint).apply {
            strokeWidth = 10f
        }

        // 左上角
        canvas.drawLine(rect.left, rect.top, rect.left + markerLength, rect.top, markerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + markerLength, markerPaint)

        // 右上角
        canvas.drawLine(rect.right, rect.top, rect.right - markerLength, rect.top, markerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + markerLength, markerPaint)

        // 左下角
        canvas.drawLine(rect.left, rect.bottom, rect.left + markerLength, rect.bottom, markerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - markerLength, markerPaint)

        // 右下角
        canvas.drawLine(rect.right, rect.bottom, rect.right - markerLength, rect.bottom, markerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - markerLength, markerPaint)
    }

    // 檢查所有必需的權限是否已授予
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    // 處理權限請求結果
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "缺少必要權限", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraActivity"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val REQUEST_CODE_PERMISSIONS = 10
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        private const val APP_DIRECTORY = "DiabetesPhotos"

        // 更新權限請求列表以支持 Android 14 (API 34)
        private val REQUIRED_PERMISSIONS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 使用新的媒體權限
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10-12 只需要 READ_EXTERNAL_STORAGE
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
            } else {
                // Android 9 及以下需要 WRITE_EXTERNAL_STORAGE
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            }
    }
}