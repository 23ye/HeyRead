package cn.heyread.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import cn.heyread.app.ui.components.LocalPaneContentActive
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class CoverCoordinateTransform(
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val pivotInRoot: Offset = Offset.Zero,
)

internal val LocalCoverCoordinateTransforms =
    compositionLocalOf<androidx.compose.runtime.State<List<CoverCoordinateTransform>>> {
      androidx.compose.runtime.mutableStateOf(emptyList())
    }

@Composable
internal fun Modifier.sharedCoverEndpoint(
    identity: String?,
    originId: String?,
    entryId: String?,
    isDetail: Boolean,
    painter: Painter?,
    animated: Boolean,
    failed: Boolean,
    enabled: Boolean,
    coordinator: SharedCoverCoordinator?,
): Modifier {
  if (!enabled || identity == null || originId == null || entryId == null || coordinator == null)
      return this
  val owner = remember(identity, originId, entryId, isDetail, coordinator) { Any() }
  var coordinates by remember(owner) { mutableStateOf<LayoutCoordinates?>(null) }
  val placementRevision = remember(owner) { AtomicLong(0L) }
  val currentCoordinates = coordinates
  val transforms = LocalCoverCoordinateTransforms.current
  val paneContentActive = LocalPaneContentActive.current
  val visible = currentCoordinates?.takeIf { it.isAttached }?.let(::isFullyVisible) == true
  SideEffect {
    currentCoordinates
        ?.takeIf { it.isAttached }
        ?.let { coordinates ->
          val canonicalRect = settledBoundsInRoot(coordinates, transforms.value)
          val visualRect = fullBoundsInRoot(coordinates)
          coordinator.register(
              SharedCoverRegistration(
                  identity = identity,
                  originId = originId,
                  entryId = entryId,
                  isDetail = isDetail,
                  rect = canonicalRect,
                  painter = painter,
                  animated = animated,
                  failed = failed,
                  rectProvider = {
                    coordinates
                        ?.takeIf { it.isAttached }
                        ?.let { settledBoundsInRoot(it, transforms.value) } ?: canonicalRect
                  },
                  visualRectProvider = {
                    coordinates?.takeIf { it.isAttached }?.let(::fullBoundsInRoot) ?: visualRect
                  },
                  attachedProvider = { coordinates?.isAttached == true },
                  visibleProvider = {
                    coordinates?.takeIf { it.isAttached }?.let(::isFullyVisible) == true
                  },
                  canonicalVisibleProvider = {
                    coordinates
                        ?.takeIf { it.isAttached }
                        ?.let { target ->
                          val canonical = settledBoundsInRoot(target, transforms.value)
                          val clipped = target.boundsInRoot()
                          val rootWidth = target.findRootCoordinates().size.width.toFloat()
                          val tolerance = 1f
                          canonical.hasArea &&
                              canonical.left >= -tolerance &&
                              canonical.right <= rootWidth + tolerance &&
                              clipped.top <= canonical.top + tolerance &&
                              clipped.bottom >= canonical.bottom - tolerance
                        } == true
                  },
                  placementRevisionProvider = { placementRevision.get() },
                  paneActiveProvider = { paneContentActive },
                  visible = visible,
                  owner = owner,
              ))
        }
  }
  DisposableEffect(identity, originId, entryId, isDetail, coordinator, owner) {
    onDispose { coordinator.remove(identity, originId, entryId, isDetail, owner) }
  }
  return onGloballyPositioned {
    coordinates = it
    coordinator.recordEndpointPlacement(
        identity = identity,
        originId = originId,
        entryId = entryId,
        isDetail = isDetail,
        owner = owner,
        revision = placementRevision,
    )
  }
}

private fun fullBoundsInRoot(coordinates: LayoutCoordinates): Rect =
    coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false)

private fun isFullyVisible(coordinates: LayoutCoordinates): Boolean {
  if (!coordinates.isAttached) return false
  val full = fullBoundsInRoot(coordinates)
  val visible = coordinates.boundsInRoot()
  val tolerance = 1f
  return visible.hasArea &&
      visible.left <= full.left + tolerance &&
      visible.top <= full.top + tolerance &&
      visible.right >= full.right - tolerance &&
      visible.bottom >= full.bottom - tolerance
}

