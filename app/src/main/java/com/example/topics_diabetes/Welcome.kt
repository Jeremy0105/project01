package com.example.topics_diabetes

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class Welcome : AppCompatActivity() {

    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var guestButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        // 初始化視圖
        loginButton = findViewById(R.id.login_button)
        registerButton = findViewById(R.id.register_button)
        guestButton = findViewById(R.id.guest_button)

        // 設置點擊監聽器
        loginButton.setOnClickListener { showLoginDialog() }
        registerButton.setOnClickListener { showRegisterDialog() }
        guestButton.setOnClickListener { loginAsGuest() }
    }

    private fun showLoginDialog() {
        // 創建登入對話框
        val dialogView = layoutInflater.inflate(R.layout.dialog_login, null)
        val usernameEditText = dialogView.findViewById<EditText>(R.id.username_edit_text)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.password_edit_text)
        val forgotPasswordText = dialogView.findViewById<TextView>(R.id.forgot_password_text)

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("登入")
            .setPositiveButton("登入", null) // 稍後設置
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create()

        // 設置"忘記密碼"點擊事件
        forgotPasswordText.setOnClickListener {
            alertDialog.dismiss()
            showForgotPasswordDialog()
        }

        // 顯示對話框
        alertDialog.show()

        // 設置登入按鈕點擊事件（必須在show之後設置，以防自動關閉對話框）
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val username = usernameEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()

            if (validateLoginInput(username, password)) {
                // 嘗試登入
                if (performLogin(username, password)) {
                    alertDialog.dismiss()
                    navigateToHome(username, false) // 非訪客模式
                }
            }
        }
    }

    private fun showRegisterDialog() {
        // 創建註冊對話框
        val dialogView = layoutInflater.inflate(R.layout.dialog_register, null)
        val usernameEditText = dialogView.findViewById<EditText>(R.id.username_edit_text)
        val emailEditText = dialogView.findViewById<EditText>(R.id.email_edit_text)
        val passwordEditText = dialogView.findViewById<EditText>(R.id.password_edit_text)
        val confirmPasswordEditText = dialogView.findViewById<EditText>(R.id.confirm_password_edit_text)

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("註冊")
            .setPositiveButton("註冊", null) // 稍後設置
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create()

        // 顯示對話框
        alertDialog.show()

        // 設置註冊按鈕點擊事件
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val username = usernameEditText.text.toString().trim()
            val email = emailEditText.text.toString().trim()
            val password = passwordEditText.text.toString().trim()
            val confirmPassword = confirmPasswordEditText.text.toString().trim()

            if (validateRegisterInput(username, email, password, confirmPassword)) {
                // 嘗試註冊
                if (performRegister(username, email, password)) {
                    Toast.makeText(this, "註冊成功，請登入", Toast.LENGTH_SHORT).show()
                    alertDialog.dismiss()

                    // 立即顯示登入對話框
                    showLoginDialog()
                }
            }
        }
    }

    private fun showForgotPasswordDialog() {
        // 創建忘記密碼對話框
        val dialogView = layoutInflater.inflate(R.layout.dialog_forgot_password, null)
        val emailEditText = dialogView.findViewById<EditText>(R.id.email_edit_text)

        val alertDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setTitle("忘記密碼")
            .setPositiveButton("提交", null) // 稍後設置
            .setNegativeButton("取消") { dialog, _ -> dialog.dismiss() }
            .create()

        // 顯示對話框
        alertDialog.show()

        // 設置提交按鈕點擊事件
        alertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val email = emailEditText.text.toString().trim()

            if (validateEmail(email)) {
                // 發送重置密碼郵件（實際應用中調用相應API）
                Toast.makeText(this, "重置密碼郵件已發送至 $email", Toast.LENGTH_LONG).show()
                alertDialog.dismiss()
            } else {
                emailEditText.error = "請輸入有效的電子郵件"
            }
        }
    }

    private fun loginAsGuest() {
        // 訪客登入
        navigateToHome("訪客", true)
    }

    // 驗證登入輸入
    private fun validateLoginInput(username: String, password: String): Boolean {
        if (TextUtils.isEmpty(username)) {
            Toast.makeText(this, "請輸入用戶名", Toast.LENGTH_SHORT).show()
            return false
        }

        if (TextUtils.isEmpty(password)) {
            Toast.makeText(this, "請輸入密碼", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    // 驗證註冊輸入
    private fun validateRegisterInput(username: String, email: String, password: String, confirmPassword: String): Boolean {
        if (TextUtils.isEmpty(username)) {
            Toast.makeText(this, "請輸入用戶名", Toast.LENGTH_SHORT).show()
            return false
        }

        if (!validateEmail(email)) {
            Toast.makeText(this, "請輸入有效的電子郵件", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password.length < 6) {
            Toast.makeText(this, "密碼至少需要6個字符", Toast.LENGTH_SHORT).show()
            return false
        }

        if (password != confirmPassword) {
            Toast.makeText(this, "兩次輸入的密碼不匹配", Toast.LENGTH_SHORT).show()
            return false
        }

        return true
    }

    // 驗證電子郵件格式
    private fun validateEmail(email: String): Boolean {
        return !TextUtils.isEmpty(email) && android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    // 執行登入（實際應用中應連接到您的認證服務）
    private fun performLogin(username: String, password: String): Boolean {
        // 模擬登入成功
        // 實際應用中，這裡應該調用您的登入API或使用Firebase Authentication等服務
        Log.d("Welcome", "用戶 $username 嘗試登入")

        // 假設登入成功
        return true
    }

    // 執行註冊（實際應用中應連接到您的認證服務）
    private fun performRegister(username: String, email: String, password: String): Boolean {
        // 模擬註冊成功
        // 實際應用中，這裡應該調用您的註冊API或使用Firebase Authentication等服務
        Log.d("Welcome", "新用戶註冊: $username, $email")

        // 假設註冊成功
        return true
    }

    // 導航至主頁
    private fun navigateToHome(username: String, isGuest: Boolean) {
        val intent = Intent(this, Home::class.java).apply {
            putExtra("USERNAME", username)
            putExtra("IS_GUEST", isGuest)
        }
        startActivity(intent)

        // 如果用戶已登入或以訪客身份登入，不需要回到歡迎頁面
        if (!isGuest) {
            finish()
        }
    }
}