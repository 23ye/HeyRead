package cn.heyread.app.data.media

import kotlin.math.max
import kotlin.math.min

data class MediaPixelSize(val width: Float, val height: Float)

data class MediaPixelOffset(val x: Float, val y: Float)

object MediaViewportGeometry {
    fun fitSize(image: MediaPixelSize, viewport: MediaPixelSize): MediaPixelSize? {
        if (!image.isPositiveFinite() || !viewport.isPositiveFinite()) return null
        val fitScale = min(viewport.width / image.width, viewport.height / image.height)
        return MediaPixelSize(image.width * fitScale, image.height * fitScale)
    }

    fun zoomAroundCentroid(
        pan: MediaPixelOffset,
        oldScale: Float,
        newScale: Float,
        centroid: MediaPixelOffset,
        viewport: MediaPixelSize,
    ): MediaPixelOffset {
        if (newScale <= 1f) return MediaPixelOffset(0f, 0f)
        if (!oldScale.isFinite() || oldScale <= 0f || !viewport.isPositiveFinite()) return pan
        val ratio = newScale / oldScale
        val centerX = viewport.width / 2f
        val centerY = viewport.height / 2f
        return MediaPixelOffset(
            x = pan.x * ratio + (1f - ratio) * (centroid.x - centerX),
            y = pan.y * ratio + (1f - ratio) * (centroid.y - centerY),
        )
    }

    fun constrainPan(
        pan: MediaPixelOffset,
        fittedImage: MediaPixelSize?,
        viewport: MediaPixelSize,
        scale: Float,
    ): MediaPixelOffset {
        if (scale <= 1f) return MediaPixelOffset(0f, 0f)
        if (fittedImage == null || !fittedImage.isPositiveFinite() || !viewport.isPositiveFinite())
            return MediaPixelOffset(0f, 0f)
        val maxX = max(0f, (fittedImage.width * scale - viewport.width) / 2f)
        val maxY = max(0f, (fittedImage.height * scale - viewport.height) / 2f)
        return MediaPixelOffset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
    }

    private fun MediaPixelSize.isPositiveFinite(): Boolean =
        width.isFinite() && height.isFinite() && width > 0f && height > 0f
}