private fun settledBoundsInRoot(
    coordinates: LayoutCoordinates,
    transforms: List<CoverCoordinateTransform>,
): Rect {
  var rect = fullBoundsInRoot(coordinates)
  transforms.asReversed().forEach { transform -> rect = invertTransform(rect, transform) }
  return rect
}

private fun invertTransform(rect: Rect, transform: CoverCoordinateTransform): Rect {
  val scaleX = transform.scaleX.takeIf { it.isFinite() && it > 0f } ?: 1f
  val scaleY = transform.scaleY.takeIf { it.isFinite() && it > 0f } ?: 1f
  val left = (rect.left - transform.pivotInRoot.x) / scaleX + transform.pivotInRoot.x
  val right = (rect.right - transform.pivotInRoot.x) / scaleX + transform.pivotInRoot.x
  val top = (rect.top - transform.pivotInRoot.y) / scaleY + transform.pivotInRoot.y
  val bottom = (rect.bottom - transform.pivotInRoot.y) / scaleY + transform.pivotInRoot.y
  return Rect(
      left - transform.translationX,
      top - transform.translationY,
      right - transform.translationX,
      bottom - transform.translationY,
  )
}

internal val LocalSharedCoverCoordinator = compositionLocalOf<SharedCoverCoordinator?> { null }
internal val LocalSharedCoverEntryId = compositionLocalOf<String?> { null }
internal val LocalSharedCoverOriginId = compositionLocalOf<String?> { null }
internal val LocalSharedCoverIsDetail = compositionLocalOf { false }

internal enum class SharedCoverDirection {
  Forward,
  Return,
}

internal data class SharedCoverRegistration(
    val identity: String,
    val originId: String,
    val entryId: String,
    val isDetail: Boolean,
    val rect: Rect,
    val painter: Painter?,
    val animated: Boolean,
    val failed: Boolean,
    val rectProvider: (() -> Rect)? = null,
    val visualRectProvider: (() -> Rect)? = rectProvider,
    val visible: Boolean = true,
    val owner: Any = Any(),
    val attachedProvider: (() -> Boolean)? = null,
    val visibleProvider: (() -> Boolean)? = null,
    val canonicalVisibleProvider: (() -> Boolean)? = null,
    val placementRevisionProvider: (() -> Long)? = null,
    val paneActiveProvider: (() -> Boolean)? = null,
)

internal data class SharedCoverTransaction(
    val generation: Long,
    val identity: String,
    val originId: String,
    val sourceEntryId: String,
    val sourceRect: Rect,
    val painter: Painter?,
    val sourceOwner: Any? = null,
    val originRect: Rect? = null,
    val targetEntryId: String? = null,
    val targetRect: Rect? = null,
    val targetReady: Boolean = false,
    val direction: SharedCoverDirection = SharedCoverDirection.Forward,
    val pendingEntryId: String? = null,
    val pendingTargetOwner: Any? = null,
    val pendingTargetPlacementRevision: Long? = null,
    val pendingTargetPaneWasActive: Boolean = true,
)

private data class SharedCoverReturnAnchor(
    val identity: String,
    val originId: String,
    val originEntryId: String,
    val detailEntryId: String,
    val mediaIdentity: String,
    val rect: Rect,
)

private val Rect.hasArea: Boolean
  get() = width > 0f && height > 0f && width.isFinite() && height.isFinite()

@Stable
internal class SharedCoverCoordinator {
  private val registrations = mutableStateMapOf<String, SharedCoverRegistration>()
  private val returnAnchors = mutableStateMapOf<String, SharedCoverReturnAnchor>()
  var transaction by mutableStateOf<SharedCoverTransaction?>(null)
    private set

  var hostOrigin by mutableStateOf(Offset.Zero)
    private set

  private var nextGeneration = 0L
  private var currentEntryIds: Set<String> = emptySet()
  private var focusedEntryId: String? = null

  fun updateHostOrigin(origin: Offset) {
    hostOrigin = origin
  }

  fun bindExpectedTarget(entryId: String?) {
    val active = transaction ?: return
    if (active.direction != SharedCoverDirection.Forward ||
        entryId.isNullOrBlank() ||
        entryId == active.sourceEntryId)
        return
    transaction = active.copy(pendingEntryId = entryId)
  }

