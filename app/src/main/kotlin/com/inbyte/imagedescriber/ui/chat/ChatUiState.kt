package com.inbyte.imagedescriber.ui.chat

import android.net.Uri
import com.inbyte.imagedescriber.model.ChatMessage

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val pendingImageUri: Uri? = null,
    val inputText: String = "Describe the image",
    val isGenerating: Boolean = false,
    val isModelLoaded: Boolean = false,
    val loadingMessage: String? = "Preparing model files…",
    val errorMessage: String? = null,
    val matchedCategory: String? = null,
)
