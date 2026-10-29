package cn.heyread.app.ui.motion

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.io.Closeable
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap

/** A short-lived platform hint; Compose's frame clock remains the source of animation time. */
internal class MotionFrameRateHintController(
    view: View,
    owner: LifecycleOwner,
    window: Window?,
) {
  private val api35ViewReference = WeakReference(view)
  private val windowReference = WeakReference(window)
  private val lifecycleReference = WeakReference(owner)
  private val viewClients = IdentityHashMap<View, ViewClient>()
  private var registrationCount = 0
  private var attachedViewCount = 0
  private var resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
  private var applied = false
  private var originalRequestedFrameRate: Float? = null
  private var originalWindowRefreshRate: Float? = null
  private var windowModeIdAtAcquire: Int? = null
  private var chosenWindowRefreshRate: Float? = null
  private val leaseGate = MotionFrameRateLeaseGate(::applyPlatformHint, ::restorePlatformHint)

  private inner class ViewClient(val view: View) {
    var references = 0
    var attached = view.isAttachedToWindow
    val listener =
        object : View.OnAttachStateChangeListener {
          override fun onViewAttachedToWindow(v: View) {
            synchronized(this@MotionFrameRateHintController) { updateAttachment(v, true) }
          }

          override fun onViewDetachedFromWindow(v: View) {
            synchronized(this@MotionFrameRateHintController) { updateAttachment(v, false) }
          }
        }
  }

  private val lifecycleObserver = LifecycleEventObserver { _, event ->
    synchronized(this) {
      when (event) {
        Lifecycle.Event.ON_RESUME -> resumed = true
        Lifecycle.Event.ON_PAUSE,
        Lifecycle.Event.ON_STOP,
        Lifecycle.Event.ON_DESTROY -> resumed = false
        else -> Unit
      }
      updateAvailability()
    }
  }

  @Synchronized
  fun retain(view: View): Closeable {
    val owner = lifecycleReference.get() ?: return Closeable {}
    registrationCount++
    if (registrationCount == 1) {
      owner.lifecycle.addObserver(lifecycleObserver)
      resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }
    val client =
        viewClients[view]
            ?: ViewClient(view).also {
              viewClients[view] = it
              if (it.attached) attachedViewCount++
              view.addOnAttachStateChangeListener(it.listener)
            }
    client.references++
    updateAvailability()
    var closed = false
    return Closeable {
      synchronized(this) {
        if (closed) return@Closeable
        closed = true
        val current = viewClients[view]
        if (current != null) {
          current.references--
          if (current.references <= 0) {
            view.removeOnAttachStateChangeListener(current.listener)
            if (current.attached) attachedViewCount--
            viewClients.remove(view)
          }
        }
        registrationCount = (registrationCount - 1).coerceAtLeast(0)
        if (registrationCount == 0) {
          owner.lifecycle.removeObserver(lifecycleObserver)
          viewClients.values.forEach { clientState ->
            clientState.view.removeOnAttachStateChangeListener(clientState.listener)
          }
          viewClients.clear()
          attachedViewCount = 0
          leaseGate.clear()
        } else {
          updateAvailability()
        }
      }
    }
  }

  @Synchronized fun acquire(): Closeable = leaseGate.acquire()

  @Synchronized
  private fun updateAttachment(view: View, isAttached: Boolean) {
    val client = viewClients[view] ?: return
    if (client.attached == isAttached) return
    client.attached = isAttached
    attachedViewCount += if (isAttached) 1 else -1
    updateAvailability()
  }

  private fun updateAvailability() {
    leaseGate.setAvailable(registrationCount > 0 && attachedViewCount > 0 && resumed)
  }

  private fun applyPlatformHint(): Boolean {
    if (applied) return true
    if (Build.VERSION.SDK_INT >= 35) {
      val view = api35ViewReference.get() ?: return false
      return runCatching {
            originalRequestedFrameRate = view.requestedFrameRate
            view.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
            applied = true
            true
          }
          .getOrDefault(false)
    }

    val window = windowReference.get() ?: return false
    return runCatching {
          val attributes = window.attributes
          // A nonzero display-mode or refresh-rate preference belongs to another owner.
          if (attributes.preferredDisplayModeId != 0 || attributes.preferredRefreshRate != 0f)
              return@runCatching false
          val displayView =
              viewClients.values.firstOrNull { it.attached }?.view ?: return@runCatching false
          val display = displayView.display ?: return@runCatching false
          val currentMode = display.mode ?: return@runCatching false
          val candidate =
              chooseWindowRefreshRate(
                  display.supportedModes.map {
                    DisplayModeRate(it.physicalWidth, it.physicalHeight, it.refreshRate)
                  },
                  currentMode.physicalWidth,
                  currentMode.physicalHeight,
              ) ?: return@runCatching false
          if (candidate <= 60.5f) return@runCatching false
          originalWindowRefreshRate = attributes.preferredRefreshRate
          windowModeIdAtAcquire = attributes.preferredDisplayModeId
          chosenWindowRefreshRate = candidate
          attributes.preferredRefreshRate = candidate
          window.attributes = attributes
          applied = true
          true
        }
        .getOrDefault(false)
  }

  private fun restorePlatformHint() {
    if (!applied) return
    val view = api35ViewReference.get()
    if (view != null && Build.VERSION.SDK_INT >= 35) {
      runCatching {
        if (view.requestedFrameRate == View.REQUESTED_FRAME_RATE_CATEGORY_HIGH) {
          originalRequestedFrameRate?.let { view.requestedFrameRate = it }
        }
      }
    } else {
      val window = windowReference.get()
      if (window != null) {
        runCatching {
          val attributes = window.attributes
          if (attributes.preferredDisplayModeId == windowModeIdAtAcquire &&
              attributes.preferredRefreshRate == chosenWindowRefreshRate) {
            attributes.preferredRefreshRate = originalWindowRefreshRate ?: 0f
            window.attributes = attributes
          }
        }
      }
    }
    applied = false
    originalRequestedFrameRate = null
    originalWindowRefreshRate = null
    windowModeIdAtAcquire = null
    chosenWindowRefreshRate = null
  }

  companion object {
    private val controllers = WeakHashMap<Any, WeakReference<MotionFrameRateHintController>>()

    @Synchronized
    fun forView(view: View, owner: LifecycleOwner): MotionFrameRateHintController {
      val window = findWindow(view)
      val key: Any = if (Build.VERSION.SDK_INT >= 35 || window == null) view else window
      val existing = controllers[key]?.get()
      if (existing != null) return existing
      return MotionFrameRateHintController(view, owner, window).also {
        controllers[key] = WeakReference(it)
      }
    }
  }
}

