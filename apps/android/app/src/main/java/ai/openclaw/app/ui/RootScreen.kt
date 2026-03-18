package ai.openclaw.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import ai.openclaw.app.MainViewModel

@Composable
fun RootScreen(viewModel: MainViewModel) {
  val onboardingCompleted by viewModel.onboardingCompleted.collectAsState()

  if (!onboardingCompleted) {
    LocalGatewaySetupWizard(
      modifier = Modifier.fillMaxSize(),
      onContinue = { viewModel.setOnboardingCompleted(true) },
      onSkip = { viewModel.setOnboardingCompleted(true) },
    )
    return
  }

  PostOnboardingTabs(viewModel = viewModel, modifier = Modifier.fillMaxSize())
}
