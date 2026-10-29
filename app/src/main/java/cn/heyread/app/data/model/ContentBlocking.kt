package cn.heyread.app.data.model

enum class ContentBlockKind(val label: String) {
  KEYWORD("关键词"),
  AUTHOR("作者"),
  SECTION("分区"),
  TOPIC("话题"),
}

data class ContentBlockRule(
    val id: String,
    val kind: ContentBlockKind,
    val value: String,
    val label: String = "",
    val enabled: Boolean = true,
    val source: Source? = null,
)

data class ContentBlockingConfig(
    val enabled: Boolean = true,
    val applyToFavorites: Boolean = false,
    val applyToHistory: Boolean = false,
    val rules: List<ContentBlockRule> = emptyList(),
)

sealed interface ContentBlockingAction {
  data class SetEnabled(val enabled: Boolean) : ContentBlockingAction

  data class SetApplyToFavorites(val enabled: Boolean) : ContentBlockingAction

  data class SetApplyToHistory(val enabled: Boolean) : ContentBlockingAction

  data class AddKeywords(val text: String) : ContentBlockingAction

  data class AddIdentity(
      val kind: ContentBlockKind,
      val targetId: String,
      val label: String = "",
  ) : ContentBlockingAction

  data class EditKeyword(val ruleId: String, val text: String) : ContentBlockingAction

  data class SetRuleEnabled(val ruleId: String, val enabled: Boolean) : ContentBlockingAction

  data class RemoveRule(val ruleId: String) : ContentBlockingAction

  data class UndoAddedRules(val expected: List<ContentBlockRule>) : ContentBlockingAction

  data class MergeImportedRules(
      val rules: List<ContentBlockRule>,
      val expectedRules: List<ContentBlockRule>,
  ) : ContentBlockingAction

  data object ClearRules : ContentBlockingAction

  data object Reload : ContentBlockingAction
}