/** Reference-counted platform hint state, separated from Android for cancellation tests. */
internal class MotionFrameRateLeaseGate(
    private val apply: () -> Boolean,
    private val restore: () -> Unit,
) {
  private var activeLeases = 0
  private var available = false
  private var applied = false
  private var generation = 0L

  @Synchronized
  fun setAvailable(value: Boolean) {
    available = value
    reconcile()
  }

  @Synchronized
  fun acquire(): Closeable {
    activeLeases++
    val leaseGeneration = generation
    reconcile()
    var closed = false
    return Closeable {
      synchronized(this) {
        if (closed) return@Closeable
        closed = true
        if (leaseGeneration != generation) return@Closeable
        activeLeases = (activeLeases - 1).coerceAtLeast(0)
        reconcile()
      }
    }
  }

  @Synchronized
  fun clear() {
    generation++
    activeLeases = 0
    available = false
    if (applied) {
      runCatching(restore)
      applied = false
    }
  }

  private fun reconcile() {
    val shouldApply = available && activeLeases > 0
    if (shouldApply && !applied) {
      applied = runCatching(apply).getOrDefault(false)
    } else if (!shouldApply && applied) {
      runCatching(restore)
      applied = false
    }
  }
}

internal data class DisplayModeRate(val width: Int, val height: Int, val refreshRate: Float)

internal fun chooseWindowRefreshRate(
    modes: List<DisplayModeRate>,
    currentWidth: Int,
    currentHeight: Int,
): Float? =
    modes
        .asSequence()
        .filter { it.width == currentWidth && it.height == currentHeight }
        .map { it.refreshRate }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull()

@Composable
internal fun rememberMotionFrameRateHintController(
    enabled: Boolean,
): MotionFrameRateHintController? {
  if (!enabled) return null
  val view = LocalView.current
  val lifecycleOwner =
      view.findViewTreeLifecycleOwner() ?: androidx.lifecycle.compose.LocalLifecycleOwner.current
  val controller =
      remember(view, lifecycleOwner) { MotionFrameRateHintController.forView(view, lifecycleOwner) }
  DisposableEffect(controller, view) {
    val registration = controller.retain(view)
    onDispose { registration.close() }
  }
  return controller
}

/** Call from the animation's rendered window; AlertDialog invokes its slots inside that window. */
@Composable
internal fun MotionFrameRateHint(active: Boolean) {
  val controller = rememberMotionFrameRateHintController(active)
  DisposableEffect(controller, active) {
    val lease = controller?.acquire()
    onDispose { lease?.close() }
  }
}

private fun findWindow(view: View): Window? {
  var parent = view.parent
  while (parent != null) {
    (parent as? DialogWindowProvider)?.let {
      return it.window
    }
    parent = parent.parent
  }
  var context: Context? = view.context
  while (context != null) {
    when (context) {
      is Activity -> return context.window
      is ContextWrapper -> context = context.baseContext
      else -> return null
    }
  }
  return null
}
