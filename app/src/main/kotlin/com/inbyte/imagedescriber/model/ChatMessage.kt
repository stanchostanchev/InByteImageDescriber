package com.inbyte.imagedescriber.model

import android.net.Uri
import java.util.UUID

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    val text: String,
    val imageUri: Uri? = null,
    val isStreaming: Boolean = false,
)

enum class MessageRole { USER, ASSISTANT }
