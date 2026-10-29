package cn.heyread.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import cn.heyread.app.R
import cn.heyread.app.data.model.ReadingPreferences
import cn.heyread.app.viewmodel.SteamTranslationPhase
import cn.heyread.app.viewmodel.SteamTranslationState

private const val TRANSLATION_DOCS = "https://developers.google.com/ml-kit/language/translation"
private const val TRANSLATION_TERMS = "https://developers.google.com/ml-kit/terms"
private const val TRANSLATION_USAGE =
    "https://developers.google.com/ml-kit/language/translation/translation-terms"

@Composable
internal fun SteamTranslationSection(
    storyKey: String,
    originalBody: String,
    state: SteamTranslationState,
    noImages: Boolean,
    readingPreferences: ReadingPreferences = ReadingPreferences(),
    onTranslate: () -> Unit,
    onCancel: () -> Unit,
    onShowOriginal: () -> Unit,
    onShowTranslation: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
  var showInfo by remember(storyKey) { mutableStateOf(false) }
  val stateMatches = state.storyKey == storyKey && state.sourceBody == originalBody
  val phase = if (stateMatches) state.phase else SteamTranslationPhase.IDLE
  val showingTranslation =
      stateMatches && phase == SteamTranslationPhase.READY && state.showingTranslation
  val visibleBody = if (showingTranslation) state.translatedBody ?: originalBody else originalBody

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    when (phase) {
      SteamTranslationPhase.IDLE -> {
        Button(
            onClick = onTranslate,
            modifier =
                Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("steam-translation-start"),
        ) {
          Text("使用 Google 翻译（英→中）")
        }
        Text(
            "首次使用需通过 Wi‑Fi 下载约 30 MB 的语言模型；模型不随应用安装包提供。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "正文与译文在设备上处理，不会发送到 Google。ML Kit 仍可能发送性能与使用指标，并联网更新模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = { showInfo = true },
            modifier = Modifier.heightIn(min = 48.dp).testTag("steam-translation-info"),
        ) {
          Text("翻译说明")
        }
      }
      SteamTranslationPhase.PREPARING_MODEL -> {
        BusyTranslationRow(
            label = "正在准备模型，请保持 Wi‑Fi 连接。首次下载约 30 MB。",
            onCancel = onCancel,
            testTag = "steam-translation-cancel",
        )
      }
      SteamTranslationPhase.TRANSLATING -> {
        BusyTranslationRow(
            label = "正在翻译第 ${state.completedSegments + 1} / ${state.totalSegments} 段…",
            onCancel = onCancel,
            testTag = "steam-translation-cancel",
        )
      }
      SteamTranslationPhase.FAILED -> {
        Text(state.error ?: "翻译暂时失败。原文仍可阅读。", color = MaterialTheme.colorScheme.error)
        OutlinedButton(
            onClick = onTranslate,
            modifier = Modifier.heightIn(min = 48.dp).testTag("steam-translation-retry"),
        ) {
          Text("重试翻译")
        }
        TextButton(
            onClick = { showInfo = true },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("翻译说明")
        }
      }
      SteamTranslationPhase.READY -> {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
              horizontalArrangement = Arrangement.Center,
              verticalAlignment = Alignment.CenterVertically,
              modifier =
                  Modifier.heightIn(min = 48.dp)
                      .clickable { onOpenUrl("https://translate.google.com") }
                      .testTag("steam-translation-attribution"),
          ) {
            Surface(color = Color.White, shape = RoundedCornerShape(4.dp)) {
              Image(
                  painter = painterResource(R.drawable.google_translate_attribution),
                  contentDescription = "Google Translate",
                  modifier =
                      Modifier.width(132.dp)
                          .heightIn(max = 20.dp)
                          .padding(horizontal = 5.dp, vertical = 2.dp),
              )
            }
          }
          Text(
              "机器翻译可能不准确，请以原文为准。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TextButton(
            onClick = if (showingTranslation) onShowOriginal else onShowTranslation,
            modifier = Modifier.heightIn(min = 48.dp).testTag("steam-translation-toggle"),
        ) {
          Text(if (showingTranslation) "查看原文" else "查看译文")
        }
        TextButton(
            onClick = { showInfo = true },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
          Text("翻译说明")
        }
      }
    }

    splitReaderParagraphs(visibleBody).forEach { paragraph ->
      SelectionContainer {
        RichText(
            text = paragraph,
            fontSize = readingPreferences.bodyFontSize,
            lineHeight = readingPreferences.bodyLineHeight,
            fontFamily = readingPreferences.font.toComposeFontFamily(),
            noImages = noImages,
            modifier = Modifier.padding(bottom = readingPreferences.paragraphBottomSpacing),
        )
      }
    }
  }
  MotionAlertDialog(
      visible = showInfo,
      onDismiss = { showInfo = false },
      title = "关于 Google 翻译",
      message = "",
      confirmLabel = "知道了",
      onConfirm = { showInfo = false },
      dismissLabel = "关闭",
      content = {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text("正文与译文在设备上处理，不会发送给 Google。ML Kit 可能向 Google 发送性能和使用指标，也会联网获取或更新语言模型。")
          Text("首次使用需通过 Wi‑Fi 下载约 30 MB 模型；模型不随应用安装包提供。取消只会停止本应用等待，不保证终止 ML Kit 在后台进行的模型下载。")
          Text("译文仅供理解参考，机器翻译可能不准确。请对照原文。")
          Text(
              "本服务可能包含由 Google 提供技术支持的翻译。Google 对这些翻译不作任何明示或默示保证，包括准确性、可靠性，以及适销性、特定用途适用性和不侵权的默示保证。")
          Text("使用前请查看 Google 翻译的使用规则、服务条款及隐私说明。")
          TranslationLinks(onOpenUrl)
        }
      },
  )
}

@Composable
private fun BusyTranslationRow(label: String, onCancel: () -> Unit, testTag: String) {
  Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag(testTag)) {
      Text("取消")
    }
  }
}

@Composable
private fun TranslationLinks(onOpenUrl: (String) -> Unit) {
  Column(Modifier.fillMaxWidth()) {
    TextButton(
        onClick = { onOpenUrl(TRANSLATION_DOCS) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
      Text("翻译说明")
    }
    TextButton(
        onClick = { onOpenUrl(TRANSLATION_USAGE) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
      Text("使用规则")
    }
    TextButton(
        onClick = { onOpenUrl(TRANSLATION_TERMS) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
      Text("服务条款与隐私")
    }
  }
}
