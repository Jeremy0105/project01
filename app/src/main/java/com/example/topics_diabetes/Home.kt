package com.example.topics_diabetes

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class Home : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        // Initialize UI components
        setupUI()
    }

    private fun setupUI() {
        // Get references to UI elements
        val welcomeText = findViewById<TextView>(R.id.tvWelcome)
        val cameraCard = findViewById<CardView>(R.id.cardCamera)
        val historyCard = findViewById<CardView>(R.id.cardHistory)
        val woundManagementCard = findViewById<CardView>(R.id.cardWoundManagement)
        val reportsButton = findViewById<Button>(R.id.btnReports)
        val settingsButton = findViewById<Button>(R.id.btnSettings)
        val versionTextView = findViewById<TextView>(R.id.tvVersion)

        // 設置版本號
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            versionTextView.text = "版本 ${packageInfo.versionName}"
        } catch (e: Exception) {
            versionTextView.text = "版本 1.0.0" // 預設版本號
        }

        // Set up click listeners for the cards
        cameraCard.setOnClickListener {
            // Navigate to Camera screen for food photo
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
        }

        historyCard.setOnClickListener {
            // Navigate to History screen - 歷史紀錄(照片)
            val intent = Intent(this, HistoryActivity::class.java)
            startActivity(intent)
        }

        woundManagementCard.setOnClickListener {
            // Navigate to History Analysis screen - 歷史紀錄(分析)
            val intent = Intent(this, HistoryAnalysisActivity::class.java)
            startActivity(intent)
        }

        // 預留卡片的點擊事件可以稍後添加
        // 當您準備好實現這些功能時，可以解除註釋並添加相應的導航邏輯

        // 如果您有其他預留卡片，可以在這裡添加點擊事件
        // 例如：
        /*
        val cardPlaceholder1 = findViewById<CardView>(R.id.cardPlaceholder1)
        val cardPlaceholder2 = findViewById<CardView>(R.id.cardPlaceholder2)
        val cardPlaceholder3 = findViewById<CardView>(R.id.cardPlaceholder3)

        cardPlaceholder1.setOnClickListener {
            // 預留功能1的實現
            Toast.makeText(this, "預留功能1", Toast.LENGTH_SHORT).show()
        }

        cardPlaceholder2.setOnClickListener {
            // 預留功能2的實現
            Toast.makeText(this, "預留功能2", Toast.LENGTH_SHORT).show()
        }

        cardPlaceholder3.setOnClickListener {
            // 預留功能3的實現
            Toast.makeText(this, "預留功能3", Toast.LENGTH_SHORT).show()
        }
        */

        // 報告按鈕點擊事件（如果需要實現）
        /*
        reportsButton.setOnClickListener {
            // Navigate to Reports screen
            val intent = Intent(this, ReportsActivity::class.java)
            startActivity(intent)
        }
        */

        // 設置按鈕點擊事件（如果需要實現）
        /*
        settingsButton.setOnClickListener {
            // Navigate to Settings screen
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        */
    }
}