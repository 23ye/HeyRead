package cn.heyread.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.heyread.app.data.account.CommentImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest

@Composable
fun CommentImageThumbnail(
    image: CommentImage,
    noImages: Boolean,
    tag: String,
    onClick: () -> Unit = {},
) {
    if (noImages) {
        Surface(
            Modifier.padding(top = 7.dp).width(112.dp).height(76.dp).testTag(tag),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Box(contentAlignment = Alignment.Center) { Text("图片已隐藏") }
        }
        return
    }
    var retry by remember(image.url, image.thumbnailUrl) { mutableIntStateOf(0) }
    var state by
        remember(image.url, image.thumbnailUrl) { mutableStateOf<AsyncImagePainter.State?>(null) }
    val context = LocalContext.current
    val ratio =
        ((image.width ?: 4).toFloat() / (image.height ?: 3).coerceAtLeast(1)).coerceIn(.6f, 1.8f)
    val height = (112.dp / ratio).coerceIn(68.dp, 140.dp)
    Box(
        Modifier.padding(top = 7.dp)
            .width(112.dp)
            .height(height)
            .testTag(tag)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.matchParentSize(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {}
        AnimatedImage(
            model =
                remember(image.url, image.thumbnailUrl, image.originalUrl, image.animated, retry) {
                    ImageRequest.Builder(context)
                        .data(
                            if (image.animated) image.originalUrl ?: image.url
                            else image.thumbnailUrl ?: image.url
                        )
                        .memoryCacheKey(
                            "${if (image.animated) image.originalUrl ?: image.url else image.thumbnailUrl ?: image.url}-$retry"
                        )
                        .build()
                },
            contentDescription = "查看评论图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier.matchParentSize(),
            onState = { state = it },
        )
        if (state !is AsyncImagePainter.State.Success) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    if (state is AsyncImagePainter.State.Error) "加载失败 · 点按重试" else "图片加载中",
                    Modifier.clickable { if (state is AsyncImagePainter.State.Error) retry++ }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