  fun setCurrentEntry(entryId: String?) {
    setCurrentEntries(entryId?.let(::setOf).orEmpty(), entryId)
  }

  fun setCurrentEntries(entryIds: Set<String>, focusedEntryId: String? = entryIds.lastOrNull()) {
    val previousFocus = this.focusedEntryId
    val current = entryIds.filter(String::isNotBlank).toSet()
    currentEntryIds = current
    this.focusedEntryId = focusedEntryId?.takeIf(String::isNotBlank)
    val active = transaction
    if (active != null) {
      val allowed = setOfNotNull(active.sourceEntryId, active.targetEntryId)
      val previousTargetFocus = active.targetEntryId ?: active.pendingEntryId
      if (active.direction == SharedCoverDirection.Forward &&
          this.focusedEntryId == active.sourceEntryId &&
          previousTargetFocus != null &&
          previousFocus == previousTargetFocus) {
        // Back reached the source before this forward transaction could be replaced
        // by a paired return. Do not leave the forward overlay floating during pop.
        cancel(clearRelatedAnchor = true)
        return
      }
      val pendingForwardTarget =
          active.direction == SharedCoverDirection.Forward && active.targetEntryId == null
      val focusedCandidate = this.focusedEntryId?.takeUnless { it == active.sourceEntryId }
      if (pendingForwardTarget && focusedCandidate != null) {
        val pending = active.pendingEntryId
        if (pending == null) {
          val targetEntryId = focusedCandidate
          transaction = active.copy(pendingEntryId = targetEntryId)
          active.originRect
              ?.takeIf { it.hasArea }
              ?.let { rect ->
                returnAnchors[targetEntryId] =
                    SharedCoverReturnAnchor(
                        identity = active.identity,
                        originId = active.originId,
                        originEntryId = active.sourceEntryId,
                        detailEntryId = targetEntryId,
                        mediaIdentity = active.identity,
                        rect = rect,
                    )
              }
        } else if (focusedCandidate != pending) cancel(clearRelatedAnchor = true)
      } else if (this.focusedEntryId != null &&
          this.focusedEntryId !in allowed &&
          !pendingForwardTarget) {
        cancel(clearRelatedAnchor = true)
      } else if (active.targetEntryId == null &&
          this.focusedEntryId != active.sourceEntryId &&
          previousFocus == active.sourceEntryId) {
        cancel(clearRelatedAnchor = true)
      }
      return
    }
    returnAnchors.entries.removeAll { (_, anchor) ->
      this.focusedEntryId != null &&
          ((anchor.detailEntryId != this.focusedEntryId &&
              anchor.originEntryId != this.focusedEntryId) ||
              (previousFocus == anchor.detailEntryId &&
                  this.focusedEntryId == anchor.originEntryId))
    }
  }

