package com.inbyte.imagedescriber

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.inbyte.imagedescriber.ui.chat.ChatScreen
import com.inbyte.imagedescriber.ui.theme.InByteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            InByteTheme {
                ChatScreen()
            }
        }
    }
}
