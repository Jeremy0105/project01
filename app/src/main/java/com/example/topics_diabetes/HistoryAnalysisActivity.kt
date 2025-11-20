package com.example.topics_diabetes

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryAnalysisActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var addGroupButton: Button
    private lateinit var emptyView: TextView
    private lateinit var woundGroupAdapter: WoundGroupAdapter
    private val woundGroups = mutableListOf<WoundGroup>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history_analysis)

        initializeViews()
        setupRecyclerView()
        setupButtonListeners()
        loadWoundGroups()
    }

    override fun onResume() {
        super.onResume()
        loadWoundGroups()
    }

    private fun initializeViews() {
        recyclerView = findViewById(R.id.wound_groups_recycler_view)
        addGroupButton = findViewById(R.id.add_group_button)
        emptyView = findViewById(R.id.empty_view)

        supportActionBar?.title = "歷史紀錄(分析)"
    }

    private fun setupRecyclerView() {
        recyclerView.layoutManager = LinearLayoutManager(this)
        woundGroupAdapter = WoundGroupAdapter(woundGroups)
        recyclerView.adapter = woundGroupAdapter
    }

    private fun setupButtonListeners() {
        addGroupButton.setOnClickListener { showAddGroupDialog() }
    }

    private fun showAddGroupDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_wound_group, null)
        val nameEditText = dialogView.findViewById<EditText>(R.id.group_name_edit_text)
        val locationEditText = dialogView.findViewById<EditText>(R.id.wound_location_edit_text)

        nameEditText.hint = "例如：張三 - 腿部傷口"
        locationEditText.hint = "例如：左腿膝蓋"

        AlertDialog.Builder(this)
            .setTitle("新增分析群組")
            .setMessage("為不同區域或患者的傷口創建專門的分析群組")
            .setView(dialogView)
            .setPositiveButton("創建") { _, _ ->
                val name = nameEditText.text.toString().trim()
                val location = locationEditText.text.toString().trim()

                if (name.isNotEmpty()) {
                    addWoundGroup(name, location)
                } else {
                    Toast.makeText(this, "請輸入群組名稱", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addWoundGroup(name: String, location: String) {
        if (woundGroups.any { it.name == name }) {
            Toast.makeText(this, "已存在相同名稱的群組", Toast.LENGTH_SHORT).show()
            return
        }

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

        woundGroups.add(0, woundGroup)
        woundGroupAdapter.notifyItemInserted(0)
        updateEmptyView()

        Toast.makeText(this, "已創建分析群組：$name", Toast.LENGTH_SHORT).show()
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

    private fun loadWoundGroups() {
        woundGroups.clear()

        val rootDir = File(getExternalFilesDir(null), "WoundGroups")
        if (!rootDir.exists()) {
            rootDir.mkdirs()
            updateEmptyView()
            return
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

                        val analysisCount = countAnalysisFiles(dir)

                        val woundGroup = WoundGroup(
                            id = dir.name,
                            name = properties["name"] ?: "未命名群組",
                            location = properties["location"] ?: "",
                            creationDate = Date(properties["created"]?.toLongOrNull() ?: System.currentTimeMillis()),
                            lastUpdated = Date(properties["updated"]?.toLongOrNull() ?: System.currentTimeMillis()),
                            imageCount = analysisCount
                        )

                        woundGroups.add(woundGroup)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            woundGroups.sortByDescending { it.lastUpdated }
        }

        woundGroupAdapter.notifyDataSetChanged()
        updateEmptyView()
    }

    private fun countAnalysisFiles(groupDir: File): Int {
        val reportFiles = groupDir.listFiles { file ->
            file.isFile && file.name.endsWith("_report.txt")
        }
        return reportFiles?.size ?: 0
    }

    private fun updateEmptyView() {
        if (woundGroups.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    private fun openWoundGroupDetail(woundGroup: WoundGroup) {
        val intent = Intent(this, WoundGroupDetailActivity::class.java).apply {
            putExtra(EXTRA_GROUP_ID, woundGroup.id)
            putExtra(EXTRA_GROUP_NAME, woundGroup.name)
        }
        startActivity(intent)
    }

    private fun deleteWoundGroup(woundGroup: WoundGroup, position: Int) {
        AlertDialog.Builder(this)
            .setTitle("刪除確認")
            .setMessage("確定要刪除群組「${woundGroup.name}」嗎？\n這將刪除該群組中的所有分析記錄。")
            .setPositiveButton("刪除") { _, _ ->
                val groupDir = File(getExternalFilesDir(null), "WoundGroups/${woundGroup.id}")
                if (groupDir.exists()) {
                    groupDir.deleteRecursively()
                }

                woundGroups.removeAt(position)
                woundGroupAdapter.notifyItemRemoved(position)
                updateEmptyView()

                Toast.makeText(this, "已刪除群組：${woundGroup.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    inner class WoundGroupAdapter(private val woundGroups: List<WoundGroup>) :
        RecyclerView.Adapter<WoundGroupAdapter.WoundGroupViewHolder>() {

        inner class WoundGroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val nameTextView: TextView = itemView.findViewById(R.id.group_name_text)
            val locationTextView: TextView = itemView.findViewById(R.id.location_text)
            val dateTextView: TextView = itemView.findViewById(R.id.date_text)
            val countTextView: TextView = itemView.findViewById(R.id.image_count_text)
            val openButton: Button = itemView.findViewById(R.id.open_group_button)
            val deleteButton: Button = itemView.findViewById(R.id.delete_group_button)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WoundGroupViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_analysis_group, parent, false)
            return WoundGroupViewHolder(view)
        }

        override fun onBindViewHolder(holder: WoundGroupViewHolder, position: Int) {
            val woundGroup = woundGroups[position]

            holder.nameTextView.text = woundGroup.name
            holder.locationTextView.text = if (woundGroup.location.isNotEmpty()) {
                "位置: ${woundGroup.location}"
            } else {
                "位置: 未指定"
            }

            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            holder.dateTextView.text = "創建於: ${dateFormat.format(woundGroup.creationDate)}"

            val analysisText = if (woundGroup.imageCount > 0) {
                "分析記錄: ${woundGroup.imageCount} 筆"
            } else {
                "尚無分析記錄"
            }
            holder.countTextView.text = analysisText

            holder.openButton.setOnClickListener {
                openWoundGroupDetail(woundGroup)
            }

            holder.deleteButton.setOnClickListener {
                deleteWoundGroup(woundGroup, position)
            }

            holder.itemView.setOnClickListener {
                openWoundGroupDetail(woundGroup)
            }

            if (woundGroup.imageCount == 0) {
                holder.countTextView.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this@HistoryAnalysisActivity, android.R.color.darker_gray)
                )
            } else {
                holder.countTextView.setTextColor(
                    androidx.core.content.ContextCompat.getColor(this@HistoryAnalysisActivity, android.R.color.black)
                )
            }
        }

        override fun getItemCount(): Int = woundGroups.size
    }

    data class WoundGroup(
        val id: String,
        val name: String,
        val location: String,
        val creationDate: Date,
        val lastUpdated: Date,
        val imageCount: Int
    )

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"
    }
}
