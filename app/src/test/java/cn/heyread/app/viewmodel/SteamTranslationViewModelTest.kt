package cn.heyread.app.viewmodel

import cn.heyread.app.data.model.Source
import cn.heyread.app.data.translation.SteamTranslationService
import cn.heyread.app.data.translation.SteamTranslationServiceFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SteamTranslationViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun successTranslatesEachParagraphAndKeepsOriginalUntilWholeArticleCompletes() =
        runTest(dispatcher) {
            val inputs = mutableListOf<String>()
            val service = FakeService { text ->
                inputs += text
                "译:$text"
            }
            val vm = SteamTranslationViewModel(SteamTranslationServiceFactory { service })
            val body = "First paragraph.\r\nSecond 😀 paragraph.\nThird paragraph."

            vm.bind(Source.STEAM, "STEAM:1", body, isActive = true)
            vm.translate()
            assertEquals(SteamTranslationPhase.PREPARING_MODEL, vm.state.value.phase)
            assertEquals(body, vm.state.value.sourceBody)
            runCurrent()

            assertEquals(SteamTranslationPhase.READY, vm.state.value.phase)
            assertEquals(3, vm.state.value.totalSegments)
            assertEquals(3, vm.state.value.completedSegments)
            assertTrue(inputs.none { '\n' in it || '\r' in it })
            assertEquals(
                "译:First paragraph.\r\n译:Second 😀 paragraph.\n译:Third paragraph.",
                vm.state.value.translatedBody,
            )
            assertTrue(vm.state.value.showingTranslation)
            assertEquals(1, service.closeCount)

            vm.showOriginal()
            assertFalse(vm.state.value.showingTranslation)
            vm.showTranslation()
            assertTrue(vm.state.value.showingTranslation)
        }

    @Test
    fun failureKeepsOriginalAndRetryCanSucceed() =
        runTest(dispatcher) {
            var created = 0
            val factory = SteamTranslationServiceFactory {
                created++
                if (created == 1) FakeService { error("offline") } else FakeService { "译:$it" }
            }
            val vm = SteamTranslationViewModel(factory)
            vm.bind(Source.STEAM, "STEAM:2", "Original text", isActive = true)

            vm.translate()
            runCurrent()

            assertEquals(SteamTranslationPhase.FAILED, vm.state.value.phase)
            assertEquals("Original text", vm.state.value.sourceBody)
            assertFalse(vm.state.value.showingTranslation)
            assertTrue(vm.state.value.error.orEmpty().contains("重试"))

            vm.translate()
            runCurrent()

            assertEquals(SteamTranslationPhase.READY, vm.state.value.phase)
            assertEquals("译:Original text", vm.state.value.translatedBody)
        }

    @Test
    fun switchingArticleCancelsOldWorkAndRejectsItsLateResult() =
        runTest(dispatcher) {
            val lateTranslation = CompletableDeferred<String>()
            var created = 0
            val firstService = FakeService {
                withContext(NonCancellable) { lateTranslation.await() }
            }
            val factory = SteamTranslationServiceFactory {
                created++
                if (created == 1) firstService else FakeService { "译:$it" }
            }
            val vm = SteamTranslationViewModel(factory)

            vm.bind(Source.STEAM, "STEAM:old", "old article", isActive = true)
            vm.translate()
            runCurrent()
            assertEquals(SteamTranslationPhase.TRANSLATING, vm.state.value.phase)

            vm.bind(Source.STEAM, "STEAM:new", "new article", isActive = true)
            assertEquals("STEAM:new", vm.state.value.storyKey)
            vm.translate()
            runCurrent()
            assertEquals("译:new article", vm.state.value.translatedBody)

            lateTranslation.complete("stale old translation")
            runCurrent()
            assertEquals("STEAM:new", vm.state.value.storyKey)
            assertEquals("译:new article", vm.state.value.translatedBody)
        }

    @Test
    fun leavingPageCancelsTranslationClosesServiceAndKeepsOriginalReadable() =
        runTest(dispatcher) {
            val waiting = CompletableDeferred<String>()
            val service = FakeService { withContext(NonCancellable) { waiting.await() } }
            val vm = SteamTranslationViewModel(SteamTranslationServiceFactory { service })
            val body = "Original remains available"
            vm.bind(Source.STEAM, "STEAM:3", body, isActive = true)
            vm.translate()
            runCurrent()
            assertEquals(SteamTranslationPhase.TRANSLATING, vm.state.value.phase)

            vm.bind(Source.STEAM, "STEAM:3", body, isActive = false)
            assertEquals(SteamTranslationPhase.IDLE, vm.state.value.phase)
            assertEquals(body, vm.state.value.sourceBody)
            assertEquals(1, service.closeCount)

            waiting.complete("late text")
            runCurrent()
            assertEquals(SteamTranslationPhase.IDLE, vm.state.value.phase)
            assertEquals(body, vm.state.value.sourceBody)
        }

    @Test
    fun modelPreparationTimeoutFailsCleanlyAndCanRetry() =
        runTest(dispatcher) {
            var created = 0
            val factory = SteamTranslationServiceFactory {
                created++
                if (created == 1) FakeService(prepareAction = { awaitCancellation() })
                else FakeService { "译:$it" }
            }
            val vm = SteamTranslationViewModel(factory)
            vm.bind(Source.STEAM, "STEAM:timeout", "Original", isActive = true)

            vm.translate()
            runCurrent()
            assertEquals(SteamTranslationPhase.PREPARING_MODEL, vm.state.value.phase)
            advanceTimeBy(180_001)
            runCurrent()

            assertEquals(SteamTranslationPhase.FAILED, vm.state.value.phase)
            assertEquals("Original", vm.state.value.sourceBody)
            vm.translate()
            runCurrent()
            assertEquals(SteamTranslationPhase.READY, vm.state.value.phase)
        }

    @Test
    fun blankTranslationResultFailsAndRetainsOriginal() =
        runTest(dispatcher) {
            val vm =
                SteamTranslationViewModel(SteamTranslationServiceFactory { FakeService { "  " } })
            vm.bind(Source.STEAM, "STEAM:blank", "Original paragraph", isActive = true)

            vm.translate()
            runCurrent()

            assertEquals(SteamTranslationPhase.FAILED, vm.state.value.phase)
            assertEquals("Original paragraph", vm.state.value.sourceBody)
            assertEquals(null, vm.state.value.translatedBody)
            assertFalse(vm.state.value.showingTranslation)
        }

    @Test
    fun serviceCancellationReturnsToIdleWithoutMarkingFailure() =
        runTest(dispatcher) {
            val vm =
                SteamTranslationViewModel(
                    SteamTranslationServiceFactory {
                        object : SteamTranslationService {
                            override suspend fun prepare() {
                                throw kotlinx.coroutines.CancellationException("SDK cancelled")
                            }

                            override suspend fun translate(text: String): String =
                                error("unreachable")

                            override fun close() = Unit
                        }
                    }
                )
            vm.bind(Source.STEAM, "STEAM:cancel", "Original", isActive = true)

            vm.translate()
            runCurrent()

            assertEquals(SteamTranslationPhase.IDLE, vm.state.value.phase)
            assertEquals("Original", vm.state.value.sourceBody)
            assertEquals(null, vm.state.value.error)
        }

    private class FakeService(
        private val prepareAction: suspend () -> Unit = {},
        private val translator: suspend (String) -> String = { error("Unexpected translation") },
    ) : SteamTranslationService {
        var closeCount = 0
            private set

        override suspend fun prepare() = prepareAction()

        override suspend fun translate(text: String): String = translator(text)

        override fun close() {
            closeCount++
        }
    }
}
