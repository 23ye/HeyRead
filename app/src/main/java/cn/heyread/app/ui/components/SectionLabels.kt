package cn.heyread.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cn.heyread.app.data.model.ArticleLabel
import coil.compose.AsyncImage

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SectionLabels(
    sections: List<ArticleLabel>,
    noImages: Boolean,
    modifier: Modifier = Modifier,
    onClick: ((ArticleLabel) -> Unit)? = null,
) {
    val labels = sections.distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }
    if (labels.isEmpty()) return
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEach { label -> SectionLabelChip(label, noImages, onClick) }
    }
}

@Composable
private fun SectionLabelChip(
    label: ArticleLabel,
    noImages: Boolean,
    onClick: ((ArticleLabel) -> Unit)?,
) {
    var imageFailed by remember(label.imageUrl) { mutableStateOf(false) }
    Row(
        Modifier.heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (label.id.isNotBlank() && onClick != null) Modifier.clickable { onClick(label) }
                else Modifier
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("section-label-${label.id.ifBlank { label.name }}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Surface(
            Modifier.size(20.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (!noImages && !imageFailed && !label.imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = label.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.clip(CircleShape),
                    onError = { imageFailed = true },
                )
            } else {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.padding(3.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(label.name, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
