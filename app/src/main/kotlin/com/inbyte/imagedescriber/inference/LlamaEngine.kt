package com.inbyte.imagedescriber.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaEngine @Inject constructor() {

    private var nativeHandle: Long = 0L

    val isLoaded: Boolean get() = nativeHandle != 0L

    // Tracks which model file is currently loaded on the native side so callers
    // sharing this singleton (ChatViewModel + DescriptionViewModel) don't reload
    // unnecessarily — or worse, assume the wrong model is loaded.
    @Volatile
    var loadedModelPath: String = ""
        private set

    fun loadModel(
        modelPath: String,
        clipModelPath: String = "",
        contextSize: Int = 2048,
        threads: Int = 4,
    ): Boolean {
        if (loadedModelPath == modelPath && nativeHandle != 0L) return true
        if (nativeHandle != 0L) free()
        nativeHandle = nativeLoadModel(modelPath, clipModelPath, contextSize, threads)
        loadedModelPath = if (nativeHandle != 0L) modelPath else ""
        return nativeHandle != 0L
    }

    fun describeImage(
        context: Context,
        imageUri: Uri,
        prompt: String,
        maxTokens: Int = 512,
    ): Flow<String> = callbackFlow {
        val imageBytes = loadAndResizeImage(context, imageUri, maxSize = 512)
            ?: run { close(IllegalStateException("Cannot read image")); return@callbackFlow }

        nativeDescribeImage(
            handle        = nativeHandle,
            imageBytes    = imageBytes,
            prompt        = prompt,
            maxTokens     = maxTokens,
            temperature   = 0.1f,
            tokenCallback = { token -> trySend(token) },
        )
        close()
    }.flowOn(Dispatchers.Default)

    fun generate(
        prompt: String,
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
    ): Flow<String> = callbackFlow {
        nativeGenerate(
            handle        = nativeHandle,
            prompt        = prompt,
            maxTokens     = maxTokens,
            temperature   = temperature,
            tokenCallback = { token -> trySend(token) },
        )
        close()
    }.flowOn(Dispatchers.Default)

    private fun loadAndResizeImage(context: Context, uri: Uri, maxSize: Int): ByteArray? {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
        val w = opts.outWidth; val h = opts.outHeight
        if (w <= maxSize && h <= maxSize) return raw
        val scale = maxSize.toFloat() / maxOf(w, h)
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val scaled = Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
        bmp.recycle()
        val enhanced = enhanceContrast(scaled)
        scaled.recycle()
        val out = ByteArrayOutputStream()
        enhanced.compress(Bitmap.CompressFormat.JPEG, 90, out)
        enhanced.recycle()
        return out.toByteArray()
    }

    // Boost contrast so faint pencil/crayon lines in child drawings are clearly visible
    private fun enhanceContrast(src: Bitmap): Bitmap {
        val contrast = 2.5f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ))
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return out
    }

    fun cancel() {
        if (nativeHandle != 0L) nativeCancel(nativeHandle)
    }

    fun free() {
        if (nativeHandle != 0L) {
            nativeFree(nativeHandle)
            nativeHandle = 0L
        }
        loadedModelPath = ""
    }

    // ── JNI declarations ────────────────────────────────────────────────────

    private external fun nativeLoadModel(
        modelPath: String,
        clipModelPath: String,
        nCtx: Int,
        nThreads: Int,
    ): Long

    private external fun nativeDescribeImage(
        handle: Long,
        imageBytes: ByteArray,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        tokenCallback: (String) -> Unit,
    )

    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        tokenCallback: (String) -> Unit,
    )

    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    companion object {
        init {
            System.loadLibrary("inbyte-inference")
        }
    }
}