  fun register(registration: SharedCoverRegistration) {
    registrations[registration.key()] = registration
    if (registration.isDetail) {
      returnAnchors.entries.removeAll {
        it.key == registration.entryId && it.value.identity != registration.identity
      }
    }
    val active = transaction ?: return
    if (active.direction == SharedCoverDirection.Return &&
        !registration.isDetail &&
        registration.entryId == active.targetEntryId &&
        registration.originId == active.originId &&
        registration.identity != active.identity &&
        sameStoryCoverPost(registration.identity, active.identity, active.originId)) {
      cancel(clearRelatedAnchor = true)
      return
    }
    if (active.identity != registration.identity || active.originId != registration.originId) return
    val isForwardSource =
        active.direction == SharedCoverDirection.Forward &&
            !registration.isDetail &&
            registration.entryId == active.sourceEntryId &&
            registration.owner === active.sourceOwner
    val isForwardTarget =
        active.direction == SharedCoverDirection.Forward &&
            registration.isDetail &&
            registration.entryId != active.sourceEntryId &&
            (registration.entryId == active.pendingEntryId ||
                (active.pendingEntryId == null && focusedEntryId == registration.entryId))
    val isReturnTarget =
        active.direction == SharedCoverDirection.Return &&
            !registration.isDetail &&
            registration.entryId == active.targetEntryId
    if (!isForwardSource && !isForwardTarget && !isReturnTarget) return
    if (registration.failed || registration.animated) {
      cancel(clearRelatedAnchor = true)
      return
    }
    if (!registration.isDetail &&
        active.direction == SharedCoverDirection.Forward &&
        registration.entryId == active.sourceEntryId &&
        registration.painter != null &&
        !registration.animated &&
        !registration.failed &&
        registration.owner === active.sourceOwner) {
      // begin() already validated and froze the visible source geometry. A cold Coil
      // success can arrive after the page has started moving offscreen; only the same
      // still-attached endpoint may supply its Painter, never its new coordinates.
      if (registration.attached()) {
        transaction = active.copy(painter = registration.painter)
      }
      return
    }
    if (!registration.isDetail &&
        active.direction == SharedCoverDirection.Return &&
        registration.entryId == active.targetEntryId) {
      if (!isFreshPendingTarget(active, registration)) return
      val currentRect = registration.rectProvider?.invoke() ?: registration.rect
      if (!registration.attached() || !registration.canonicallyVisible() || !currentRect.hasArea) {
        if (active.targetReady) cancel(clearRelatedAnchor = true)
        return
      }
      if (!active.targetReady) {
        transaction = active.copy(targetRect = currentRect, targetReady = true)
      }
      return
    }
    if (registration.isDetail && active.direction == SharedCoverDirection.Forward) {
      if (focusedEntryId != null &&
          focusedEntryId != active.sourceEntryId &&
          focusedEntryId != registration.entryId) {
        cancel(clearRelatedAnchor = true)
        return
      }
      if (!registration.attached() || !registration.rect.hasArea) return
      val resolvedOrigin = active.originRegistration()
      val anchorRect = active.originRect ?: resolvedOrigin?.rect ?: active.sourceRect
      transaction =
          active.copy(
              targetEntryId = registration.entryId,
              targetRect = active.targetRect ?: registration.rect,
              targetReady = active.targetReady || registration.rect.hasArea,
          )
      returnAnchors[registration.entryId] =
          SharedCoverReturnAnchor(
              identity = active.identity,
              originId = active.originId,
              originEntryId = active.sourceEntryId,
              detailEntryId = registration.entryId,
              mediaIdentity = active.identity,
              rect = anchorRect,
          )
    }
  }

  private fun SharedCoverTransaction.originRegistration(): SharedCoverRegistration? =
      registrations.values.firstOrNull {
        it.identity == identity &&
            it.originId == originId &&
            it.entryId == sourceEntryId &&
            !it.isDetail
      }

  fun remove(
      identity: String?,
      originId: String?,
      entryId: String,
      isDetail: Boolean,
      owner: Any? = null,
  ) {
    if (identity == null || originId == null) return
    val key = "$identity|$originId|$entryId|$isDetail"
    val existing = registrations[key]
    if (owner != null && (existing == null || existing.owner !== owner)) return
    if (existing != null) registrations.remove(key)
    val active = transaction ?: return
    if (active.direction == SharedCoverDirection.Forward &&
        active.targetEntryId == entryId &&
        isDetail &&
        entryId !in currentEntryIds) {
      cancel(clearRelatedAnchor = false)
    }
    if (active.direction == SharedCoverDirection.Return &&
        active.targetEntryId == entryId &&
        !isDetail &&
        active.targetReady) {
      cancel(clearRelatedAnchor = true)
    }
    if (isDetail && active.direction != SharedCoverDirection.Return) {
      val anchor = returnAnchors[entryId]
      if (anchor?.identity == identity && active.targetEntryId != entryId)
          returnAnchors.remove(entryId)
    }
  }

  fun begin(identity: String?, originId: String, entryId: String): Boolean {
    if (identity.isNullOrBlank() || originId.isBlank() || entryId != originId) return false
    val source =
        registrations.values.firstOrNull {
          it.identity == identity &&
              it.originId == originId &&
              it.entryId == entryId &&
              !it.isDetail &&
              !it.animated &&
              !it.failed &&
              it.attached() &&
              it.currentlyVisible() &&
              it.rect.hasArea
        } ?: return false
    val sourceRect = source.visualRectProvider?.invoke() ?: source.rect
    if (!sourceRect.hasArea) return false
    returnAnchors.entries.removeAll { it.value.originEntryId == entryId }
    transaction =
        SharedCoverTransaction(
            generation = ++nextGeneration,
            identity = identity,
            originId = originId,
            sourceEntryId = entryId,
            sourceRect = sourceRect,
            painter = source.painter,
            sourceOwner = source.owner,
            originRect = source.rectProvider?.invoke() ?: source.rect,
        )
    return true
  }

