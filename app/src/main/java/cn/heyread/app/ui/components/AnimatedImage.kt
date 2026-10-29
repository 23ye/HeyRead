package cn.heyread.app.ui.components

import android.graphics.drawable.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter

/** Renders static and animated image sources with lifecycle-aware playback. */
@Composable
fun AnimatedImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    onState: ((AsyncImagePainter.State) -> Unit)? = null,
) {
  var state by remember(model) { mutableStateOf<AsyncImagePainter.State?>(null) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val paneContentActive = LocalPaneContentActive.current
  val windowInfo = LocalWindowInfo.current
  var visible by remember(model) { mutableStateOf(false) }
  var started by
      remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
      }
  val animatedDrawable =
      remember(state) {
        (state as? AsyncImagePainter.State.Success)?.result?.drawable as? Animatable
      }

  DisposableEffect(lifecycle) {
    val observer = LifecycleEventObserver { owner, _ ->
      started = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
    lifecycle.addObserver(observer)
    onDispose { lifecycle.removeObserver(observer) }
  }
  LaunchedEffect(started, visible, animatedDrawable, paneContentActive) {
    if (started && visible && paneContentActive) animatedDrawable?.start()
    else animatedDrawable?.stop()
  }
  DisposableEffect(animatedDrawable) { onDispose { animatedDrawable?.stop() } }

  AsyncImage(
      model = model,
      contentDescription = contentDescription,
      modifier =
          modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            val container = windowInfo.containerSize
            visible =
                coordinates.isAttached &&
                    bounds.width > 0f &&
                    bounds.height > 0f &&
                    container.width > 0 &&
                    container.height > 0 &&
                    bounds.left < container.width &&
                    bounds.right > 0f &&
                    bounds.top < container.height &&
                    bounds.bottom > 0f
          },
      contentScale = contentScale,
      onState = {
        state = it
        onState?.invoke(it)
      },
  )
}
