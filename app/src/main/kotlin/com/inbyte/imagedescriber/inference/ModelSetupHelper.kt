package com.inbyte.imagedescriber.inference

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ModelSetupHelper {

    private const val MODEL_ASSET   = "models/SmolVLM-500M-Instruct-Q4_K_M.gguf"
    private const val MMPROJ_ASSET  = "models/mmproj-SmolVLM-500M-Instruct-Q8_0.gguf"
    private const val QWEN_ASSET    = "qwen3-1.7b.Q4_K_M.gguf"
    private const val QWEN_VERSION  = 4  // increment this every time the Qwen asset changes
    private const val PREFS_NAME    = "model_prefs"
    private const val KEY_QWEN_VER  = "qwen_version"

    data class ModelPaths(
        val modelPath: String,
        val clipPath: String,
        val qwenPath: String,
    )

    suspend fun prepareModels(context: Context, onProgress: (String) -> Unit): ModelPaths =
        withContext(Dispatchers.IO) {
            val modelsDir = File(context.filesDir, "models").also { it.mkdirs() }
            val modelFile = File(modelsDir, "SmolVLM-500M-Instruct-Q4_K_M.gguf")
            val clipFile  = File(modelsDir, "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf")
            val qwenFile  = File(modelsDir, "qwen3-1.7b.Q4_K_M.gguf")

            if (!modelFile.exists()) {
                onProgress("Copying SmolVLM model…")
                copyAsset(context, MODEL_ASSET, modelFile)
            }
            if (!clipFile.exists()) {
                onProgress("Copying vision projector…")
                copyAsset(context, MMPROJ_ASSET, clipFile)
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val installedVersion = prefs.getInt(KEY_QWEN_VER, 0)
            if (!qwenFile.exists() || installedVersion != QWEN_VERSION) {
                onProgress("Copying Qwen3 model…")
                copyAsset(context, QWEN_ASSET, qwenFile)
                prefs.edit().putInt(KEY_QWEN_VER, QWEN_VERSION).apply()
            }

            ModelPaths(modelFile.absolutePath, clipFile.absolutePath, qwenFile.absolutePath)
        }

    private fun copyAsset(context: Context, assetPath: String, dest: File) {
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output, bufferSize = 8 * 1024 * 1024) }
        }
    }
}
