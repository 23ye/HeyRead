package cn.heyread.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.translation.MlKitSteamTranslationServiceFactory
import cn.heyread.app.data.translation.SteamTranslationService
import cn.heyread.app.data.translation.SteamTranslationServiceFactory
import cn.heyread.app.data.translation.TranslationText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class SteamTranslationPhase {
    IDLE,
    PREPARING_MODEL,
    TRANSLATING,
    READY,
    FAILED,
}

data class SteamTranslationState(
    val storyKey: String = "",
    val sourceBody: String = "",
    val phase: SteamTranslationPhase = SteamTranslationPhase.IDLE,
    val translatedBody: String? = null,
    val showingTranslation: Boolean = false,
    val completedSegments: Int = 0,
    val totalSegments: Int = 0,
    val error: String? = null,
) {
    val isBusy: Boolean
        get() =
            phase == SteamTranslationPhase.PREPARING_MODEL ||
                phase == SteamTranslationPhase.TRANSLATING
}

class SteamTranslationViewModel(
    private val serviceFactory: SteamTranslationServiceFactory = MlKitSteamTranslationServiceFactory
) : ViewModel() {
    private val mutableState = MutableStateFlow(SteamTranslationState())
    val state = mutableState.asStateFlow()

    private var currentStoryKey: String? = null
    private var currentBody: String? = null
    private var requestGeneration = 0L
    private var translationJob: Job? = null
    private var activeService: SteamTranslationService? = null

    fun bind(source: Source, storyKey: String, body: String, isActive: Boolean) {
        if (!isActive) {
            deactivate()
            return
        }
        if (source != Source.STEAM || body.isBlank()) {
            resetTarget()
            return
        }
        if (storyKey == currentStoryKey && body == currentBody) return

        stopTranslation()
        currentStoryKey = storyKey
        currentBody = body
        mutableState.value = SteamTranslationState(storyKey = storyKey, sourceBody = body)
    }

    fun translate() {
        val storyKey = currentStoryKey ?: return
        val body = currentBody?.takeIf(String::isNotBlank) ?: return
        if (mutableState.value.isBusy) return

        stopTranslation()
        val generation = requestGeneration
        val fragments = TranslationText.split(body)
        val translatable = fragments.filter { it.text.isNotEmpty() }
        if (translatable.isEmpty()) return
        mutableState.value =
            mutableState.value.copy(
                phase = SteamTranslationPhase.PREPARING_MODEL,
                translatedBody = null,
                showingTranslation = false,
                completedSegments = 0,
                totalSegments = translatable.size,
                error = null,
            )

        translationJob =
            viewModelScope.launch {
                var service: SteamTranslationService? = null
                try {
                    withTimeout(TRANSLATION_TIMEOUT_MILLIS) {
                        val createdService = serviceFactory.create()
                        service = createdService
                        activeService = createdService
                        createdService.prepare()
                        if (!isCurrent(generation, storyKey, body)) return@withTimeout

                        mutableState.value =
                            mutableState.value.copy(phase = SteamTranslationPhase.TRANSLATING)
                        var completed = 0
                        val translated = StringBuilder()
                        for (fragment in fragments) {
                            currentCoroutineContext().ensureActive()
                            if (!isCurrent(generation, storyKey, body)) return@withTimeout
                            translated.append(fragment.prefix)
                            if (fragment.text.isNotEmpty()) {
                                val result = createdService.translate(fragment.text)
                                currentCoroutineContext().ensureActive()
                                if (!isCurrent(generation, storyKey, body)) return@withTimeout
                                if (result.isBlank()) error("翻译结果为空")
                                translated.append(result)
                                completed++
                                mutableState.value =
                                    mutableState.value.copy(completedSegments = completed)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (!isCurrent(generation, storyKey, body)) return@withTimeout
                        if (isCurrent(generation, storyKey, body))
                            mutableState.value =
                                mutableState.value.copy(
                                    phase = SteamTranslationPhase.READY,
                                    translatedBody = translated.toString(),
                                    showingTranslation = true,
                                    completedSegments = translatable.size,
                                    error = null,
                                )
                    }
                } catch (timeout: TimeoutCancellationException) {
                    if (isCurrent(generation, storyKey, body))
                        mutableState.value =
                            mutableState.value.copy(
                                phase = SteamTranslationPhase.FAILED,
                                error = "翻译等待超时，请检查网络后重试。原文仍可阅读。",
                            )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (isCurrent(generation, storyKey, body))
                        mutableState.value =
                            mutableState.value.copy(
                                phase = SteamTranslationPhase.FAILED,
                                error = "翻译暂时失败，请检查网络后重试。原文仍可阅读。",
                            )
                } finally {
                    runCatching { service?.close() }
                    if (activeService === service) activeService = null
                    if (isCurrent(generation, storyKey, body) && mutableState.value.isBusy) {
                        mutableState.value =
                            mutableState.value.copy(
                                phase = SteamTranslationPhase.IDLE,
                                translatedBody = null,
                                showingTranslation = false,
                                completedSegments = 0,
                                totalSegments = 0,
                                error = null,
                            )
                    }
                }
            }
    }

    fun cancel() {
        stopTranslation()
        val current = mutableState.value
        if (current.isBusy)
            mutableState.value =
                current.copy(
                    phase = SteamTranslationPhase.IDLE,
                    translatedBody = null,
                    showingTranslation = false,
                    completedSegments = 0,
                    totalSegments = 0,
                    error = null,
                )
    }

    fun showOriginal() {
        val current = mutableState.value
        if (current.phase == SteamTranslationPhase.READY)
            mutableState.value = current.copy(showingTranslation = false)
    }

    fun showTranslation() {
        val current = mutableState.value
        if (current.phase == SteamTranslationPhase.READY)
            mutableState.value = current.copy(showingTranslation = true)
    }

    fun deactivate() {
        val current = mutableState.value
        if (current.isBusy) {
            stopTranslation()
            mutableState.value =
                current.copy(
                    phase = SteamTranslationPhase.IDLE,
                    translatedBody = null,
                    showingTranslation = false,
                    completedSegments = 0,
                    totalSegments = 0,
                    error = null,
                )
        }
    }

    private fun resetTarget() {
        stopTranslation()
        currentStoryKey = null
        currentBody = null
        mutableState.value = SteamTranslationState()
    }

    private fun stopTranslation() {
        requestGeneration++
        translationJob?.cancel()
        translationJob = null
        runCatching { activeService?.close() }
        activeService = null
    }

    private fun isCurrent(generation: Long, storyKey: String, body: String): Boolean =
        generation == requestGeneration && currentStoryKey == storyKey && currentBody == body

    override fun onCleared() {
        stopTranslation()
        super.onCleared()
    }

    class Factory(
        private val serviceFactory: SteamTranslationServiceFactory =
            MlKitSteamTranslationServiceFactory
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SteamTranslationViewModel(serviceFactory) as T
    }

    private companion object {
        const val TRANSLATION_TIMEOUT_MILLIS = 180_000L
    }
}
