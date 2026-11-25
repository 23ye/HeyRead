package cn.heyread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import cn.heyread.app.navigation.HeyReadNavigation
import cn.heyread.app.viewmodel.AppViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
  private var startupPreDrawGate: StartupPreDrawGate? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val gate = StartupPreDrawGate(window.decorView)
    startupPreDrawGate = gate
    setContent {
      val viewModel: AppViewModel = hiltViewModel()
      val ui by viewModel.state.collectAsStateWithLifecycle()
      StartupAppearanceContent(ui.startupAppearanceReady, gate) { HeyReadNavigation(viewModel) }
    }
  }

  override fun onDestroy() {
    startupPreDrawGate?.dispose()
    startupPreDrawGate = null
    super.onDestroy()
  }
}