  fun beginReturn(identity: String?, originId: String, detailEntryId: String): Boolean {
    if (identity.isNullOrBlank() || originId.isBlank()) return false
    val anchor = returnAnchors[detailEntryId] ?: return false
    val detail =
        registrations.values.firstOrNull {
          it.identity == identity &&
              it.originId == originId &&
              it.entryId == detailEntryId &&
              it.isDetail &&
              it.painter != null &&
              !it.failed &&
              !it.animated &&
              it.attached() &&
              it.currentlyVisible()
        } ?: return false
    if (anchor.identity != identity ||
        anchor.mediaIdentity != identity ||
        anchor.originId != originId ||
        !anchor.rect.hasArea)
        return false
    val sourceRect = detail.visualRectProvider?.invoke() ?: detail.rect
    if (!sourceRect.hasArea) return false
    val targetRegistration = registrations["${identity}|${originId}|${anchor.originEntryId}|false"]
    transaction =
        SharedCoverTransaction(
            generation = ++nextGeneration,
            identity = identity,
            originId = originId,
            sourceEntryId = detailEntryId,
            sourceRect = sourceRect,
            painter = detail.painter,
            targetEntryId = anchor.originEntryId,
            targetRect = null,
            targetReady = false,
            direction = SharedCoverDirection.Return,
            pendingTargetOwner = targetRegistration?.owner,
            pendingTargetPlacementRevision =
                targetRegistration?.placementRevisionProvider?.invoke(),
            pendingTargetPaneWasActive = targetRegistration?.paneActiveProvider?.invoke() ?: true,
        )
    return true
  }

  fun cancelForEntry(entryId: String?) {
    val active = transaction ?: return
    if (entryId != null && entryId in setOfNotNull(active.sourceEntryId, active.targetEntryId))
        cancel(clearRelatedAnchor = false)
  }

  fun cancel() {
    cancel(clearRelatedAnchor = true)
    returnAnchors.clear()
  }

  private fun cancel(clearRelatedAnchor: Boolean) {
    val active = transaction
    transaction = null
    if (clearRelatedAnchor && active != null) {
      active.targetEntryId?.let(returnAnchors::remove)
      returnAnchors.entries.removeAll {
        it.value.originEntryId == active.sourceEntryId ||
            it.value.detailEntryId == active.sourceEntryId
      }
    }
  }

  fun finish(generation: Long) {
    val active = transaction?.takeIf { it.generation == generation } ?: return
    transaction = null
    if (active.direction == SharedCoverDirection.Return) returnAnchors.remove(active.sourceEntryId)
  }

  /** Resolve a pending return from the live endpoint as the hidden pane is placed again. */
  fun samplePendingReturnTarget(generation: Long): Boolean {
    val active =
        transaction?.takeIf {
          it.generation == generation && it.direction == SharedCoverDirection.Return
        } ?: return false
    if (active.targetReady) return false
    val targetEntryId = active.targetEntryId ?: return true
    val registration =
        registrations["${active.identity}|${active.originId}|$targetEntryId|false"] ?: return true
    if (!isFreshPendingTarget(active, registration)) return true
    if (registration.failed || registration.animated) {
      cancel(clearRelatedAnchor = true)
      return false
    }
    if (!registration.attached() || !registration.canonicallyVisible()) return true
    val rect = registration.rectProvider?.invoke() ?: registration.rect
    if (!rect.hasArea) return true
    transaction = active.copy(targetRect = rect, targetReady = true)
    return false
  }

  fun recordEndpointPlacement(
      identity: String,
      originId: String,
      entryId: String,
      isDetail: Boolean,
      owner: Any,
      revision: AtomicLong,
  ) {
    val active = transaction ?: return
    if (active.direction == SharedCoverDirection.Return &&
        !active.targetReady &&
        !isDetail &&
        active.identity == identity &&
        active.originId == originId &&
        active.targetEntryId == entryId &&
        active.pendingTargetOwner === owner) {
      revision.incrementAndGet()
    }
  }

