package cn.heyread.app.ui

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import cn.heyread.app.ui.components.ArticleVideoPlayerDialog
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ArticleVideoPlaybackTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun productionPlayerRetriesDecodesAdvancesPausesSeeksAndReleasesSyntheticClip() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("article-motion.mp4").use { it.readBytes() }
        val sourceCall = AtomicInteger()
        val factory =
            DataSource.Factory {
                if (sourceCall.getAndIncrement() == 0) ByteArrayDataSource(byteArrayOf())
                else ByteArrayDataSource(bytes)
            }
        val playerRef = AtomicReference<ExoPlayer?>()
        val releaseCount = AtomicInteger()
        val visible = mutableStateOf(true)
        val owner = TestLifecycleOwner()
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                cn.heyread.app.ui.theme.HeyReadTheme("light") {
                    if (visible.value)
                        ArticleVideoPlayerDialog(
                            url = "https://fixture.invalid/article-motion.mp4",
                            onClose = { visible.value = false },
                            sourceFactory = factory,
                            onPlayerCreated = { playerRef.set(it) },
                            onPlayerReleased = { releaseCount.incrementAndGet() },
                        )
                }
            }
        }
        compose.runOnIdle {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        compose.waitUntil(15_000) {
            runCatching { compose.onNodeWithText("视频暂时无法播放").assertExists() }.isSuccess
        }
        saveTargetCacheScreenshot("v114-video-error.png")
        compose.onNodeWithTag("article-video-retry").performClick()
        compose.waitUntil(15_000) {
            var readyAndRendered = false
            compose.runOnIdle {
                val player = playerRef.get()
                player?.videoDecoderCounters?.ensureUpdated()
                readyAndRendered =
                    player?.playbackState == Player.STATE_READY &&
                        (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 1
            }
            readyAndRendered
        }
        var playerView: PlayerView? = null
        compose.runOnIdle {
            playerView =
                WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull(::findPlayerView)
            playerView?.showController()
        }
        assertNotNull("production PlayerView should be attached", playerView)
        var controlsVisible = false
        compose.runOnIdle {
            val controls = playerView!!
            val controller = controls.findViewById<View>(androidx.media3.ui.R.id.exo_controller)
            val seek = controls.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            val playPause =
                controls.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                    ?: controls.findViewById(androidx.media3.ui.R.id.exo_pause)
                    ?: controls.findViewById(androidx.media3.ui.R.id.exo_play)
            controlsVisible =
                controller?.isShown == true && seek?.isShown == true && playPause?.isShown == true
        }
        assertTrue("native play/pause and seek controls should be visible", controlsVisible)
        Thread.sleep(300)
        saveTargetCacheScreenshot("v114-video-decoded.png")
        val player = playerRef.get()!!
        var initialPosition = 0L
        compose.runOnIdle { initialPosition = player.currentPosition }
        Thread.sleep(700)
        var advancedPosition = 0L
        compose.runOnIdle { advancedPosition = player.currentPosition }
        assertTrue(
            "decoded video position should advance",
            advancedPosition > initialPosition + 200,
        )

        var pausedPosition = 0L
        compose.runOnIdle {
            player.pause()
            pausedPosition = player.currentPosition
        }
        Thread.sleep(450)
        var stillPausedAt = 0L
        compose.runOnIdle { stillPausedAt = player.currentPosition }
        assertTrue(
            "paused position should remain stable",
            kotlin.math.abs(stillPausedAt - pausedPosition) < 150,
        )

        compose.runOnIdle { player.seekTo(7000) }
        compose.waitUntil(5_000) {
            var position = 0L
            compose.runOnIdle { position = player.currentPosition }
            position >= 6800
        }
        compose.runOnIdle { player.play() }
        compose.waitUntil(5_000) {
            var playing = false
            compose.runOnIdle { playing = player.isPlaying }
            playing
        }
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
        compose.waitUntil(5_000) {
            var stopped = false
            compose.runOnIdle {
                stopped =
                    !player.isPlaying &&
                        !player.isLoading &&
                        player.playbackState == Player.STATE_IDLE
            }
            stopped
        }
        compose.runOnIdle { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        Thread.sleep(250)
        var resumedAutomatically = true
        compose.runOnIdle {
            resumedAutomatically =
                player.isPlaying || player.isLoading || player.playbackState != Player.STATE_IDLE
        }
        assertTrue("backgrounding stops playback without auto-resuming", !resumedAutomatically)
        val releasesBeforeClose = releaseCount.get()
        compose.onNodeWithTag("article-video-close").assertExists().performClick()
        compose.waitUntil(5_000) {
            var releasedPlayerPlaying = true
            compose.runOnIdle { releasedPlayerPlaying = player.isPlaying }
            releaseCount.get() > releasesBeforeClose && !releasedPlayerPlaying
        }
    }

    private class TestLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle
            get() = registry

        val registry = LifecycleRegistry(this)
    }

    private fun findPlayerView(root: View): PlayerView? {
        if (root is PlayerView) return root
        if (root !is ViewGroup) return null
        for (index in 0 until root.childCount) {
            findPlayerView(root.getChildAt(index))?.let {
                return it
            }
        }
        return null
    }
}
