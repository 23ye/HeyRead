package cn.heyread.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.heyread.app.navigation.HeyReadNavigation
import cn.heyread.app.viewmodel.AppViewModel

class MainActivity : ComponentActivity() {
  private var startupPreDrawGate: StartupPreDrawGate? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val gate = StartupPreDrawGate(window.decorView)
    startupPreDrawGate = gate
    setContent {
      val viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory(application))
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