  fun isHidden(identity: String?, originId: String?, entryId: String, isDetail: Boolean): Boolean {
    val active = transaction ?: return false
    if (identity != active.identity ||
        originId != active.originId ||
        !active.targetReady ||
        active.painter == null)
        return false
    return if (active.direction == SharedCoverDirection.Forward) {
      if (isDetail) active.targetEntryId == entryId else active.sourceEntryId == entryId
    } else {
      if (isDetail) active.sourceEntryId == entryId else active.targetEntryId == entryId
    }
  }

  private fun SharedCoverRegistration.key() = "$identity|$originId|$entryId|$isDetail"

  private fun storyCoverPostKey(identity: String, originId: String): String? {
    val prefix = "story-cover:$originId:"
    if (!identity.startsWith(prefix)) return null
    val postIdentity = identity.removePrefix(prefix)
    val sourceSeparator = postIdentity.indexOf(':')
    if (sourceSeparator <= 0) return null
    val urlSeparator =
        postIdentity.indexOf(":https://", startIndex = sourceSeparator + 1, ignoreCase = true)
    if (urlSeparator <= sourceSeparator + 1) return null
    return "$prefix${postIdentity.substring(0, urlSeparator)}"
  }

  private fun sameStoryCoverPost(first: String, second: String, originId: String): Boolean {
    val firstKey = storyCoverPostKey(first, originId) ?: return false
    return firstKey == storyCoverPostKey(second, originId)
  }

  private fun SharedCoverRegistration.attached(): Boolean = attachedProvider?.invoke() ?: true

  private fun SharedCoverRegistration.currentlyVisible(): Boolean =
      visibleProvider?.invoke() ?: visible

  private fun SharedCoverRegistration.canonicallyVisible(): Boolean =
      canonicalVisibleProvider?.invoke() ?: currentlyVisible()

  private fun SharedCoverRegistration.paneIsActive(): Boolean = paneActiveProvider?.invoke() ?: true

  private fun isFreshPendingTarget(
      active: SharedCoverTransaction,
      registration: SharedCoverRegistration,
  ): Boolean {
    if (active.direction != SharedCoverDirection.Return || active.targetReady) return true
    if (!registration.paneIsActive()) return false
    if (active.pendingTargetOwner == null || registration.owner !== active.pendingTargetOwner)
        return true
    if (active.pendingTargetPaneWasActive) return true
    val baseline = active.pendingTargetPlacementRevision ?: return true
    return (registration.placementRevisionProvider?.invoke() ?: baseline) > baseline
  }
}

@Composable
internal fun cancelSharedCoverOnScroll(
    listState: LazyListState,
    entryId: String?,
    coordinator: SharedCoverCoordinator?,
) {
  if (coordinator == null || entryId == null) return
  LaunchedEffect(listState, entryId, coordinator) {
    snapshotFlow { listState.isScrollInProgress }
        .filter { it }
        .collect { coordinator.cancelForEntry(entryId) }
  }
}

@Composable
internal fun cancelSharedCoverOnScroll(
    scrollState: ScrollState,
    entryId: String?,
    coordinator: SharedCoverCoordinator?,
) {
  if (coordinator == null || entryId == null) return
  LaunchedEffect(scrollState, entryId, coordinator) {
    snapshotFlow { scrollState.isScrollInProgress }
        .filter { it }
        .collect { coordinator.cancelForEntry(entryId) }
  }
}

