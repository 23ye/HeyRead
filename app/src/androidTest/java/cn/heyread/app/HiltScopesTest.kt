package cn.heyread.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.heyread.app.data.account.AccountRepository
import cn.heyread.app.di.ProductionGraphEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HiltScopesTest {
  @get:Rule val compose = createAndroidComposeRule<HiltScopeTestActivity>()

  @Test
  fun singletonGraphIsStableAndActivityOwnedViewModelSurvivesRecreation() {
    lateinit var accountRepositoryBefore: AccountRepository
    compose.activityRule.scenario.onActivity { activity ->
      accountRepositoryBefore =
          EntryPointAccessors.fromActivity(activity, HiltTestActivityEntryPoint::class.java)
              .accountRepository()
      runBlocking(Dispatchers.IO) {
        val first =
            EntryPointAccessors.fromApplication(
                activity.applicationContext,
                ProductionGraphEntryPoint::class.java,
            )
        val second =
            EntryPointAccessors.fromApplication(
                activity.applicationContext,
                ProductionGraphEntryPoint::class.java,
            )
        assertSame(first.libraryStore(), second.libraryStore())
        assertSame(first.localRecordsStore(), second.localRecordsStore())
        assertSame(first.preferencesDataStore(), second.preferencesDataStore())
      }
    }

    val before = compose.onNodeWithTag("hilt-activity-vm-id").textValue()
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    compose.onNodeWithTag("hilt-activity-vm-id").assertTextEquals(before)
    compose.onNodeWithTag("hilt-probe-page").assertIsDisplayed()
    lateinit var accountRepositoryAfter: AccountRepository
    compose.activityRule.scenario.onActivity { activity ->
      accountRepositoryAfter =
          EntryPointAccessors.fromActivity(activity, HiltTestActivityEntryPoint::class.java)
              .accountRepository()
    }
    assertSame(accountRepositoryBefore, accountRepositoryAfter)
  }

  @Test
  fun navEntriesOwnSeparateViewModelsAndReturnRestoresDraftAndEntry() {
    compose.onNodeWithTag("hilt-probe-same-owner").assertTextEquals("true")
    val firstVm = compose.onNodeWithTag("hilt-probe-vm-id").textValue()
    val singleton = compose.onNodeWithTag("hilt-probe-singleton-id").textValue()
    val firstViewModelToken = compose.onNodeWithTag("hilt-probe-viewmodel-id").textValue()
    compose.onNodeWithTag("hilt-probe-draft").performTextInput("first draft")

    compose.onNodeWithTag("hilt-probe-next").performClick()
    compose.onNodeWithTag("hilt-probe-page").assertTextEquals("second")
    compose.onNodeWithTag("hilt-probe-same-owner").assertTextEquals("true")
    val secondVm = compose.onNodeWithTag("hilt-probe-vm-id").textValue()
    assertNotEquals(firstVm, secondVm)
    assertEquals(singleton, compose.onNodeWithTag("hilt-probe-singleton-id").textValue())
    assertNotEquals(
        firstViewModelToken, compose.onNodeWithTag("hilt-probe-viewmodel-id").textValue())
    compose.onNodeWithTag("hilt-probe-draft").performTextInput("second draft")

    compose.onNodeWithTag("hilt-probe-back").performClick()
    compose.onNodeWithTag("hilt-probe-page").assertTextEquals("first")
    compose.onNodeWithTag("hilt-probe-vm-id").assertTextEquals(firstVm)
    assertEquals(
        "first draft",
        compose
            .onNodeWithTag("hilt-probe-draft")
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text,
    )
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteraction.textValue(): String =
      fetchSemanticsNode().config[SemanticsProperties.Text].single().text
}
