package com.inbyte.imagedescriber.inference

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class TranslationHelper @Inject constructor() {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.BULGARIAN)
            .build()
    )

    private var modelReady = false

    suspend fun ensureModelDownloaded(): Boolean = suspendCancellableCoroutine { cont ->
        if (modelReady) { cont.resume(true); return@suspendCancellableCoroutine }
        translator.downloadModelIfNeeded()
            .addOnSuccessListener { modelReady = true; cont.resume(true) }
            .addOnFailureListener { cont.resume(false) }
    }

    suspend fun translate(text: String): String = suspendCancellableCoroutine { cont ->
        translator.translate(text)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun close() = translator.close()
}