@Composable
internal fun SharedCoverOverlayHost(
    coordinator: SharedCoverCoordinator,
    modifier: Modifier = Modifier,
) {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(coordinator, lifecycle) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP)
          coordinator.cancel()
    }
    lifecycle.addObserver(observer)
    onDispose {
      lifecycle.removeObserver(observer)
      coordinator.cancel()
    }
  }
  val active = coordinator.transaction
  val progress = active?.let { transaction -> remember(transaction.generation) { Animatable(0f) } }
  val frameRateController =
      rememberMotionFrameRateHintController(
          active?.targetReady == true && active.targetRect != null && active.painter != null)
  LaunchedEffect(active?.generation) {
    val generation = active?.generation ?: return@LaunchedEffect
    val target =
        withTimeoutOrNull(800) {
          snapshotFlow {
                coordinator.transaction?.takeIf {
                  it.generation == generation && it.targetRect != null && it.painter != null
                }
              }
              .first { it != null }
        }
    if (target == null) coordinator.finish(generation)
  }
  LaunchedEffect(active?.generation) {
    val current = active ?: return@LaunchedEffect
    if (current.direction != SharedCoverDirection.Return || current.targetReady)
        return@LaunchedEffect
    withTimeoutOrNull(800) {
      while (coordinator.samplePendingReturnTarget(current.generation)) {
        withFrameNanos {}
      }
    }
  }
  LaunchedEffect(active?.generation, active?.targetRect, active?.targetReady, active?.painter) {
    val current = active ?: return@LaunchedEffect
    if (!current.targetReady || current.targetRect == null || current.painter == null)
        return@LaunchedEffect
    val animator = progress ?: return@LaunchedEffect
    val frameRateLease = frameRateController?.acquire()
    try {
      animator.animateTo(1f, tween(MotionTokens.SHARED, easing = MotionTokens.EMPHASIZED))
    } finally {
      frameRateLease?.close()
    }
    coordinator.finish(current.generation)
  }
  val density = LocalDensity.current
  Box(
      modifier
          .onGloballyPositioned { coordinator.updateHostOrigin(it.localToRoot(Offset.Zero)) }
          .fillMaxSize()) {
        if (active != null &&
            active.targetReady &&
            active.targetRect != null &&
            active.painter != null) {
          val start = active.sourceRect
          val end = active.targetRect
          val host = coordinator.hostOrigin
          val targetWidth = end.width.coerceAtLeast(1f)
          val targetHeight = end.height.coerceAtLeast(1f)
          val startWidth = start.width.coerceAtLeast(1f)
          val startHeight = start.height.coerceAtLeast(1f)
          val intrinsic = active.painter.intrinsicSize
          val intrinsicWidth = intrinsic.width.takeIf { it.isFinite() && it > 0f } ?: targetWidth
          val intrinsicHeight = intrinsic.height.takeIf { it.isFinite() && it > 0f } ?: targetHeight
          val baseFitScale =
              min(targetWidth / intrinsicWidth, targetHeight / intrinsicHeight)
                  .coerceAtLeast(0.001f)
          Box(
              Modifier.offset {
                    IntOffset((start.left - host.x).toInt(), (start.top - host.y).toInt())
                  }
                  .requiredSize(
                      with(density) { targetWidth.toDp() }, with(density) { targetHeight.toDp() })
                  .graphicsLayer {
                    val fraction = progress?.value ?: 1f
                    val widthScale =
                        startWidth / targetWidth + (1f - startWidth / targetWidth) * fraction
                    val heightScale =
                        startHeight / targetHeight + (1f - startHeight / targetHeight) * fraction
                    translationX = (end.left - start.left) * fraction
                    translationY = (end.top - start.top) * fraction
                    scaleX = widthScale
                    scaleY = heightScale
                    transformOrigin = TransformOrigin(0f, 0f)
                    shape = RoundedCornerShape(18.dp)
                    clip = true
                  }
                  .testTag(
                      "shared-cover-overlay-${active.direction.name.lowercase()}",
                  )) {
                Image(
                    painter = active.painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier =
                        Modifier.fillMaxSize().graphicsLayer {
                          val fraction = progress?.value ?: 1f
                          val outerX =
                              startWidth / targetWidth + (1f - startWidth / targetWidth) * fraction
                          val outerY =
                              startHeight / targetHeight +
                                  (1f - startHeight / targetHeight) * fraction
                          val cropScale =
                              max(
                                  targetWidth * outerX / intrinsicWidth,
                                  targetHeight * outerY / intrinsicHeight)
                          scaleX = cropScale / (baseFitScale * outerX.coerceAtLeast(0.001f))
                          scaleY = cropScale / (baseFitScale * outerY.coerceAtLeast(0.001f))
                        },
                )
              }
        }
      }
}
