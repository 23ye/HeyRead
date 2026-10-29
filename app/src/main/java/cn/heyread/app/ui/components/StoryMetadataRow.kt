package cn.heyread.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.heyread.app.data.model.ArticleLabel
import cn.heyread.app.data.model.Source
import cn.heyread.app.data.model.Story
import coil.compose.AsyncImage

@Composable
fun StoryMetadataRow(
    story: Story,
    noImages: Boolean,
    onOpenSection: ((ArticleLabel) -> Unit)?,
    modifier: Modifier = Modifier,
) {
  val author =
      if (story.source == Source.HEYBOX) story.author.ifBlank { "作者未公开" }
      else
          when (story.source) {
            Source.STEAM -> "Steam"
            Source.DEMO -> "演示"
            Source.HEYBOX -> story.author
          }
  BoxWithConstraints(
      modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("story-metadata-${story.id}")) {
        if (maxWidth < 300.dp) {
          Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MetadataAuthor(author)
            Text(
                story.date,
                Modifier.semantics { contentDescription = "时间：${story.date}" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetadataSections(story, noImages, onOpenSection, Modifier.fillMaxWidth())
          }
        } else {
          Row(
              modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(5.dp),
          ) {
            MetadataAuthor(author, Modifier.weight(1f))
            Text(
                story.date,
                Modifier.widthIn(max = 66.dp).semantics { contentDescription = "时间：${story.date}" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetadataSections(story, noImages, onOpenSection)
          }
        }
      }
}

@Composable
private fun MetadataAuthor(author: String, modifier: Modifier = Modifier) {
  Text(
      author,
      modifier.semantics { contentDescription = "作者：$author" },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun MetadataSections(
    story: Story,
    noImages: Boolean,
    onOpenSection: ((ArticleLabel) -> Unit)?,
    modifier: Modifier = Modifier,
) {
  if (story.sections.isNotEmpty()) {
    Row(
        modifier.widthIn(max = 128.dp).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      story.sections
          .distinctBy { if (it.id.isBlank()) "name:${it.name}" else "id:${it.id}" }
          .forEach { label -> CompactSectionLabel(label, noImages, onOpenSection) }
    }
  }
}

@Composable
private fun CompactSectionLabel(
    label: ArticleLabel,
    noImages: Boolean,
    onOpenSection: ((ArticleLabel) -> Unit)?,
) {
  var imageFailed by remember(label.imageUrl) { mutableStateOf(false) }
  val modifier =
      Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
          .then(
              if (label.id.isNotBlank() && onOpenSection != null)
                  Modifier.clickable { onOpenSection(label) }
              else Modifier)
          .semantics { contentDescription = "分区：${label.name}" }
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    val iconUrl =
        label.imageUrl?.takeIf { !noImages && !imageFailed && it.startsWith("https://", true) }
    if (iconUrl != null) {
      AsyncImage(
          model = iconUrl,
          contentDescription = null,
          modifier =
              Modifier.padding(horizontal = 2.dp).size(14.dp).clip(MaterialTheme.shapes.small),
          onError = { imageFailed = true },
      )
    } else {
      Icon(
          Icons.Filled.Star,
          contentDescription = null,
          modifier = Modifier.padding(horizontal = 2.dp).size(14.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
        label.name,
        Modifier.padding(end = 5.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
