package cn.heyread.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.heyread.app.data.model.ArticleLabel
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArticleTaxonomy(
    sections: List<ArticleLabel>,
    hashtags: List<ArticleLabel>,
    noImages: Boolean = false,
    onSectionClick: ((ArticleLabel) -> Unit)? = null,
    onHashtagClick: ((ArticleLabel) -> Unit)? = null,
) {
    if (sections.isEmpty() && hashtags.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sections.isNotEmpty()) {
            TaxonomyGroup("分区") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    sections
                        .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }
                        .forEach { label ->
                            TaxonomyPill(
                                "${label.name}",
                                "section-label-${label.id.ifBlank { label.name }}",
                                "分区：${label.name}",
                                clickable = label.id.isNotBlank() && onSectionClick != null,
                                onClick = { onSectionClick?.invoke(label) },
                                iconUrl = label.imageUrl,
                                noImages = noImages,
                            )
                        }
                }
            }
        }
        if (hashtags.isNotEmpty()) {
            TaxonomyGroup("话题") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    hashtags
                        .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }
                        .forEach { label ->
                            TaxonomyPill(
                                "#${label.name}",
                                "hashtag-label-${label.id.ifBlank { label.name }}",
                                "话题：${label.name}",
                                clickable = label.id.isNotBlank() && onHashtagClick != null,
                                onClick = { onHashtagClick?.invoke(label) },
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun TaxonomyGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun TaxonomyPill(
    label: String,
    tag: String,
    accessibilityLabel: String,
    clickable: Boolean,
    onClick: () -> Unit,
    iconUrl: String? = null,
    noImages: Boolean = true,
) {
    var iconFailed by remember(iconUrl) { mutableStateOf(false) }
    Box(
        Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .then(if (clickable) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = accessibilityLabel }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.heightIn(min = 32.dp),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tag.startsWith("section-")) {
                    val validIcon =
                        iconUrl?.takeIf {
                            !noImages && !iconFailed && it.startsWith("https://", true)
                        }
                    if (validIcon != null) {
                        AsyncImage(
                            model = validIcon,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            onError = { iconFailed = true },
                        )
                    } else {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    label,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
