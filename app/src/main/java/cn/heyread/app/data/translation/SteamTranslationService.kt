package cn.heyread.app.data.translation

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

interface SteamTranslationService : AutoCloseable {
    suspend fun prepare()

    suspend fun translate(text: String): String
}

fun interface SteamTranslationServiceFactory {
    fun create(): SteamTranslationService
}

object MlKitSteamTranslationServiceFactory : SteamTranslationServiceFactory {
    override fun create(): SteamTranslationService {
        val options =
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build()
        return MlKitSteamTranslationService(Translation.getClient(options))
    }
}

private class MlKitSteamTranslationService(private val translator: Translator) :
    SteamTranslationService {
    override suspend fun prepare() {
        translator.downloadModelIfNeeded(DownloadConditions.Builder().requireWifi().build()).await()
    }

    override suspend fun translate(text: String): String = translator.translate(text).await()

    override fun close() = translator.close()
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener { if (continuation.isActive) continuation.cancel() }
    }
