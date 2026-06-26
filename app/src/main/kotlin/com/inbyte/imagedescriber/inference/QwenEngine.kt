package com.inbyte.imagedescriber.inference

import android.content.Context
import com.arm.aichat.LlamaEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QwenEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val engine by lazy { LlamaEngine.getInstance(context) }

    private var loaded = false

    companion object {
        private const val SYSTEM_PROMPT =
            "You are a concept extractor. When given a description, extract the most important " +
            "concepts and list them one per line. No numbers, no bullet points, no explanation, no extra text."
    }

    suspend fun loadModel(modelPath: String) {
        engine.loadModel(modelPath)
        engine.setSystemPrompt(SYSTEM_PROMPT)
        loaded = true
    }

    fun isLoaded() = loaded

    fun generate(prompt: String, maxTokens: Int = 512): Flow<String> =
        engine.sendUserPrompt(prompt, maxTokens).map { token ->
            // Strip special tokens Qwen3 may emit
            token.replace("<think>", "")
                 .replace("</think>", "")
                 .replace("<|im_end|>", "")
                 .replace("<|im_start|>", "")
        }

    fun unload() {
        if (loaded) {
            engine.cleanUp()
            loaded = false
        }
    }

    fun destroy() {
        engine.destroy()
        loaded = false
    }
}
