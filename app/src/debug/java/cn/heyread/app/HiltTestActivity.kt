package cn.heyread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cn.heyread.app.data.account.AccountRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.components.ActivityComponent
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.scopes.ViewModelScoped
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@AndroidEntryPoint class HiltTestActivity : ComponentActivity()

@AndroidEntryPoint
class HiltScopeTestActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.padding(16.dp)) {
            val activityProbe: HiltScopeProbeViewModel = hiltViewModel()
            Text(activityProbe.instanceId, Modifier.testTag("hilt-activity-vm-id"))
            HiltProbeNavigation(Modifier.fillMaxWidth().weight(1f))
          }
        }
      }
    }
  }
}

@EntryPoint
@InstallIn(ActivityComponent::class)
interface HiltTestActivityEntryPoint {
  fun accountRepository(): AccountRepository
}

@Singleton
class HiltProbeSingletonToken @Inject constructor() {
  val id: String = UUID.randomUUID().toString()
}

@ViewModelScoped
class HiltProbeViewModelToken @Inject constructor() {
  val id: String = UUID.randomUUID().toString()
}

@HiltViewModel
class HiltScopeProbeViewModel
@Inject
constructor(
    val singletonToken: HiltProbeSingletonToken,
    val viewModelToken: HiltProbeViewModelToken,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
  val instanceId: String = UUID.randomUUID().toString()
  val draft = savedStateHandle.getStateFlow(DRAFT_KEY, "")

  fun updateDraft(value: String) {
    savedStateHandle[DRAFT_KEY] = value
  }

  companion object {
    private const val DRAFT_KEY = "hilt_probe_draft"
  }
}

@Composable
private fun HiltProbeNavigation(modifier: Modifier = Modifier) {
  val nav = rememberNavController()
  NavHost(navController = nav, startDestination = "hilt-probe/first", modifier = modifier) {
    composable(
        route = "hilt-probe/{page}",
        arguments = listOf(navArgument("page") { type = NavType.StringType }),
    ) { entry ->
      HiltProbePage(
          entry = entry,
          onNext = { nav.navigate("hilt-probe/second") },
          onBack = { nav.popBackStack() },
      )
    }
  }
}

@Composable
private fun HiltProbePage(
    entry: NavBackStackEntry,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
  val probe: HiltScopeProbeViewModel = hiltViewModel(entry)
  val sameOwnerProbe: HiltScopeProbeViewModel = hiltViewModel(entry)
  val draft by probe.draft.collectAsStateWithLifecycle()
  val page = entry.arguments?.getString("page").orEmpty()
  Column {
    Text(page, Modifier.testTag("hilt-probe-page"))
    Text(probe.instanceId, Modifier.testTag("hilt-probe-vm-id"))
    Text((probe === sameOwnerProbe).toString(), Modifier.testTag("hilt-probe-same-owner"))
    Text(probe.singletonToken.id, Modifier.testTag("hilt-probe-singleton-id"))
    Text(probe.viewModelToken.id, Modifier.testTag("hilt-probe-viewmodel-id"))
    OutlinedTextField(
        value = draft,
        onValueChange = probe::updateDraft,
        label = { Text("Probe draft") },
        modifier = Modifier.testTag("hilt-probe-draft"),
    )
    if (page == "first") {
      Button(onClick = onNext, modifier = Modifier.testTag("hilt-probe-next")) { Text("Next") }
    } else {
      Button(onClick = onBack, modifier = Modifier.testTag("hilt-probe-back")) { Text("Back") }
    }
  }
}
