package com.example.topics_diabetes

import android.content.Context
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class ModelManager(private val context: Context) {

    companion object {
        private const val TAG = "ModelManager"
        private const val CONFIG_FILE = "model_config.json"
    }

    data class ModelConfig(
        val id: String,
        val name: String,
        val version: String,
        val fileName: String,
        val enabled: Boolean,
        val color: Int,
        val inputSize: Int,
        val threshold: Float,
        val description: String
    )

    private var modelConfigs: List<ModelConfig> = emptyList()
    private val interpreters = mutableMapOf<String, Interpreter>()
//功能：
//  - 從 assets/model_config.json 讀取配置文件
//  - 解析 JSON，提取每個模型的：
//    - id (例如: "gt_tissue", "bone")
//    - name (中文名稱)
//    - fileName (.tflite 文件名)
//    - color (遮罩顏色)
//    - inputSize (512)
//    - threshold (0.5)
//    - enabled (是否啟用)
//  - 過濾出 enabled: true 的模型
//  - 返回 List<ModelConfig>
//
//  輸入： 無
//  輸出： List (7個模型配置)
    fun loadModelConfigs(): List<ModelConfig> {
        try {
            val configJson = context.assets.open(CONFIG_FILE)
                .bufferedReader()
                .use { it.readText() }

            val jsonObject = JSONObject(configJson)
            val modelsArray = jsonObject.getJSONArray("models")

            modelConfigs = (0 until modelsArray.length()).map { i ->
                val model = modelsArray.getJSONObject(i)
                ModelConfig(
                    id = model.getString("id"),
                    name = model.getString("name"),
                    version = model.getString("version"),
                    fileName = model.getString("fileName"),
                    enabled = model.getBoolean("enabled"),
                    color = Color.parseColor(model.getString("color")),
                    inputSize = model.getInt("inputSize"),
                    threshold = model.getDouble("threshold").toFloat(),
                    description = model.getString("description")
                )
            }.filter { it.enabled }

            Log.d(TAG, "載入 ${modelConfigs.size} 個啟用的模型配置")
            return modelConfigs
        } catch (e: Exception) {
            Log.e(TAG, "載入模型配置失敗", e)
            return emptyList()
        }
    }
// 功能：
//  - 調用 loadModelConfigs() 讀取配置
//  - FOR 每個啟用的模型：
//    - 調用 loadModelFile(fileName) 載入 .tflite 文件
//    - 創建 Interpreter.Options（設定 4 個執行緒）
//    - 創建 Interpreter 實例
//    - 保存到 interpreters Map
//  - 返回成功載入的模型數量
//
//  輸入： 無
//  輸出： Map<modelId, Interpreter>
    suspend fun initializeModels(): Boolean = withContext(Dispatchers.IO) {
        try {
            val configs = loadModelConfigs()

            for (config in configs) {
                if (config.enabled) {
                    try {
                        val modelBuffer = loadModelFile(config.fileName)
                        val options = Interpreter.Options().apply {
                            numThreads = 4
                        }
                        interpreters[config.id] = Interpreter(modelBuffer, options)
                        Log.d(TAG, "✓ 模型載入成功: ${config.name} v${config.version}")
                    } catch (e: Exception) {
                        Log.e(TAG, "✗ 模型載入失敗: ${config.name}", e)
                    }
                }
            }

            val successCount = interpreters.size
            Log.d(TAG, "成功初始化 $successCount/${configs.size} 個模型")
            successCount > 0
        } catch (e: Exception) {
            Log.e(TAG, "模型初始化失敗", e)
            false
        }
    }

    fun getAvailableModels(): List<ModelConfig> {
        return modelConfigs.filter { it.enabled }
    }

    fun getModelConfig(modelId: String): ModelConfig? {
        return modelConfigs.find { it.id == modelId }
    }

    fun getInterpreter(modelId: String): Interpreter? {
        return interpreters[modelId]
    }

    fun isAllModelsReady(): Boolean {
        val expectedCount = modelConfigs.count { it.enabled }
        return interpreters.size == expectedCount && expectedCount > 0
    }

    fun checkModelFiles(): Map<String, Boolean> {
        val configs = loadModelConfigs()
        return configs.associate { config ->
            config.id to try {
                context.assets.open(config.fileName).use { true }
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(fileName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun close() {
        interpreters.values.forEach { it.close() }
        interpreters.clear()
        Log.d(TAG, "所有模型資源已釋放")
    }
}