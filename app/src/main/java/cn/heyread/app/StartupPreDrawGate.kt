package cn.heyread.app

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect

/** Holds the Activity's first draw until the appearance-ready Compose content commits. */
internal class StartupPreDrawGate(private val view: View) {
  private var contentCommitted = false
  private var disposed = false
  private val listener =
    ViewTreeObserver.OnPreDrawListener {
      if (!contentCommitted && !disposed) return@OnPreDrawListener false
      removeListener()
      true
    }

  init {
    view.viewTreeObserver.addOnPreDrawListener(listener)
  }

  fun markContentCommitted() {
    contentCommitted = true
    view.invalidate()
  }

  fun dispose() {
    disposed = true
    removeListener()
  }

  private fun removeListener() {
    val observer = view.viewTreeObserver
    if (observer.isAlive) observer.removeOnPreDrawListener(listener)
  }
}

@Composable
internal fun StartupAppearanceContent(
  ready: Boolean,
  gate: StartupPreDrawGate,
  content: @Composable () -> Unit,
) {
  if (ready) {
    content()
    SideEffect { gate.markContentCommitted() }
  }
}
