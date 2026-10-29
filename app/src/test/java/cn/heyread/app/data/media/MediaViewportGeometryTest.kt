package cn.heyread.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaViewportGeometryTest {
  @Test
  fun fitKeepsSquarePortraitAndLandscapeInsideViewport() {
    val viewport = MediaPixelSize(400f, 300f)

    assertEquals(
      MediaPixelSize(300f, 300f),
      MediaViewportGeometry.fitSize(MediaPixelSize(1000f, 1000f), viewport),
    )
    assertEquals(
      MediaPixelSize(150f, 300f),
      MediaViewportGeometry.fitSize(MediaPixelSize(400f, 800f), viewport),
    )
    assertEquals(
      MediaPixelSize(400f, 200f),
      MediaViewportGeometry.fitSize(MediaPixelSize(800f, 400f), viewport),
    )
  }

  @Test
  fun zoomAroundCentroidPreservesTheContentPointUnderTheFingers() {
    val viewport = MediaPixelSize(400f, 300f)
    val oldPan = MediaPixelOffset(24f, -18f)
    val centroid = MediaPixelOffset(130f, 82f)
    val oldScale = 1.7f
    val newScale = 3.2f
    val updatedPan =
      MediaViewportGeometry.zoomAroundCentroid(oldPan, oldScale, newScale, centroid, viewport)

    val centerX = viewport.width / 2f
    val centerY = viewport.height / 2f
    val contentBeforeX = (centroid.x - centerX - oldPan.x) / oldScale
    val contentBeforeY = (centroid.y - centerY - oldPan.y) / oldScale
    val contentAfterX = (centroid.x - centerX - updatedPan.x) / newScale
    val contentAfterY = (centroid.y - centerY - updatedPan.y) / newScale

    assertEquals(contentBeforeX, contentAfterX, 0.0001f)
    assertEquals(contentBeforeY, contentAfterY, 0.0001f)
  }

  @Test
  fun panUsesScreenPixelDeltasUntilItReachesTheImageBoundsAndReversesImmediately() {
    val viewport = MediaPixelSize(400f, 300f)
    val fitted = MediaViewportGeometry.fitSize(MediaPixelSize(2000f, 1000f), viewport)
    val scale = 3f

    val afterScreenDrag =
      MediaViewportGeometry.constrainPan(MediaPixelOffset(100f, -100f), fitted, viewport, scale)
    assertEquals(MediaPixelOffset(100f, -100f), afterScreenDrag)

    val atEdge =
      MediaViewportGeometry.constrainPan(
        MediaPixelOffset(10_000f, -10_000f),
        fitted,
        viewport,
        scale,
      )
    assertEquals(400f, atEdge.x, 0f)
    assertEquals(-150f, atEdge.y, 0f)

    val reversedFromEdge =
      MediaViewportGeometry.constrainPan(
        MediaPixelOffset(atEdge.x - 24f, atEdge.y + 24f),
        fitted,
        viewport,
        scale,
      )
    assertEquals(376f, reversedFromEdge.x, 0f)
    assertEquals(-126f, reversedFromEdge.y, 0f)
  }

  @Test
  fun oneToOneResetAndInvalidGeometryReturnFiniteBoundedOffsets() {
    val viewport = MediaPixelSize(400f, 300f)
    val fitted = MediaPixelSize(400f, 200f)

    assertEquals(
      MediaPixelOffset(0f, 0f),
      MediaViewportGeometry.constrainPan(MediaPixelOffset(80f, -60f), fitted, viewport, 1f),
    )
    assertEquals(
      MediaPixelOffset(0f, 0f),
      MediaViewportGeometry.constrainPan(MediaPixelOffset(80f, -60f), fitted, viewport, 0.8f),
    )
    assertEquals(
      MediaPixelOffset(0f, 0f),
      MediaViewportGeometry.constrainPan(MediaPixelOffset(80f, -60f), null, viewport, 2f),
    )
    assertEquals(
      MediaPixelOffset(0f, 0f),
      MediaViewportGeometry.constrainPan(
        MediaPixelOffset(80f, -60f),
        fitted,
        MediaPixelSize(Float.POSITIVE_INFINITY, 300f),
        2f,
      ),
    )
    assertNull(MediaViewportGeometry.fitSize(MediaPixelSize(0f, 20f), viewport))
    assertNull(MediaViewportGeometry.fitSize(MediaPixelSize(Float.NaN, 20f), viewport))
    assertNull(MediaViewportGeometry.fitSize(MediaPixelSize(20f, 20f), MediaPixelSize(-1f, 20f)))
  }
}
