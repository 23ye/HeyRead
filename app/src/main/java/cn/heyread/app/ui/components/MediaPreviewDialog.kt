package cn.heyread.app.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import cn.heyread.app.data.media.MediaExporter
import cn.heyread.app.data.media.MediaImage
import cn.heyread.app.data.media.MediaPixelOffset
import cn.heyread.app.data.media.MediaPixelSize
import cn.heyread.app.data.media.MediaViewportGeometry
import cn.heyread.app.data.media.StagedMedia
import cn.heyread.app.ui.motion.MotionFrameRateHint
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MediaImageThumbnail(image: MediaImage, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    var retry by remember(image.previewUrl) { mutableIntStateOf(0) }
    var state by remember(image.previewUrl) { mutableStateOf<AsyncImagePainter.State?>(null) }
    Box(
        modifier.heightIn(min = 176.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedImage(
            model =
                remember(image.previewUrl, image.animationUrl, retry) {
                    ImageRequest.Builder(context)
                        .data(image.animationUrl ?: image.previewUrl)
                        .memoryCacheKey("${image.animationUrl ?: image.previewUrl}-$retry")
                        .build()
                },
            contentDescription = "查看${image.description.ifBlank { "图片" }}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().heightIn(min = 176.dp),
            onState = { state = it },
        )
        if (state !is AsyncImagePainter.State.Success) {
            Surface(color = Color.Black.copy(alpha = .68f), shape = MaterialTheme.shapes.small) {
                Text(
                    if (state is AsyncImagePainter.State.Error) "图片加载失败 · 点击重试" else "图片加载中",
                    Modifier.clickable { if (state is AsyncImagePainter.State.Error) retry++ }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MediaPreviewDialog(
    image: MediaImage,
    onDismiss: () -> Unit,
    onExportPrepared: ((StagedMedia) -> Unit)? = null,
    gallery: List<MediaImage> = listOf(image),
    initialIndex: Int = 0,
) {
    val context = LocalContext.current
    val exporter = remember(context) { MediaExporter(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val gallerySnapshot = remember(image, gallery) { gallery.ifEmpty { listOf(image) }.toList() }
    val safeInitialIndex = initialIndex.coerceIn(0, gallerySnapshot.lastIndex)
    val pagerState =
        rememberPagerState(initialPage = safeInitialIndex, pageCount = { gallerySnapshot.size })
    val currentIndex = pagerState.settledPage.coerceIn(0, gallerySnapshot.lastIndex)
    val currentImage = gallerySnapshot[currentIndex]
    val parentPaneActive = LocalPaneContentActive.current
    var showingOriginalIndex by remember(gallerySnapshot) { mutableIntStateOf(-1) }
    val retryByIndex = remember(gallerySnapshot) { mutableStateMapOf<Int, Int>() }
    val imageStates = remember(gallerySnapshot) { mutableStateMapOf<Int, MediaImageLoadPhase>() }
    val sourceSizes = remember(gallerySnapshot) { mutableStateMapOf<Int, MediaPixelSize>() }
    val gestureCentroid = remember(gallerySnapshot) { mutableStateOf(Offset.Zero) }
    var viewportSize by remember(gallerySnapshot) { mutableStateOf(MediaPixelSize(0f, 0f)) }
    var scale by remember(gallerySnapshot) { mutableFloatStateOf(1f) }
    var pan by remember(gallerySnapshot) { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var stagedForSave by remember { mutableStateOf<StagedMedia?>(null) }
    val currentPageIsOriginal = showingOriginalIndex == currentIndex
    val fittedImageSize =
        sourceSizes[currentIndex]?.let { MediaViewportGeometry.fitSize(it, viewportSize) }
    val canChangePage = !busy && stagedForSave == null
    val saveDocument =
        rememberLauncherForActivityResult(SaveImageDocument()) { uri: Uri? ->
            val staged = stagedForSave
            stagedForSave = null
            if (uri != null && staged != null)
                scope.launch {
                    busy = true
                    message = null
                    try {
                        exporter.copyToUri(staged, uri)
                        message = "图片已保存"
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        message = error.localizedMessage ?: "保存失败"
                    } finally {
                        busy = false
                    }
                }
            else staged?.file?.delete()
        }
    val transformState = rememberTransformableState { zoom, delta, _ ->
        val oldScale = scale
        val newScale = (oldScale * zoom).coerceIn(1f, 6f)
        val centroid = gestureCentroid.value
        val zoomedPan =
            MediaViewportGeometry.zoomAroundCentroid(
                MediaPixelOffset(pan.x, pan.y),
                oldScale,
                newScale,
                MediaPixelOffset(centroid.x, centroid.y),
                viewportSize,
            )
        val boundedPan =
            MediaViewportGeometry.constrainPan(
                MediaPixelOffset(zoomedPan.x + delta.x, zoomedPan.y + delta.y),
                fittedImageSize,
                viewportSize,
                newScale,
            )
        scale = newScale
        pan = Offset(boundedPan.x, boundedPan.y)
    }
    LaunchedEffect(pagerState.settledPage) {
        scale = 1f
        pan = Offset.Zero
        message = null
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        MotionFrameRateHint(pagerState.isScrollInProgress)
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (dialogView.parent as? DialogWindowProvider)?.window
            window?.statusBarColor = android.graphics.Color.BLACK
            window?.navigationBarColor = android.graphics.Color.BLACK
            window?.decorView?.systemUiVisibility = 0
        }
        Surface(
            Modifier.fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding()
                .testTag("media-preview"),
            color = Color.Black,
        ) {
            Column(Modifier.fillMaxSize()) {
                FlowRow(
                    Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.Center,
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier =
                            Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                .testTag("media-close"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭图片", tint = Color.White)
                    }
                    Text(
                        if (currentPageIsOriginal) "原图" else "图片预览",
                        Modifier.align(Alignment.CenterVertically),
                        color = Color.White,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (currentImage.hasHigherResolutionSource)
                            TextButton(
                                onClick = {
                                    showingOriginalIndex =
                                        if (currentPageIsOriginal) -1 else currentIndex
                                    scale = 1f
                                    pan = Offset.Zero
                                    message = null
                                },
                                modifier =
                                    Modifier.sizeIn(minHeight = 48.dp)
                                        .testTag("media-original-toggle"),
                            ) {
                                Text(
                                    if (currentPageIsOriginal) "查看预览" else "查看原图",
                                    color = Color.White,
                                )
                            }
                        IconButton(
                            onClick = {
                                if (!busy && stagedForSave == null)
                                    scope.launch {
                                        val exportIndex = currentIndex
                                        val exportImage = currentImage
                                        busy = true
                                        message = null
                                        try {
                                            val staged =
                                                exporter.prepare(
                                                    exportImage.exportUrl ?: exportImage.previewUrl
                                                )
                                            if (onExportPrepared != null) onExportPrepared(staged)
                                            else if (Build.VERSION.SDK_INT >= 29) {
                                                try {
                                                    exporter.saveToMediaStore(staged)
                                                    if (pagerState.settledPage == exportIndex)
                                                        message = "已保存到 Pictures/HeyRead"
                                                } catch (error: CancellationException) {
                                                    throw error
                                                } catch (error: Exception) {
                                                    staged.file.delete()
                                                    if (pagerState.settledPage == exportIndex)
                                                        message = error.localizedMessage ?: "保存失败"
                                                }
                                            } else {
                                                stagedForSave = staged
                                                saveDocument.launch(
                                                    SaveImageRequest(
                                                        staged.displayName,
                                                        staged.mimeType,
                                                    )
                                                )
                                            }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Exception) {
                                            if (pagerState.settledPage == exportIndex)
                                                message = error.localizedMessage ?: "图片下载失败"
                                        } finally {
                                            busy = false
                                        }
                                    }
                            },
                            enabled = !busy && stagedForSave == null,
                            modifier =
                                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("media-save"),
                        ) {
                            if (busy)
                                CircularProgressIndicator(
                                    Modifier.size(20.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp,
                                )
                            else Text("保存", color = Color.White)
                        }
                        IconButton(
                            onClick = {
                                if (!busy && stagedForSave == null)
                                    scope.launch {
                                        val exportIndex = currentIndex
                                        val exportImage = currentImage
                                        busy = true
                                        message = null
                                        try {
                                            val staged =
                                                exporter.prepare(
                                                    exportImage.exportUrl ?: exportImage.previewUrl
                                                )
                                            val intent = exporter.shareIntent(staged)
                                            context.startActivity(
                                                Intent.createChooser(intent, "分享图片")
                                            )
                                            if (pagerState.settledPage == exportIndex)
                                                message = "已打开系统分享面板"
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Exception) {
                                            if (pagerState.settledPage == exportIndex)
                                                message = error.localizedMessage ?: "图片准备失败"
                                        } finally {
                                            busy = false
                                        }
                                    }
                            },
                            enabled = !busy && stagedForSave == null,
                            modifier =
                                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .testTag("media-share"),
                        ) {
                            Icon(Icons.Filled.Share, "分享图片", tint = Color.White)
                        }
                    }
                }
                Box(
                    Modifier.weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .onSizeChanged {
                            val nextViewport =
                                MediaPixelSize(it.width.toFloat(), it.height.toFloat())
                            viewportSize = nextViewport
                            val nextFit =
                                sourceSizes[currentIndex]?.let {
                                    MediaViewportGeometry.fitSize(it, nextViewport)
                                }
                            val bounded =
                                MediaViewportGeometry.constrainPan(
                                    MediaPixelOffset(pan.x, pan.y),
                                    nextFit,
                                    nextViewport,
                                    scale,
                                )
                            pan = Offset(bounded.x, bounded.y)
                        }
                        .pointerInput(gallerySnapshot.size) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val centroid = event.calculateCentroid(useCurrent = false)
                                    if (centroid.isSpecified) gestureCentroid.value = centroid
                                } while (event.changes.any { it.pressed })
                            }
                        }
                        .pointerInput(gallerySnapshot.size, fittedImageSize, viewportSize) {
                            detectTapGestures(
                                onDoubleTap = { centroid ->
                                    val oldScale = scale
                                    val newScale = if (oldScale > 1f) 1f else 2.5f
                                    val zoomedPan =
                                        MediaViewportGeometry.zoomAroundCentroid(
                                            MediaPixelOffset(pan.x, pan.y),
                                            oldScale,
                                            newScale,
                                            MediaPixelOffset(centroid.x, centroid.y),
                                            viewportSize,
                                        )
                                    val boundedPan =
                                        MediaViewportGeometry.constrainPan(
                                            zoomedPan,
                                            fittedImageSize,
                                            viewportSize,
                                            newScale,
                                        )
                                    scale = newScale
                                    pan = Offset(boundedPan.x, boundedPan.y)
                                }
                            )
                        }
                        .semantics {
                            this[MediaScaleSemanticsKey] = scale
                            this[MediaPanXSemanticsKey] = pan.x
                            this[MediaPanYSemanticsKey] = pan.y
                            stateDescription = "放大 ${scale} 倍"
                        }
                        .testTag("media-image"),
                    contentAlignment = Alignment.Center,
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().testTag("media-gallery-pager"),
                        userScrollEnabled = scale <= 1f && canChangePage,
                        key = { it },
                    ) { page ->
                        val pageImage = gallerySnapshot[page]
                        val stablePage =
                            page == pagerState.settledPage && !pagerState.isScrollInProgress
                        val pageUsesOriginal = showingOriginalIndex == page
                        val requestUrl =
                            if (pageUsesOriginal && page == pagerState.settledPage)
                                pageImage.exportUrl ?: pageImage.previewUrl
                            else pageImage.animationUrl ?: pageImage.previewUrl
                        val retry = retryByIndex[page] ?: 0
                        val pageState = imageStates[page] ?: MediaImageLoadPhase.Loading
                        CompositionLocalProvider(
                            LocalPaneContentActive provides (parentPaneActive && stablePage)
                        ) {
                            Box(
                                Modifier.fillMaxSize()
                                    .transformable(transformState, canPan = { scale > 1f })
                                    .then(
                                        if (stablePage) Modifier
                                        else Modifier.semantics { hideFromAccessibility() }
                                    )
                            ) {
                                AnimatedImage(
                                    model =
                                        remember(requestUrl, retry) {
                                            ImageRequest.Builder(context)
                                                .data(requestUrl)
                                                .memoryCacheKey("$requestUrl-$retry")
                                                .build()
                                        },
                                    contentDescription =
                                        if (stablePage) pageImage.description.ifBlank { "全屏图片" }
                                        else null,
                                    contentScale = ContentScale.Fit,
                                    modifier =
                                        Modifier.fillMaxSize()
                                            .graphicsLayer(
                                                scaleX = if (stablePage) scale else 1f,
                                                scaleY = if (stablePage) scale else 1f,
                                                translationX = if (stablePage) pan.x else 0f,
                                                translationY = if (stablePage) pan.y else 0f,
                                            )
                                            .testTag("media-image-page-$page"),
                                    onState = { state ->
                                        imageStates[page] =
                                            when (state) {
                                                is AsyncImagePainter.State.Success ->
                                                    MediaImageLoadPhase.Success
                                                is AsyncImagePainter.State.Error ->
                                                    MediaImageLoadPhase.Error
                                                else -> MediaImageLoadPhase.Loading
                                            }
                                        val intrinsic =
                                            (state as? AsyncImagePainter.State.Success)
                                                ?.painter
                                                ?.intrinsicSize
                                        if (
                                            intrinsic != null &&
                                                intrinsic.width.isFinite() &&
                                                intrinsic.height.isFinite() &&
                                                intrinsic.width > 0f &&
                                                intrinsic.height > 0f
                                        )
                                            sourceSizes[page] =
                                                MediaPixelSize(intrinsic.width, intrinsic.height)
                                        if (
                                            intrinsic != null &&
                                                page == pagerState.settledPage &&
                                                !pagerState.isScrollInProgress
                                        ) {
                                            val nextFit =
                                                MediaViewportGeometry.fitSize(
                                                    MediaPixelSize(
                                                        intrinsic.width,
                                                        intrinsic.height,
                                                    ),
                                                    viewportSize,
                                                )
                                            val bounded =
                                                MediaViewportGeometry.constrainPan(
                                                    MediaPixelOffset(pan.x, pan.y),
                                                    nextFit,
                                                    viewportSize,
                                                    scale,
                                                )
                                            pan = Offset(bounded.x, bounded.y)
                                        }
                                    },
                                )
                                if (stablePage && pageState != MediaImageLoadPhase.Success) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            if (pageState == MediaImageLoadPhase.Error) "图片加载失败"
                                            else "图片加载中…",
                                            color = Color.White,
                                        )
                                        if (pageState == MediaImageLoadPhase.Error)
                                            TextButton(
                                                onClick = { retryByIndex[page] = retry + 1 }
                                            ) {
                                                Text("重试", color = Color.White)
                                            }
                                        if (
                                            pageUsesOriginal &&
                                                pageState == MediaImageLoadPhase.Error
                                        )
                                            TextButton(
                                                onClick = {
                                                    showingOriginalIndex = -1
                                                    retryByIndex[page] = 0
                                                }
                                            ) {
                                                Text("返回预览", color = Color.White)
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
                val hintParts = buildList {
                    if (!currentImage.hasHigherResolutionSource) add("未提供更高清原图")
                    if (gallerySnapshot.size > 1) add("左右滑动切图")
                    add("双指缩放")
                    add("双击放大或还原")
                }
                Text(
                    hintParts.joinToString(" · "),
                    Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 8.dp),
                    color = Color.LightGray,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (gallerySnapshot.size > 1)
                    Text(
                        "${pagerState.currentPage + 1}/${gallerySnapshot.size}",
                        Modifier.align(Alignment.CenterHorizontally)
                            .padding(bottom = 4.dp)
                            .testTag("media-page-indicator"),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                message?.let {
                    Text(
                        it,
                        Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

private enum class MediaImageLoadPhase {
    Loading,
    Success,
    Error,
}

internal val MediaScaleSemanticsKey = SemanticsPropertyKey<Float>("MediaScale")
internal val MediaPanXSemanticsKey = SemanticsPropertyKey<Float>("MediaPanX")
internal val MediaPanYSemanticsKey = SemanticsPropertyKey<Float>("MediaPanY")

private data class SaveImageRequest(val name: String, val mime: String)

private class SaveImageDocument : ActivityResultContract<SaveImageRequest, Uri?>() {
    override fun createIntent(context: Context, input: SaveImageRequest): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.mime
            putExtra(Intent.EXTRA_TITLE, input.name)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
}
