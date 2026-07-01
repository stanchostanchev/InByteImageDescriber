package com.inbyte.imagedescriber.ui.description

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.inbyte.imagedescriber.inference.CategoryClassifier
import com.inbyte.imagedescriber.inference.LlamaEngine
import com.inbyte.imagedescriber.inference.ModelSetupHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class DescriptionUiState(
    val imageUri: String? = null,
    val description: String = "",
    val categories: List<String> = emptyList(),
    val categoryInput: String = "",
    val isGenerating: Boolean = false,
    val isModelReady: Boolean = false,
    val loadingMessage: String? = "Preparing model…",
    val errorMessage: String? = null,
)

@HiltViewModel
class DescriptionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val llamaEngine: LlamaEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DescriptionUiState())
    val uiState: StateFlow<DescriptionUiState> = _uiState.asStateFlow()

    private var smolvlmPath: String = ""
    private var clipPath: String = ""

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val paths = ModelSetupHelper.prepareModels(context) { msg ->
                    _uiState.value = _uiState.value.copy(loadingMessage = msg)
                }
                smolvlmPath = paths.modelPath
                clipPath    = paths.clipPath
                val loaded = llamaEngine.loadModel(
                    modelPath     = smolvlmPath,
                    clipModelPath = clipPath,
                    contextSize   = 2048,
                    threads       = 4,
                )
                _uiState.value = _uiState.value.copy(
                    isModelReady   = loaded,
                    loadingMessage = null,
                    errorMessage   = if (!loaded) "Failed to load vision model." else null,
                )
                if (loaded && pendingDescribe) {
                    pendingDescribe = false
                    describe()
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    loadingMessage = null,
                    errorMessage   = "Setup error: ${e.message}",
                )
            }
        }
    }

    private suspend fun ensureSmolVLM(): Boolean {
        if (smolvlmPath.isEmpty()) return false
        if (llamaEngine.loadedModelPath == smolvlmPath) return true
        _uiState.value = _uiState.value.copy(loadingMessage = "Loading vision model…")
        val loaded = withContext(Dispatchers.IO) {
            llamaEngine.loadModel(smolvlmPath, clipPath, 2048, 4)
        }
        _uiState.value = _uiState.value.copy(loadingMessage = null)
        return loaded
    }

    fun setImageAndDescribe(uri: String) {
        if (_uiState.value.imageUri == uri) return
        _uiState.value = _uiState.value.copy(imageUri = uri, description = "")
        if (_uiState.value.isModelReady) describe()
        // if model isn't ready yet, describe() will be called once init finishes — handled below
        else pendingDescribe = true
    }

    private var pendingDescribe = false

    fun describe() {
        val uri = _uiState.value.imageUri ?: return
        if (_uiState.value.isGenerating || !_uiState.value.isModelReady) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isGenerating = true,
                description  = "",
                categories   = emptyList(),
                categoryInput = "",
                errorMessage = null,
            )
            try {
                if (!ensureSmolVLM()) {
                    _uiState.value = _uiState.value.copy(errorMessage = "Failed to load vision model.", isGenerating = false)
                    return@launch
                }
                val resolvedUri = resolveUri(uri)
                llamaEngine.describeImage(
                    context   = context,
                    imageUri  = resolvedUri,
                    prompt    = "Describe this children's drawing in detail.",
                    maxTokens = 300,
                ).collect { token ->
                    _uiState.value = _uiState.value.copy(description = _uiState.value.description + token)
                }
                val categories = CategoryClassifier.classify(_uiState.value.description)
                _uiState.value = _uiState.value.copy(
                    categories    = categories,
                    categoryInput = categories.firstOrNull() ?: "",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Error: ${e.message}")
            } finally {
                _uiState.value = _uiState.value.copy(isGenerating = false)
            }
        }
    }

    fun stopGeneration() = llamaEngine.cancel()

    fun onCategoryInputChange(text: String) {
        _uiState.value = _uiState.value.copy(categoryInput = text)
    }

    // asset URIs (file:///android_asset/...) can't be opened via contentResolver — copy to cache first
    private suspend fun resolveUri(uri: String): Uri = withContext(Dispatchers.IO) {
        if (uri.startsWith("file:///android_asset/")) {
            val assetPath = uri.removePrefix("file:///android_asset/")
            val cacheFile = File(context.cacheDir, File(assetPath).name)
            if (!cacheFile.exists()) {
                context.assets.open(assetPath).use { input ->
                    cacheFile.outputStream().use { input.copyTo(it) }
                }
            }
            Uri.fromFile(cacheFile)
        } else {
            Uri.parse(uri)
        }
    }
}
