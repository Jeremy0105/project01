package com.example.topics_diabetes

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File

class ImageAdapter(
    private val context: Context,
    private var imageFiles: Array<File>,
    private var isDeleteMode: Boolean = false
) : BaseAdapter() {

    private val tag = "ImageAdapter"
    private var onImageDeleteListener: OnImageDeleteListener? = null

    interface OnImageDeleteListener {
        fun onImageDeleted()
    }

    fun setOnImageDeleteListener(listener: OnImageDeleteListener) {
        this.onImageDeleteListener = listener
    }

    fun setDeleteMode(deleteMode: Boolean) {
        isDeleteMode = deleteMode
        notifyDataSetChanged()
    }

    fun updateData(newImageFiles: Array<File>) {
        imageFiles = newImageFiles
        notifyDataSetChanged()
    }

    override fun getCount(): Int = imageFiles.size

    override fun getItem(position: Int): Any = imageFiles[position]

    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view: View
        val holder: ViewHolder

        if (convertView == null) {
            // 如果沒有可重用的視圖，創建新視圖
            view = LayoutInflater.from(context).inflate(R.layout.grid_item_image, parent, false)
            holder = ViewHolder(view)
            view.tag = holder
        } else {
            // 重用現有視圖
            view = convertView
            holder = view.tag as ViewHolder
        }

        // 獲取當前文件並加載圖像
        val file = imageFiles[position]

        try {
            // 使用 BitmapFactory.Options 減小圖像大小以改善性能
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4  // 將原始尺寸縮小4倍
            }

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options)
            holder.imageView.setImageBitmap(bitmap)

            // 根據刪除模式設置不同的背景或視覺效果
            if (isDeleteMode) {
                // 刪除模式下的視覺效果
                holder.imageView.setColorFilter(Color.argb(70, 255, 0, 0))  // 紅色濾鏡
                view.setBackgroundColor(ContextCompat.getColor(context, android.R.color.holo_red_light))
            } else {
                // 普通模式下的視覺效果
                holder.imageView.clearColorFilter()
                view.setBackgroundColor(Color.TRANSPARENT)
            }

            // 設置點擊監聽器
            view.setOnClickListener {
                if (isDeleteMode) {
                    // 刪除模式下，點擊圖片顯示刪除確認對話框
                    showDeleteConfirmationDialog(file, position)
                } else {
                    // 普通模式下，點擊圖片進行分析
                    startAnalysisActivity(file)
                }
            }

        }
        catch (e: Exception) {
            Log.e(tag, "Error loading image: ${file.absolutePath}", e)
            // 如果圖像加載失敗，顯示錯誤信息
            holder.imageView.setImageResource(android.R.drawable.ic_menu_report_image)
        }

        return view
    }

    private fun showDeleteConfirmationDialog(file: File, position: Int) {
        AlertDialog.Builder(context)
            .setTitle("刪除確認")
            .setMessage("您確定要刪除這張圖片嗎？")
            .setPositiveButton("刪除") { _, _ ->
                deleteImage(file, position)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun deleteImage(file: File, position: Int) {
        try {
            // 嘗試刪除文件
            if (file.delete()) {
                Toast.makeText(context, "圖片刪除成功", Toast.LENGTH_SHORT).show()

                // 通知監聽器圖片已被刪除
                onImageDeleteListener?.onImageDeleted()
            } else {
                Toast.makeText(context, "圖片刪除失敗", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error deleting image", e)
            Toast.makeText(context, "刪除失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAnalysisActivity(file: File) {
        try {
            Log.d(tag, "Starting analysis for image: ${file.name}")
            Toast.makeText(context, "正在分析圖片...", Toast.LENGTH_SHORT).show()

            val intent = Intent(context, AnalysisActivity::class.java).apply {
                putExtra(CameraActivity.EXTRA_IMAGE_URI, Uri.fromFile(file).toString())
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(tag, "Error starting AnalysisActivity", e)
            Toast.makeText(context, "啟動分析失敗: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ViewHolder 模式提高 ListView/GridView 的效能
    private class ViewHolder(view: View) {
        val imageView: ImageView = view.findViewById(R.id.grid_image)
    }
}