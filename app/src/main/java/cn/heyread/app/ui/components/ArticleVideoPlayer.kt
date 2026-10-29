package cn.heyread.app.ui.components

import android.content.Context
import android.os.Build
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient

internal fun secureMediaClient(): OkHttpClient =
    OkHttpClient.Builder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(
            Interceptor { chain ->
                val url = chain.request().url
                if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) {
                    throw IOException("不安全的视频地址")
                }
                chain.proceed(chain.request())
            }
        )
        .build()

@androidx.annotation.OptIn(UnstableApi::class)
internal fun secureMediaSourceFactory(context: Context): DataSource.Factory =
    HttpsOnlyDataSourceFactory(OkHttpDataSource.Factory(secureMediaClient()))

internal fun isSafeMediaResourceUrl(raw: String): Boolean {
    val url = raw.toHttpUrlOrNull() ?: return false
    return url.isHttps && url.username.isEmpty() && url.password.isEmpty()
}

@androidx.annotation.OptIn(UnstableApi::class)
internal class HttpsOnlyDataSourceFactory(private val delegate: DataSource.Factory) :
    DataSource.Factory {
    override fun createDataSource(): DataSource = HttpsOnlyDataSource(delegate.createDataSource())
}

@androidx.annotation.OptIn(UnstableApi::class)
private class HttpsOnlyDataSource(private val delegate: DataSource) : DataSource {
    override fun addTransferListener(transferListener: TransferListener) =
        delegate.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!isSafeMediaResourceUrl(dataSpec.uri.toString())) throw IOException("仅支持安全的视频资源地址")
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length)

    override fun getUri() = delegate.uri

    override fun getResponseHeaders() = delegate.responseHeaders

    override fun close() = delegate.close()
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun ArticleVideoPlayerDialog(
    url: String,
    onClose: () -> Unit,
    onReloadArticle: () -> Unit = {},
    active: Boolean = true,
    sourceFactory: DataSource.Factory? = null,
    onPlayerCreated: (ExoPlayer?) -> Unit = {},
    onPlayerReleased: () -> Unit = {},
) {
    val context = LocalContext.current
    val readingFont = LocalGlobalReadingFont.current
    val customTypeface = LocalCustomReadingTypeface.current
    val playerTextTypeface = remember(readingFont, customTypeface) {
        readingFont.toAndroidTypeface(customTypeface)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.LaunchedEffect(active) { if (!active) onClose() }
    val parsed =
        remember(url) {
            url.toHttpUrlOrNull()?.takeIf {
                it.isHttps && it.username.isEmpty() && it.password.isEmpty()
            }
        }
    var attempt by remember(url) { mutableIntStateOf(0) }
    var playerError by remember(url) { mutableStateOf(false) }
    var pausedForLifecycle by remember(url) { mutableStateOf(false) }
    var resumePosition by remember(url) { mutableStateOf(0L) }
    val player =
        remember(url, attempt, parsed) {
            parsed?.let {
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(
                        DefaultMediaSourceFactory(context)
                            .setDataSourceFactory(
                                sourceFactory ?: secureMediaSourceFactory(context)
                            )
                    )
                    .build()
                    .apply {
                        setAudioAttributes(AudioAttributes.DEFAULT, true)
                        addListener(
                            object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) {
                                    playerError = true
                                }
                            }
                        )
                        setMediaItem(MediaItem.fromUri(it.toString()))
                        prepare()
                        playWhenReady = true
                    }
            }
        }
    val playerTextTypefaceState = remember(player) { PlayerTextTypefaceState() }
    var isPlaying by remember(player) { mutableStateOf(player?.isPlaying == true) }
    DisposableEffect(player, lifecycle) {
        onPlayerCreated(player)
        if (player == null) return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                resumePosition = player.currentPosition.coerceAtLeast(0L)
                player.stop()
                pausedForLifecycle = true
            }
        }
        val playerListener =
            object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }
            }
        player.addListener(playerListener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(playerListener)
            player?.pause()
            player?.release()
            onPlayerReleased()
        }
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (player == null || playerError) {
                Text(
                    if (parsed == null) "视频地址无效或已失效" else "视频暂时无法播放",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
                if (parsed != null && playerError)
                    TextButton(
                        onClick = {
                            playerError = false
                            attempt++
                        },
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(top = 56.dp)
                                .testTag("article-video-retry"),
                    ) {
                        Text("重试", color = Color.White)
                    }
                if (playerError)
                    TextButton(
                        onClick = {
                            onClose()
                            onReloadArticle()
                        },
                        modifier = Modifier.align(Alignment.Center).padding(top = 104.dp),
                    ) {
                        Text("重新加载正文", color = Color.White)
                    }
            } else {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            layoutParams = ViewGroup.LayoutParams(-1, -1)
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                            this.player = player
                            keepScreenOn = player.isPlaying
                            playerTextTypefaceState.apply(this, playerTextTypeface)
                        }
                    },
                    update = {
                        it.player = player
                        it.keepScreenOn = isPlaying
                        playerTextTypefaceState.apply(it, playerTextTypeface)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (pausedForLifecycle) {
                    TextButton(
                        onClick = {
                            player.seekTo(resumePosition)
                            player.prepare()
                            player.playWhenReady = true
                            pausedForLifecycle = false
                        },
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Text("继续播放", color = Color.White)
                    }
                }
            }
            IconButton(
                onClick = onClose,
                modifier =
                    Modifier.align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(12.dp)
                        .testTag("article-video-close"),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭视频", tint = Color.White)
            }
        }
    }
}

private class PlayerTextTypefaceState {
    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private var appliedFamily: Typeface? = null
    private var appliedView: WeakReference<View>? = null

    fun apply(view: View, family: Typeface) {
        if (appliedFamily === family && appliedView?.get() === view) return
        appliedFamily = family
        appliedView = WeakReference(view)
        applyToChildren(view, family)
    }

    private fun applyToChildren(view: View, family: Typeface) {
        if (view is TextView) {
            val original = originalTypefaces.getOrPut(view) { view.typeface }
            view.typeface =
                if (family === Typeface.DEFAULT) {
                    original
                } else {
                    val style = original?.style ?: Typeface.NORMAL
                    if (Build.VERSION.SDK_INT >= 28) {
                        Typeface.create(
                            family,
                            original?.weight ?: 400,
                            original?.isItalic ?: false,
                        )
                    } else {
                        Typeface.create(family, style)
                    }
                }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyToChildren(view.getChildAt(index), family)
        }
    }
}
