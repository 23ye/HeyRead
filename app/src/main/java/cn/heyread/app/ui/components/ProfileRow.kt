package cn.heyread.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.heyread.app.data.model.AuthorProfile
import coil.compose.AsyncImage

@Composable
fun ProfileRow(
    profile: AuthorProfile?,
    fallbackName: String,
    noImages: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    timestamp: String? = null,
    isAuthor: Boolean = false,
    showLevel: Boolean = true,
    onProfileClick: ((AuthorProfile) -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val name = profile?.name?.takeIf(String::isNotBlank) ?: fallbackName
    val avatarSize = if (compact) 36.dp else 46.dp
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Row(
            Modifier.weight(1f)
                .heightIn(min = 48.dp)
                .then(
                    if (!profile?.userId.isNullOrBlank() && onProfileClick != null)
                        Modifier.clickable { onProfileClick(profile!!) }
                    else Modifier
                )
                .testTag(
                    "profile-row-${profile?.userId?.takeIf(String::isNotBlank) ?: "unavailable"}"
                ),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(avatarSize)) {
                Surface(
                    Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            name.take(1),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                if (!noImages && !profile?.avatarUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = profile?.avatarUrl,
                        contentDescription = "$name 头像",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(
                Modifier.weight(1f).padding(top = 1.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        name,
                        Modifier.weight(1f, fill = false),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (compact) 13.sp else 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    profile
                        ?.level
                        ?.takeIf { showLevel && it.isNotBlank() }
                        ?.let { level ->
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                            ) {
                                Text(
                                    level,
                                    Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    if (isAuthor) {
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                "作者",
                                Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 10.sp,
                            )
                        }
                    }
                }
                val meta =
                    listOfNotNull(
                            timestamp?.takeIf(String::isNotBlank),
                            profile?.location?.takeIf(String::isNotBlank),
                        )
                        .joinToString(" · ")
                if (meta.isNotBlank())
                    Text(
                        meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
            }
        }
        trailing()
    }
}
