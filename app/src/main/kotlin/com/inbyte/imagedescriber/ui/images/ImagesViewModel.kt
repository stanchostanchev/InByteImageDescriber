package com.inbyte.imagedescriber.ui.images

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class ImagesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _imageUris = MutableStateFlow<List<String>>(loadAssetImages())
    val imageUris: StateFlow<List<String>> = _imageUris.asStateFlow()

    private val _selectedImageUri = MutableStateFlow<String?>(null)
    val selectedImageUri: StateFlow<String?> = _selectedImageUri.asStateFlow()

    fun selectImage(uri: String) {
        _selectedImageUri.value = uri
    }

    private fun loadAssetImages(): List<String> {
        return try {
            val all = context.assets.list("images") ?: emptyArray()
            all.filter { it.first().isDigit() && (it.endsWith(".png") || it.endsWith(".jpg") || it.endsWith(".jpeg")) }
                .sorted()
                .map { "file:///android_asset/images/$it" }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addImages(uris: List<Uri>) {
        _imageUris.value = _imageUris.value + uris.map { it.toString() }
    }

    fun removeImage(uri: String) {
        _imageUris.value = _imageUris.value - uri
    }
}
