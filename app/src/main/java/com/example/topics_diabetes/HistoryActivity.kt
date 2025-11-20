package com.example.topics_diabetes

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.GridView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Color

class HistoryActivity : AppCompatActivity(), ImageAdapter.OnImageDeleteListener {

    private lateinit var selectFromGalleryButton: Button
    private lateinit var deleteButton: Button
    private lateinit var historyGridView: GridView
    private lateinit var emptyTextView: TextView
    private var imageFiles: Array<File> = emptyArray()
    private var isDeleteMode = false
    private lateinit var imageAdapter: ImageAdapter

    // 使用 ActivityResultLauncher 處理圖片選擇結果
    private val pickImage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data
            val selectedImageUri = data?.data
            if (selectedImageUri != null) {
                // 詢問是否使用此圖片
                showImageConfirmationDialog(selectedImageUri)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        // 初始化視圖
        selectFromGalleryButton = findViewById(R.id.select_from_gallery_button)
        deleteButton = findViewById(R.id.delete_mode_button)
        historyGridView = findViewById(R.id.history_grid_view)
        emptyTextView = findViewById(R.id.empty_text_view)

        // 設置按鈕點擊監聽器
        selectFromGalleryButton.setOnClickListener { openGallery() }
        deleteButton.setOnClickListener { toggleDeleteMode() }

        // 初始化適配器
        imageAdapter = ImageAdapter(this, imageFiles, false)
        imageAdapter.setOnImageDeleteListener(this)
        historyGridView.adapter = imageAdapter

        // 加載歷史記錄
        loadHistoryImages()
    }

    override fun onResume() {
        super.onResume()
        // 當活動重新顯示時刷新歷史記錄
        if (isDeleteMode) {
            // 如果處於刪除模式時返回，自動退出刪除模式
            exitDeleteMode()
        } else {
            loadHistoryImages()
        }
    }

    private fun toggleDeleteMode() {
        isDeleteMode = !isDeleteMode

        if (isDeleteMode) {
            deleteButton.apply {
                text = "完成刪除"
                setBackgroundResource(R.drawable.button_normal)
                setTextColor(Color.WHITE)
            }
            selectFromGalleryButton.isEnabled = false
            Toast.makeText(this, "點擊圖片進行刪除", Toast.LENGTH_SHORT).show()
        } else {
            exitDeleteMode()
        }

        imageAdapter.setDeleteMode(isDeleteMode)
    }

    private fun exitDeleteMode() {
        isDeleteMode = false
        deleteButton.apply {
            text = "刪除圖片"
            setBackgroundResource(R.drawable.button_normal)
            setTextColor(Color.WHITE)
        }
        selectFromGalleryButton.isEnabled = true
        imageAdapter.setDeleteMode(false)
    }

    override fun onImageDeleted() {
        // 刪除圖片後重新加載圖片列表
        loadHistoryImages()

        // 如果所有圖片都已刪除，自動退出刪除模式
        if (imageFiles.isEmpty()) {
            exitDeleteMode()
        }
    }

    private fun openGallery() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 使用 Photo Picker API (適用於 Android 13 及以上)
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 1)
            }
        } else {
            // 舊版方法
            Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        pickImage.launch(intent)
    }

    private fun showImageConfirmationDialog(imageUri: Uri) {
        AlertDialog.Builder(this)
            .setTitle("圖片選擇")
            .setMessage("您要使用這張圖片進行分析嗎？")
            .setPositiveButton("使用") { _, _ ->
                // 保存圖片到應用目錄並進行分析
                copyImageToAppDirectoryAndAnalyze(imageUri)
            }
            .setNegativeButton("取消", null)
            .setCancelable(false)
            .show()
    }

    private fun copyImageToAppDirectoryAndAnalyze(uri: Uri) {
        try {
            // 創建應用專用目錄

            val appDir = File(getExternalFilesDir(null), APP_DIRECTORY)
            if (!appDir.exists()) {
                appDir.mkdirs()
            }

            // 創建目標文件
            val timeStamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis())
            val destFile = File(appDir, "$timeStamp.jpg")

            // 複製文件
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            // 進行分析
            val savedUri = Uri.fromFile(destFile)
            startAnalysisActivity(savedUri)

            Toast.makeText(baseContext, "圖片已複製至應用目錄", Toast.LENGTH_SHORT).show()

            // 重新加載歷史圖像
            loadHistoryImages()
        } catch (e: Exception) {
            Toast.makeText(baseContext, "複製失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAnalysisActivity(imageUri: Uri) {
        val intent = Intent(this, AnalysisActivity::class.java).apply {
            putExtra(CameraActivity.EXTRA_IMAGE_URI, imageUri.toString())
        }
        startActivity(intent)
    }

    private fun loadHistoryImages() {
        // 獲取應用目錄中的所有圖像
        val appDir = File(getExternalFilesDir(null), APP_DIRECTORY)
        if (!appDir.exists()) {
            appDir.mkdirs()
        }

        val files = appDir.listFiles { file ->
            file.isFile && (file.name.endsWith(".jpg", ignoreCase = true) ||
                    file.name.endsWith(".jpeg", ignoreCase = true) ||
                    file.name.endsWith(".png", ignoreCase = true))
        }

        if (files != null && files.isNotEmpty()) {
            // 按最後修改時間排序（最新的排在前面）
            imageFiles = files.sortedByDescending { it.lastModified() }.toTypedArray()

            historyGridView.visibility = View.VISIBLE
            emptyTextView.visibility = View.GONE

            // 設置適配器顯示圖像
            imageAdapter.updateData(imageFiles)

            // 更新刪除按鈕的狀態
            deleteButton.visibility = View.VISIBLE
        } else {
            historyGridView.visibility = View.GONE
            emptyTextView.visibility = View.VISIBLE
            imageFiles = emptyArray()
            imageAdapter.updateData(imageFiles)

            // 如果沒有圖片，隱藏刪除按鈕
            deleteButton.visibility = View.GONE

            // 如果當前處於刪除模式，自動退出
            if (isDeleteMode) {
                exitDeleteMode()
            }
        }
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private const val APP_DIRECTORY = "DiabetesPhotos"
    }
}